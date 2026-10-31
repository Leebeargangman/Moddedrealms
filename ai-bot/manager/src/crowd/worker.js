// A crowd of lightweight bots, run in its own process by crowd.js. Each bot is a mineflayer client (about
// 40 MB, no game window) that joins an offline-mode server under its own name, follows the crowd's leader,
// wanders or gathers, and acts out its personality: what it likes, what it won't do (free will), and how it
// talks. Messages with the manager:
//   in:  {type: 'spec', spec} {type: 'leader', leader} {type: 'order', mode, at, by} {type: 'member', name, personality}
//        {type: 'llmResult', id, result|error} {type: 'stop'}
//   out: {type: 'status', ...} {type: 'event', event} {type: 'llm', id, system, user, schema}
import mineflayer from 'mineflayer'
import pathfinderPkg from 'mineflayer-pathfinder'
import { normalize, line, mayRefuse, dislikes, likes, promptText } from '../agent/personality.js'
import { settleStep, settlerJob } from './settle.js'

const { pathfinder, Movements, goals } = pathfinderPkg
const ACTIVITY = { follow: 'follow', wander: 'explore', gather: 'follow', stop: 'stay', settle: 'build' }
const JOKES = [
  "why did the creeper cross the road? to get to the other ssssside",
  "I'd tell you a joke about gravel, but it'd fall flat",
  'my hut has no door. very open plan, very modern',
  "I asked a tree for some wood. it said I'd have to punch it first",
  'what do you call a sheep that knows karate? lamb chop!'
]
// One bot joins at a time. Servers throttle logins from one address (Bukkit's connection-throttle: 4 s), so
// joining faster gets bots kicked with "Connection throttled".
const JOIN_GAP_MS = 4500
const SAY_GAP_MS = 8000      // per bot
const REFUSE_FOR_MS = 2 * 60e3

let spec = null
let leader = null            // {name, x, y, z} of the leader bot, from the manager
let mode = 'follow'
let gatherAt = null
let settleAt = null          // the camp, while settling
let stopping = false
const members = new Map()    // name -> Member
const llmWaiting = new Map() // id -> {resolve, reject}
let llmBusy = 0
let nextAmbient = Date.now() + 30e3
const recentChat = []        // last lines of real players' chat, for the AI

const send = msg => { try { process.send(msg) } catch {} }

// All joins (first ones and reconnects) go through one queue, JOIN_GAP_MS apart.
const joinQueue = []
let joining = false
function queueJoin (m, delayMs = 0) {
  setTimeout(() => {
    if (stopping || joinQueue.includes(m)) return
    joinQueue.push(m)
    pumpJoins()
  }, delayMs)
}
async function pumpJoins () {
  if (joining) return
  joining = true
  while (joinQueue.length && !stopping) {
    joinQueue.shift().connect()
    await new Promise(resolve => setTimeout(resolve, JOIN_GAP_MS))
  }
  joining = false
}
const event = (text, extra = {}) => send({ type: 'event', event: { type: 'crowd', crowd: spec?.id, text, ...extra } })
const rand = (a, b) => a + Math.random() * (b - a)
const pick = list => list[Math.floor(Math.random() * list.length)]

function ask (system, user, schema) {
  const id = Math.random().toString(36).slice(2)
  llmBusy++
  send({ type: 'llm', id, system, user, schema })
  return new Promise((resolve, reject) => {
    llmWaiting.set(id, { resolve, reject })
    setTimeout(() => { if (llmWaiting.delete(id)) reject(new Error('the AI took too long')) }, 90e3)
  }).finally(() => { llmBusy-- })
}

const SAY_SCHEMA = { type: 'object', properties: { say: { type: 'string', maxLength: 120 } }, required: ['say'] }

class Member {
  constructor (name) {
    this.name = name
    this.state = 'waiting'
    this.doing = 'waiting to join'
    this.refusedUntil = 0
    this.ownMode = null       // the mode it stuck with after refusing an order
    this.lastSaid = 0
    this.fails = 0
    this.home = null
    this.errand = null       // a settling errand in progress (a promise)
  }

  get plot () { return spec.members?.[this.name]?.plot ?? null }
  set plot (p) { if (spec.members?.[this.name]) spec.members[this.name].plot = p }

  get personality () { return spec.members?.[this.name]?.personality ?? null }

