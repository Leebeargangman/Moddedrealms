// A team of bots that meet up and survive together, coordinated by a leader AI.
import { EventEmitter } from 'node:events'
import { chatJSON } from '../llm.js'
import { SKILLS, ARGS_SCHEMA } from '../agent/skills.js'
import { gearScore } from '../agent/combat.js'

const TOGETHER_RADIUS = 48 // everyone within this of the leader counts as "together"
const MOVING = new Set(['travel_far', 'goto', 'explore', 'come_to'])

const SYSTEM = `You are the leader of a small team of Minecraft survival bots playing together. You see every
member's status and decide what each one does next so the team survives and gets stronger together.
Principles:
- Stay together: work within ~40 blocks of each other unless a job needs otherwise; regroup if someone is far.
- Split the work by what each member has: wood and food first, then stone tools, a shared base and stash,
  beds (respawn point), iron, diamonds. Give the best gear to whoever fights.
- Keep someone guarding (follow_player the leader) when hostile mobs or strangers are around or at night.
- Hurt or hungry members should eat, hide or stay near the group. Avoid strange players.
- A hurt member with no food in its inventory can't eat: a member who has food should "give" it some (item, count, name).
- The leader may move the whole team (travel_far/goto/explore); the others will follow automatically.
- Only use members' exact names. Give each member at most one action.
- Members listed under "offline" are not in the game (they rejoin later): give them no orders and send nobody to them.
Reply with ONLY JSON:
{"thought": "<one short sentence>", "directive": "<one sentence everyone should keep in mind>",
 "orders": [{"bot": "<name>", "action": "<action>", "args": {...}, "why": "<few words>"}]}
Actions:
${Object.entries(SKILLS).map(([k, v]) => `- ${k} ${v.args}: ${v.desc}`).join('\n')}`

const SCHEMA = {
  type: 'object',
  properties: {
    thought: { type: 'string', maxLength: 200 },
    directive: { type: 'string', maxLength: 200 },
    orders: {
      type: 'array',
      items: {
        type: 'object',
        properties: { bot: { type: 'string', maxLength: 16 }, action: { type: 'string', enum: Object.keys(SKILLS) }, args: ARGS_SCHEMA, why: { type: 'string', maxLength: 200 } },
        required: ['bot', 'action', 'args']
      }
    }
  },
  required: ['thought', 'directive', 'orders']
}

export class Team extends EventEmitter {
  /** cfg: { id, name, leader: botId, members: [botId], leaderModel?, intervalMs? } */
  constructor ({ cfg, bots, log }) {
    super()
    this.cfg = cfg
    this.id = cfg.id
    this.bots = bots // Map id -> BotInstance (the manager's)
    this.log = (...a) => log(`[team ${cfg.id}]`, ...a)
    this.phase = 'gathering'
    this.lastPlan = null
    this.feed = [] // recent team events for the website
  }

  members () { return this.cfg.members.map(id => this.bots.get(id)).filter(Boolean) }
  leader () { return this.bots.get(this.cfg.leader) }
  /** In-game names, so members treat each other as friends. */
  names () { return this.members().map(b => b.session?.name ?? b.cfg.name).filter(Boolean) }

  start () {
    for (const b of this.members()) b.team = this
    this.timer = setInterval(() => this.tick().catch(e => this.log('tick failed:', e.message)), 15000)
    this.log(`started with ${this.cfg.members.length} members, leader ${this.cfg.leader}`)
  }

  stop () {
    clearInterval(this.timer)
    for (const b of this.members()) if (b.team === this) b.team = null
  }

  note (text) {
    this.feed.push({ t: new Date().toISOString(), text })
    if (this.feed.length > 50) this.feed.shift()
    this.emit('event', { type: 'team', team: this.id, text })
    this.log(text)
  }

  playing () {
    return this.members().filter(b => b.state === 'online' && b.agent?.inGame())
  }

  // ------------------------------------------------------------ the loop