  connect () {
    if (stopping) return
    const [host, port] = String(spec.server).split(':')
    this.state = 'connecting'
    this.doing = 'joining'
    const bot = mineflayer.createBot({
      host, port: Number(port) || 25565, username: this.name, auth: 'offline',
      // "short" (8 chunks): with less, the pathfinder can't see far enough to plan a route to the leader.
      viewDistance: 'short', checkTimeoutInterval: 60e3, hideErrors: true, logErrors: false
    })
    this.bot = bot
    bot.loadPlugin(pathfinder)
    bot.once('spawn', () => {
      this.state = 'online'
      this.fails = 0
      this.home = bot.entity.position.clone()
      const moves = new Movements(bot)
      // They walk around the world rather than change it, except that, like a player, they dig steps out of a
      // hole in soft ground. (Not leaves: weighing a way through every tree canopy made planning crawl; a
      // settler stuck on a treetop digs itself down instead.)
      const soft = new Set(['dirt', 'grass_block', 'coarse_dirt', 'podzol', 'sand', 'red_sand', 'gravel', 'rooted_dirt', 'mud'])
      moves.canDig = true
      moves.blocksCantBreak = new Set(Object.values(bot.registry.blocksByName).filter(b => !soft.has(b.name)).map(b => b.id))
      moves.allow1by1towers = false
      moves.scafoldingBlocks = []
      bot.pathfinder.setMovements(moves)
      bot.pathfinder.thinkTimeout = 5000 // forests take a while to plan through
      // Planning time per game tick, shared out across the crowd (one process runs them all).
      bot.pathfinder.tickTimeout = Math.max(8, Math.floor(40 / Math.max(1, members.size)))
      this.timer = setInterval(() => this.think(), rand(2500, 4500))
    })
    bot.on('chat', (from, text) => this.heard(from, text))
    // The pathfinder cuts corners and catches on tree trunks, then replans the same way; on a treetop or in a
    // hole it can't plan at all. Step sideways and hop, and after a few tries walk off blindly for a moment.
    bot.on('path_reset', reason => {
      if (reason !== 'stuck' || this.escaping) return
      const pos = bot.entity.position
      this.stuckCount = this.stuckAt && pos.distanceTo(this.stuckAt) < 1 ? (this.stuckCount ?? 0) + 1 : 1
      this.stuckAt = pos.clone()
      // A settler on an errand only sidesteps: walking off blindly would cancel the errand's walk.
      if (this.stuckCount < 3 || this.errand) {
        const side = Math.random() < 0.5 ? 'left' : 'right'
        bot.setControlState(side, true)
        bot.setControlState('jump', true)
        setTimeout(() => { bot.setControlState(side, false); bot.setControlState('jump', false) }, 500)
        return
      }
      this.escaping = true
      this.stuckCount = 0
      bot.pathfinder.setGoal(null)
      bot.look(Math.random() * Math.PI * 2, 0, true).catch(() => {})
      bot.setControlState('forward', true)
      bot.setControlState('jump', true)
      setTimeout(() => {
        bot.clearControlStates()
        this.escaping = false
      }, 3000)
    })
    bot.on('kicked', reason => { this.lastKick = typeof reason === 'string' ? reason : JSON.stringify(reason).slice(0, 200) })
    bot.on('error', e => { this.lastError = e.message })
    bot.on('end', () => {
      clearInterval(this.timer)
      this.bot = null
      if (stopping) { this.state = 'stopped'; return }
      this.state = 'offline'
      // Throttled for joining too soon after another bot: not a real failure, just wait for a turn.
      if (/throttled/i.test(this.lastKick ?? '')) {
        this.lastKick = null
        this.doing = 'waiting for a turn to join'
        queueJoin(this, 2000)
        return
      }
      this.fails++
      this.doing = this.lastKick ? `kicked: ${this.lastKick.slice(0, 80)}` : this.lastError ? `disconnected: ${this.lastError.slice(0, 80)}` : 'disconnected'
      this.lastKick = this.lastError = null
      // Back off, and give up after a few tries (the server may be full, whitelisted or online-mode).
      if (this.fails <= 5) queueJoin(this, Math.min(15e3 * 2 ** (this.fails - 1), 4 * 60e3))
      else this.doing += ' (gave up; restart the crowd to try again)'
    })
  }

  quit () {
    clearInterval(this.timer)
    try { this.bot?.quit() } catch {}
  }

  /** The mode it's following: the crowd's, unless it refused that and is doing its own thing. */
  mode () {
    return Date.now() < this.refusedUntil && this.ownMode ? this.ownMode : mode
  }

  think () {
    const bot = this.bot
    if (!bot?.entity || this.escaping) return
    const p = this.personality
    const pos = bot.entity.position
    const m = this.mode()
    // How far it strays depends on who it is: nervous ones stay close, explorers roam.
    const range = likes(p, 'explore') ? 14 : likes(p, 'follow') || likes(p, 'stay') ? 3 : 7
    const goal = target => {
      if (!bot.pathfinder.isMoving() || Math.random() < 0.15) bot.pathfinder.setGoal(target)
    }
    if (m === 'settle' && settleAt) {
      settleStep(this, SETTLE_CTX)
      return
    }
    if (m === 'stop') {
      bot.pathfinder.setGoal(null)
      this.doing = 'standing around'
      if (Math.random() < 0.2) bot.look(rand(0, Math.PI * 2), rand(-0.3, 0.3), false).catch(() => {})
      return
    }
    if (m === 'gather' && gatherAt) {
      const d = Math.hypot(pos.x - gatherAt.x, pos.z - gatherAt.z)
      this.doing = d > range + 2 ? `going to ${Math.round(gatherAt.x)} ${Math.round(gatherAt.z)}` : 'waiting at the meeting point'
      if (d > range + 2) goal(new goals.GoalNearXZ(gatherAt.x + rand(-range, range), gatherAt.z + rand(-range, range), 2))
      return
    }
    if (m === 'follow' && leader) {
      const d = Math.hypot(pos.x - leader.x, pos.z - leader.z)
      if (d > 120) {
        this.doing = `too far from ${leader.name} to follow`
        return
      }
      this.doing = `following ${leader.name}`
      // An explorer drifts off now and then even when following.
      if (likes(p, 'explore') && Math.random() < 0.1) {
        goal(new goals.GoalNearXZ(pos.x + rand(-20, 20), pos.z + rand(-20, 20), 2))
        this.doing = `wandering off (bored of following ${leader.name})`
        return
      }
      if (d > range + 3) goal(new goals.GoalNearXZ(leader.x + rand(-range, range), leader.z + rand(-range, range), 2))
      return
    }
    // Wander (or follow with no leader in the world): around home, further for explorers.
    const centre = (leader && m === 'follow') ? leader : this.home ?? pos
    const r = likes(p, 'explore') ? 40 : dislikes(p, 'explore') ? 8 : 20
    this.doing = 'wandering'
    if (!bot.pathfinder.isMoving()) goal(new goals.GoalNearXZ(centre.x + rand(-r, r), centre.z + rand(-r, r), 2))
  }

  /** Someone said something; answer if it was to us (by name). */
  heard (from, text) {
    if (!from || members.has(from)) return
    recentChat.push(`${from}: ${text}`)
    if (recentChat.length > 8) recentChat.shift()
    if (!new RegExp(`\\b${this.name}\\b`, 'i').test(text)) return
    if (Date.now() - this.lastSaid < SAY_GAP_MS) return
    const p = this.personality
    if (spec.ai?.enabled && p?.enabled !== false && llmBusy < 2) {
      const system = `${promptText(p)}\nYou are ${this.name}, one of a crowd of players on a Minecraft server${leader ? `, following ${leader.name}` : ''}. Someone talked to you. Answer in one short, casual line (under 100 characters), in character. Reply with JSON {"say": "..."}.`
      ask(system, `You are ${this.doing}.\nRecent chat:\n${recentChat.join('\n')}\n${from} just said to you: "${text}"`, SAY_SCHEMA)
        .then(r => this.say(r.say)).catch(() => {})
    } else this.say(line(p, 'happy'))
  }

  say (text) {
    const t = String(text ?? '').replace(/[\r\n§]/g, ' ').trim().slice(0, 120)
    if (!t || !this.bot) return
    this.lastSaid = Date.now()
    this.bot.chat(t)
    send({ type: 'event', event: { type: 'said', bot: `${spec.id}:${this.name}`, text: t, crowd: spec.id, member: this.name } })
  }

  status () {
    const pos = this.bot?.entity?.position
    const p = this.personality
    return {
      name: this.name,
      state: this.state,
      doing: Date.now() < this.refusedUntil ? `${this.doing} (refused to ${this.refusedWhat})` : this.doing,
      personality: p ? { preset: p.preset, text: p.text, enabled: p.enabled !== false, freeWill: !!p.freeWill, likes: p.likes, dislikes: p.dislikes } : null,
      job: settleAt ? settlerJob(p) : null,
      hut: this.plot ? { done: (this.hutSize ?? 0) - (this.hutLeft ?? this.hutSize ?? 0), size: this.hutSize ?? null } : null,
      position: pos ? { x: Math.round(pos.x), y: Math.round(pos.y), z: Math.round(pos.z) } : null
    }
  }
}

/**
 * A new order for the whole crowd. Each bot reacts in character: happy if it likes it, grumbling if it doesn't,
 * and with free will a bot may refuse something it dislikes and keep doing what it was doing for a while.
 * Only a few of them say something, so a big crowd doesn't flood the chat.
 */