  async tick () {
    const leader = this.leader()
    const online = this.playing()
    if (!leader?.agent?.inGame() || online.length < 2) return

    const lp = leader.body.pos
    const far = online.filter(b => b !== leader && (b.body.dimension !== leader.body.dimension || dist(b.body.pos, lp) > TOGETHER_RADIUS))
    if (far.length) {
      if (this.phase !== 'gathering') this.note(`regrouping: ${far.map(b => b.session.name).join(', ')} ${far.length > 1 ? 'are' : 'is'} away`)
      this.phase = 'gathering'
      // The leader waits; everyone else comes to it (through the nether if it's far).
      if (!isBusy(leader, 'wait')) this.order(leader, 'wait', { seconds: 60 }, 'waiting for the team')
      for (const b of far) {
        if (isBusy(b, 'come_to')) continue
        this.order(b, 'come_to', { name: leader.session.name, x: Math.round(lp.x), y: Math.round(lp.y), z: Math.round(lp.z) }, 'meeting up')
      }
      return
    }
    if (this.phase !== 'together') this.note('everyone is together')
    this.phase = 'together'

    // Whoever is following the leader keeps doing so while it travels.
    if (MOVING.has(leader.agent.task?.name)) {
      for (const b of online) if (b !== leader && !isBusy(b, 'follow_player')) this.order(b, 'follow_player', { name: leader.session.name, seconds: 300 }, 'following the leader')
      return
    }

    this.shareFood(online)

    const interval = this.cfg.intervalMs ?? 90000
    if (this.lastPlan && Date.now() - this.lastPlan < interval) return
    this.lastPlan = Date.now()
    await this.plan(leader, online)
  }

  /** A hurt member with nothing to eat can't heal; whoever carries the most food hands some over. */
  shareFood (online) {
    for (const hungry of online) {
      if (hungry.body.health >= 12 || hungry.body.bestFood()) continue
      const name = hungry.session.name
      if (online.some(b => b.agent?.task?.name === 'give' && b.agent.task.meta?.args?.name === name)) continue
      const giver = online
        .filter(b => b !== hungry && !isBusy(b, 'give') && b.body.bestFood())
        .sort((a, b) => b.body.bestFood().count - a.body.bestFood().count)[0]
      if (!giver) continue
      const food = giver.body.bestFood()
      this.order(giver, 'give', { item: food.item, count: Math.min(4, food.count), name }, `${name} is hurt and has no food`)
    }
  }

  async plan (leader, online) {
    const state = online.map(b => summary(b, b === leader))
    let plan
    try {
      const ai = { ...leader.ai(), ...(this.cfg.leaderModel ? { model: this.cfg.leaderModel } : {}) }
      if (ai.enabled === false) throw new Error('AI off')
      // Members who logged off (low health, kicked...) are listed so the leader stops planning around them.
      const away = this.members().filter(b => !online.includes(b)).map(b => `${b.session?.name ?? b.cfg.name}: ${b.offlineReason ?? b.state}`)
      plan = await chatJSON(ai, SYSTEM, JSON.stringify({ team: state, offline: away, base: leader.memory.data.stash ?? null, recent: this.feed.slice(-6).map(f => f.text) }), SCHEMA, { temperature: 0.3 })
    } catch (e) {
      this.log('leader AI unavailable, using rules:', e.message)
      plan = rulePlan(online, leader)
    }
    this.note(`plan: ${plan.thought}`)
    for (const b of online) {
      b.memory.data.teamDirective = plan.directive || null
      b.memory.save()
    }
    for (const o of plan.orders ?? []) {
      const bot = online.find(b => b.session.name.toLowerCase() === String(o.bot).toLowerCase())
      if (!bot || !SKILLS[o.action]) continue
      const target = String(o.args?.name ?? '').toLowerCase()
      if (target && this.members().some(m => !online.includes(m) && m.session?.name?.toLowerCase() === target)) continue
      if (['follow_player', 'come_to', 'give'].includes(o.action) && (bot === leader ? (!target || target === bot.session.name.toLowerCase()) : target === bot.session.name.toLowerCase())) continue
      // Re-planning every 90 s mustn't restart a job that's still running (a hunt or a mine takes minutes).
      if (isBusy(bot, o.action)) continue
      if (o.action === 'give' && !bot.body.count(String(o.args?.item ?? ''))) continue
      this.order(bot, o.action, o.args ?? {}, o.why ?? '')
      if (bot === leader && MOVING.has(o.action)) {
        for (const f of online) if (f !== leader) this.order(f, 'follow_player', { name: leader.session.name, seconds: 300 }, 'following the leader')
      }
    }
  }