function order (newMode, at, by) {
  const before = mode
  mode = newMode
  if (newMode === 'gather') gatherAt = at ?? leader ?? gatherAt
  if (newMode === 'settle') settleAt = at ?? leader ?? settleAt
  const activity = ACTIVITY[newMode]
  const refused = []
  let speakers = 0
  for (const m of members.values()) {
    if (m.state !== 'online') continue
    const p = m.personality
    let kind = null
    if (mayRefuse(p, newMode === 'stop' ? 'stay' : activity) && Math.random() < 0.5) {
      m.ownMode = before === newMode ? 'wander' : before
      m.refusedUntil = Date.now() + REFUSE_FOR_MS
      m.refusedWhat = newMode
      refused.push(m.name)
      kind = 'refuse'
    } else {
      m.refusedUntil = 0
      kind = dislikes(p, activity) ? 'grudging' : likes(p, activity) ? 'happy' : null
    }
    if (kind && p?.enabled !== false && speakers < 4 && Math.random() < (kind === 'refuse' ? 0.9 : 0.35)) {
      speakers++
      setTimeout(() => m.say(line(p, kind, activity)), rand(300, 2500))
    }
    // Wander around where they are now, not back where they first joined.
    if (newMode === 'wander' && m.bot?.entity) m.home = m.bot.entity.position.clone()
    m.bot?.pathfinder.setGoal(null)
    m.think()
  }
  const online = [...members.values()].filter(m => m.state === 'online').length
  event(`${by ? `${by}: ` : ''}${newMode}${newMode === 'gather' && gatherAt ? ` at ${Math.round(gatherAt.x)} ${Math.round(gatherAt.z)}` : ''}. ` +
    `${online - refused.length} of ${online} went along` + (refused.length ? `; ${refused.join(', ')} refused` : ''))
}

/** Now and then someone in the crowd says something in character (needs an AI model and a listener nearby). */
function ambient () {
  if (!spec.ai?.enabled || Date.now() < nextAmbient || llmBusy > 0) return
  nextAmbient = Date.now() + Math.max(20, spec.chatEverySeconds ?? 60) * 1000 * rand(0.7, 1.3)
  const online = [...members.values()].filter(m => m.state === 'online' && m.personality?.enabled !== false && m.personality)
  if (!online.length) return
  const m = pick(online)
  const system = `${promptText(m.personality)}\nYou are ${m.name}, one of a crowd of players on a Minecraft server${leader ? ` who follow ${leader.name}` : ''}. Say one short, casual thing in chat (under 100 characters), in character: a remark, a joke, a complaint or a question to the others. Reply with JSON {"say": "..."}.`
  ask(system, `You are ${m.doing}.${recentChat.length ? `\nRecent chat:\n${recentChat.join('\n')}` : ''}`, SAY_SCHEMA)
    .then(r => m.say(r.say)).catch(() => {})
}

// What settle.js needs from the crowd.
const SETTLE_CTX = {
  centre: () => settleAt,
  index: m => [...members.keys()].indexOf(m.name),
  takenPlots: m => [...members.values()].filter(o => o !== m && o.plot).map(o => o.plot),
  savePlot: m => send({ type: 'plot', name: m.name, plot: m.plot }),
  say: (m, text) => { if (text && m.personality?.enabled !== false) m.say(text) },
  joke: m => { if (!spec.ai?.enabled && Date.now() - m.lastSaid > 60e3) m.say(pick(JOKES)) },
  get ai () { return !!spec.ai?.enabled }
}

function status () {
  send({ type: 'status', mode, gatherAt, settleAt, leader: leader?.name ?? null, members: [...members.values()].map(m => m.status()) })
}

async function start () {
  mode = spec.mode ?? 'follow'
  gatherAt = spec.gather ?? null
  settleAt = spec.settle ?? null
  const names = Object.keys(spec.members ?? {})
  for (const name of names) members.set(name, new Member(name))
  setInterval(status, 2000)
  setInterval(ambient, 5000)
  for (const m of members.values()) queueJoin(m)
}

process.on('message', msg => {
  switch (msg.type) {
    case 'spec': {
      const first = !spec
      spec = msg.spec
      if (first) start()
      break
    }
    case 'leader': leader = msg.leader; break
    case 'order': order(msg.mode, msg.at, msg.by); break
    case 'member': {
      if (spec.members?.[msg.name]) spec.members[msg.name].personality = normalize(msg.personality)
      break
    }
    case 'llmResult': {
      const w = llmWaiting.get(msg.id)
      if (!w) break
      llmWaiting.delete(msg.id)
      if (msg.error) w.reject(new Error(msg.error))
      else w.resolve(msg.result)
      break
    }
    case 'stop': {
      stopping = true
      for (const m of members.values()) m.quit()
      setTimeout(() => process.exit(0), 1500)
      break
    }
  }
})
process.on('disconnect', () => process.exit(0))