  /**
   * A member's own brain chose something. Travelling is the leader's call: followers stay with the team,
   * and the leader doesn't wander off while the others are still on their way.
   */
  filterOwnDecision (bot, decision) {
    if (decision.source === 'owner' || decision.source === 'team') return decision
    const leader = this.leader()
    // Nobody follows or walks to themselves (the leader's brain sometimes tries).
    const target = String(decision.args?.name ?? '').toLowerCase()
    if (['follow_player', 'come_to'].includes(decision.action) && target === String(bot.session?.name).toLowerCase()) {
      return { ...decision, action: 'wait', args: { seconds: 20 }, thought: 'already here' }
    }
    if (!MOVING.has(decision.action)) return decision
    if (bot === leader) {
      return this.phase === 'gathering' ? { ...decision, action: 'wait', args: { seconds: 30 }, thought: 'waiting for the team to arrive' } : decision
    }
    if (!leader?.agent?.inGame()) return decision
    return { ...decision, action: 'follow_player', args: { name: leader.session.name, seconds: 120 }, thought: 'staying with the team' }
  }

  /** Where a member is, from its own client (works when they can't see each other). */
  locate (name) {
    const b = this.members().find(m => m.session?.name?.toLowerCase() === String(name).toLowerCase())
    if (b?.state !== 'online' || !b.body?.inWorld) return null
    const p = b.body.pos
    return { x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z), dimension: b.body.dimension }
  }

  /** Give a member a job, unless a staff member's order is running or queued for it. */
  order (bot, action, args, why) {
    const agent = bot.agent
    if (!agent) return
    if (agent.task?.meta?.source === 'owner' || (agent.order && agent.order.source !== 'team')) return
    if (['come_to', 'follow_player', 'give'].includes(action) && !args.name) args.name = this.leader().session.name
    agent.setOrder({ action, args, source: 'team', reply: r => this.log(`${bot.session.name}: ${r}`) })
    this.note(`${bot.session.name} -> ${action}${why ? ` (${why})` : ''}`)
  }

  status () {
    return { id: this.id, name: this.cfg.name, leader: this.cfg.leader, members: this.cfg.members, phase: this.phase, feed: this.feed.slice(-15) }
  }
}

const dist = (a, b) => Math.hypot(a.x - b.x, a.z - b.z)
const isBusy = (bot, action) => bot.agent?.task?.name === action || bot.agent?.order?.action === action

function summary (b, isLeader) {
  const body = b.body
  const inv = body.inventory()
  const p = body.pos
  return {
    name: b.session.name,
    leader: isLeader,
    pos: { x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) },
    dimension: body.dimension,
    health: Math.round(body.health),
    food: body.food,
    doing: b.agent.task?.name ?? 'nothing',
    tools: { pickaxe: body.bestTier('pickaxe'), sword: body.bestTier('sword') },
    gear: gearScore(body),
    keyItems: Object.fromEntries(Object.entries(inv).filter(([k]) => /log|planks|cobble|iron|diamond|coal|beef|pork|bread|chicken|mutton|bed|obsidian|torch/.test(k)).slice(0, 12)),
    nearbyThreats: body.entities().filter(e => e.dist < 20 && (e.type === 'player' ? !b.agent.ctx.allies().includes(String(e.name).toLowerCase()) : /zombie|skeleton|creeper|spider|witch|pillager/.test(e.type))).map(e => e.name ?? e.type).slice(0, 5)
  }
}

// Without the AI: simple roles by position in the member list.
function rulePlan (online, leader) {
  const roles = ['gather_wood', 'hunt', 'mine', 'follow_player', 'strip_mine']
  const orders = online.filter(b => b !== leader).map((b, i) => {
    const role = roles[i % roles.length]
    const args = role === 'gather_wood' ? { count: 16 } : role === 'hunt' ? { count: 3 } : role === 'mine' ? { block: 'stone', count: 24 } : role === 'strip_mine' ? { minutes: 6 } : { name: leader.session.name, seconds: 300 }
    return { bot: b.session.name, action: role, args, why: 'rule-based role' }
  })
  return { thought: 'splitting the basic jobs', directive: 'stay near the team and stock up on wood, food and stone', orders }
}
