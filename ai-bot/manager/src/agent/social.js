import { chatJSON } from '../llm.js'
import { promptText, mayRefuse, line } from './personality.js'
import { SKILLS, ARGS_SCHEMA } from './skills.js'
import { HOSTILE, RANGED, gearScore } from './combat.js'
import { FOODS } from './body.js'

// Private messages as formatted by vanilla and common server plugins. Lines starting with "<"
// are public chat, so a player can't fake these by typing them.
const WHISPER_PATTERNS = [
  /^(\w{3,16}) whispers(?: to you)?: (.+)$/i,
  /^\[(\w{3,16}) -> (?:me|you)\] (.+)$/i,
  /^from (\w{3,16}): (.+)$/i,
  /^(\w{3,16}) -> (?:me|you): (.+)$/i
]
const PUBLIC_RE = /^<(\w{3,16})> (.+)$/

const TRUSTED = 40 // at or above: no longer run from them
const HOSTILE_TRUST = -50 // at or below: flee on sight, never talk
const TALK_CAP = 70 // talking alone can't make a stranger fully trusted
const PASSIVE_CAP = 30 // trust earned just by being near us without attacking

// Natural ways of asking what the bot carries; these get the real inventory, not a guess from the AI.
const INV_RE = /\b(inv|inventory|invent|items|bags?|pockets|loot)\b|what (do|have) you (got|have|carry)|what('?s| is) on you|what are you (carrying|holding)|what('?ve| have) you got/
const asksInventory = t => INV_RE.test(t) && !/\b(drop|give|throw|clear|empty|put|stash|store)\b/.test(t)
const HAVE_RE = /^(?:do you (?:have|got)|you got|got any|have you got|how many|how much) (?:any )?([a-z][a-z _]{1,30}?)(?: do you (?:have|got))?$/

const HELP = 'commands: status, inv, come [x z], follow, stop, auto, goto x z, give <item> [n], build <file> [x y z], rejoin, or just tell me what to do'

const ownerSystem = (me, personality) => `${promptText(personality)}
You are ${me}, a Minecraft survival bot. Your owner or a staff member just sent you a
message. Decide what it means:
- kind "action": a one-off job you should do now, using one of the actions below.
- kind "directive": standing guidance that should shape everything you do from now on (put it in "directive").
- kind "answer": a question or chat; just reply.
Always include a short reply in "reply" (under 150 chars, casual).
If the Context has a "goal", that is your permanent goal from staff: keep to it, and never claim chat can change it
(only the website can). A "directive" from chat is extra guidance on top of it.
Answer questions only from the Context (status, what you're doing, inventory). Never make up items, places or
plans that aren't there; if the Context doesn't say, say you're not sure.
Actions:
${Object.entries(SKILLS).map(([k, v]) => `- ${k} ${v.args}: ${v.desc}`).join('\n')}`

const OWNER_SCHEMA = {
  type: 'object',
  properties: {
    reply: { type: 'string', maxLength: 180 },
    kind: { type: 'string', enum: ['action', 'directive', 'answer'] },
    action: { type: 'string', enum: [...Object.keys(SKILLS), 'none'] },
    args: ARGS_SCHEMA,
    directive: { type: 'string', maxLength: 200 }
  },
  required: ['reply', 'kind', 'action', 'args', 'directive']
}

const strangerSystem = (me, personality) => `${promptText(personality)}
You are ${me}, a player on a Minecraft server where other players may be dangerous
(on anarchy servers anyone may kill you). Another player is talking to you or has come close. Your goals: stay alive,
come across as a friendly, harmless, broke newbie who is not worth killing, calm things down, and judge whether this
player can be trusted.
Hard rules:
- NEVER reveal coordinates, directions, bases, stashes, what you carry, where you are going, or who you know.
- Never agree to meet up, teleport (/tpa), follow them, or hand over items. Never type commands.
- Write like a real casual player: short (under 80 chars), mostly lowercase, no emoji spam.
- Leave "reply" empty when silence is wiser: they weren't really addressing you, you already answered,
  or they are hostile. You do not need to answer every message.
Judge them: intent "friendly" | "neutral" | "suspicious" | "hostile" (threats, demands, kill talk, scams),
trustDelta from -30 (clearly dangerous) to 10 (genuinely friendly).
Reply with JSON {"reply": string, "intent": string, "trustDelta": number}.`

const STRANGER_SCHEMA = {
  type: 'object',
  properties: {
    reply: { type: 'string', maxLength: 180 },
    intent: { type: 'string', enum: ['friendly', 'neutral', 'suspicious', 'hostile'] },
    trustDelta: { type: 'integer' }
  },
  required: ['reply', 'intent', 'trustDelta']
}

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v))

// Everything the bot hears and says. It only ever looks at: whispers and website messages from the owner
// and staff, messages that name the bot, and messages from players close by. All other chat is dropped unread.
export class Social {
  constructor (agent) {
    this.agent = agent
    this.body = agent.body
    this.cfg = agent.cfg.social
    this.friends = new Set(agent.cfg.behavior.friendlyPlayers.map(n => n.toLowerCase()))
    this.inbox = []
    this.busy = false
    this.seen = new Map() // dedupe "user|text" -> time
    this.sent = [] // timestamps of our messages
    this.lastPublic = 0
    this.lastTexts = new Map() // text -> time, avoid repeating ourselves
    this.talk = new Map() // username -> last few lines of conversation
    this.greeted = new Map()
    this.memory = agent.memory
    this.memory.data.players ??= {}
    agent.social = this

    const b = this.body.b
    b.on('chat', m => this.onLine(m.text, m.sender))
    b.on('system', m => this.onLine(m.text, null))
    this.timer = setInterval(() => this.proximityTick(), 2000)
  }

  stop () { clearInterval(this.timer) }

  get me () { return this.body.name ?? this.agent.cfg.account.username }

  // ------------------------------------------------------------ relations

  isOwner (name) { return String(name).toLowerCase() === String(this.cfg.owner ?? '').toLowerCase() }
  isStaff (name) { return (this.cfg.staff ?? []).some(n => n.toLowerCase() === String(name).toLowerCase()) }

  record (name) {
    const key = name.toLowerCase()
    const players = this.memory.data.players
    players[key] ??= { name, trust: 0, passive: 0, hurtUs: 0, firstSeen: new Date().toISOString() }
    return players[key]
  }

  relation (name) {
    if (!name) return 'unknown'
    if (this.isOwner(name)) return 'owner'
    if (this.isStaff(name)) return 'staff'
    const key = name.toLowerCase()
    if (this.agent.instance.team?.names().some(n => n.toLowerCase() === key)) return 'team'
    if (this.friends.has(key)) return 'friend'
    const p = this.memory.data.players[key]
    if (!p) return 'unknown'
    if (p.trust <= HOSTILE_TRUST) return 'hostile'
    if (p.trust >= TRUSTED) return 'trusted'
    return 'unknown'
  }

  markHostile (name, why) {
    if (['owner', 'staff', 'friend', 'team'].includes(this.relation(name))) return
    const p = this.record(name)
    if (p.trust > HOSTILE_TRUST) this.memory.event(`${name} is now HOSTILE: ${why}`)
    p.trust = -100
    p.hurtUs++
    this.memory.save()
  }

  // Took damage: blame a nearby player if no mob can explain it.
  onHurt () {
    const { body } = this
    const p = body.p
    if (!p || p.inLava || p.onFire) return
    const mobs = body.entities().some(e => HOSTILE.has(e.type) && e.dist < (RANGED.has(e.type) ? 20 : 5))
    if (mobs) return
    const near = body.players().filter(e => e.name !== this.me && e.dist < 8).sort((a, b) => a.dist - b.dist)[0]
    if (near) this.markHostile(near.name, 'attacked us')
  }

  playerDistance (name) {
    const e = this.body.players().find(x => x.name?.toLowerCase() === String(name).toLowerCase())
    return e ? e.dist : Infinity
  }

  proximityTick () {
    if (!this.body.inWorld || this.agent.queue.state !== 'game') return
    for (const e of this.body.players()) {
      if (!e.name || e.name === this.me) continue
      const rel = this.relation(e.name)
      if (rel !== 'unknown' && rel !== 'trusted') continue
      if (e.dist > this.cfg.nearbyRadius) continue
      // Slowly trust people who hang around without attacking.
      const r = this.record(e.name)
      if (r.passive < PASSIVE_CAP && Math.random() < 2 / 30) { r.passive++; r.trust++ }
      // Might say something when a stranger first comes close.
      // On anarchy servers saying hi just tells a killer you're there.
      if (this.cfg.greetStrangers && this.agent.cfg.behavior.mode !== 'anarchy' && rel === 'unknown' && e.dist < 16 &&
          Date.now() - (this.greeted.get(e.name) ?? 0) > 15 * 60e3) {
        this.greeted.set(e.name, Date.now())
        this.enqueue({ kind: 'stranger', user: e.name, text: null, channel: 'whisper' })
      }
    }
  }

  // ------------------------------------------------------------ input

  mentionsMe (text) {
    const t = text.toLowerCase()
    return [this.me, ...(this.cfg.aliases ?? [])].some(n => n && t.includes(n.toLowerCase()))
  }

  onLine (text, sender) {
    if (!text) return
    for (const re of WHISPER_PATTERNS) {
      const m = re.exec(text)
      if (m) return this.onMessage(m[1], m[2], 'whisper')
    }
    const m = PUBLIC_RE.exec(text)
    if (m) return this.onMessage(m[1], m[2], 'public')
    if (sender) this.onMessage(sender, text, 'public')
  }

  onMessage (user, text, channel) {
    if (!user || user === this.me) return
    const key = `${user}|${text}`
    if (Date.now() - (this.seen.get(key) ?? 0) < 3000) return
    this.seen.set(key, Date.now())
    if (this.seen.size > 200) this.seen.clear()
    this.agent.emit('chat', { from: user, text, channel })

    const rel = this.relation(user)
    if (rel === 'owner' || rel === 'staff') {
      if (channel === 'whisper' || this.mentionsMe(text)) {
        this.enqueue({ kind: 'owner', user, text, reply: r => this.say(r, user, true) })
      }
      return
    }
    if (rel === 'hostile' || rel === 'team') return
    const nearby = this.playerDistance(user) <= this.cfg.nearbyRadius
    // Strangers' whispers only count when they're actually next to us.
    if (channel === 'whisper' ? !nearby : !(nearby || this.mentionsMe(text))) return
    this.enqueue({ kind: 'stranger', user, text, channel })
  }

  /** A staff member typed in the website's Bot tab. The reply goes back to the website. */
  fromWebsite (staffName, text, reply) {
    this.enqueue({ kind: 'owner', user: staffName, text, reply, website: true })
  }

  enqueue (ev) {
    if (ev.kind === 'owner') this.inbox.unshift(ev)
    else {
      this.inbox = this.inbox.filter(e => !(e.kind === 'stranger' && e.user === ev.user)) // keep only their latest
      this.inbox.push(ev)
      if (this.inbox.length > 5) this.inbox.splice(this.inbox.findIndex(e => e.kind === 'stranger'), 1)
    }
    this.pump()
  }

  async pump () {
    if (this.busy) return
    this.busy = true
    try {
      while (this.inbox.length) {
        const ev = this.inbox.shift()
        try {
          if (ev.kind === 'owner') await this.handleOwner(ev)
          else await this.handleStranger(ev)
        } catch (e) {
          this.agent.log(`[social] error: ${e.message}`)
          if (ev.kind === 'owner') ev.reply(/timeout|abort/i.test(e.message) ? "sorry, my head's busy right now, ask me again in a bit" : `something went wrong: ${e.message}`.slice(0, 150))
        }
      }
    } finally { this.busy = false }
  }

  // ------------------------------------------------------------ owner / staff

  async handleOwner ({ user, text: raw, reply, website }) {
    let text = raw.trim()
    const secret = this.cfg.ownerSecret
    if (secret && !website) {
      if (!text.startsWith(secret)) return this.agent.log('[social] ignored owner message without secret')
      text = text.slice(secret.length).trim()
    }
    this.agent.log(`[owner] ${user}: ${text}`)
    const agent = this.agent
    const t = text.toLowerCase().replace(/[!?.]+$/, '')
    let m

    if (/^(help|commands|\?)$/.test(t)) return reply(HELP)
    if (/^(status|where|where are you|pos|coords|hp|wya)$/.test(t)) return reply(this.statusLine())
    if (asksInventory(t)) return reply(this.inventoryLine())
    if ((m = HAVE_RE.exec(t)) && !/^(any|anything|something|stuff|items)$/.test(m[1])) return reply(this.haveLine(m[1]))
    if (/^(what('?s| is) (your|the) goal|your goal|goal)$/.test(t)) {
      const g = agent.cfg.goal
      return reply(g ? `my goal: ${g.text}${agent.lastDecision?.goalStep ? ` (right now: ${agent.lastDecision.goalStep})` : ''}` : 'no goal set. staff can set one on the website (Bots page, Goal)')
    }
    if (/^(what are you doing|what're you doing|wyd|what you doing|whats? (is )?(your|the) plan|what are you up to)$/.test(t)) return reply(this.doingLine())
    if (/^(stop|cancel|halt|stay|wait|hold)$/.test(t)) {
      agent.ownerStop()
      return reply('ok, stopped. waiting for orders ("auto" to go back to normal)')
    }
    if (/^(auto|resume|continue|carry on|go)$/.test(t)) {
      agent.setAutonomous(true)
      return reply('ok, back to doing my own thing')
    }
    if (/^(forget|clear) (orders|directive)$/.test(t)) {
      this.memory.data.ownerDirective = null
      this.memory.save()
      return reply('standing orders cleared')
    }
    if (/^rejoin$/.test(t)) {
      agent.instance.rejoin('asked by ' + user)
      return reply('rejoining')
    }
    if ((m = /^(?:come|come here|here|tp|to me)(?: (-?\d+) (-?\d+)(?: (-?\d+))?)?$/.exec(t))) {
      const args = { name: user }
      if (m[1] != null) Object.assign(args, m[3] != null ? { x: +m[1], y: +m[2], z: +m[3] } : { x: +m[1], z: +m[2] })
      return this.order('come_to', args, 'on my way', reply)
    }
    if (/^follow( me)?$/.test(t)) return this.order('follow_player', { name: user, seconds: 600 }, 'following you', reply)
    if ((m = /^goto (-?\d+) (-?\d+)(?: (-?\d+))?$/.exec(t))) {
      const args = m[3] != null ? { x: +m[1], y: +m[2], z: +m[3] } : { x: +m[1], z: +m[2] }
      return this.order('goto', args, `heading to ${m.slice(1).filter(Boolean).join(' ')}`, reply)
    }
    if ((m = /^(?:give|drop)(?: me)? ([a-z_]+)(?: (\d+))?$/.exec(t))) {
      return this.order('give', { item: m[1], count: m[2] ? +m[2] : 64, name: user }, `bringing ${m[1]}`, reply)
    }
    if ((m = /^build (\S+\.litematic)(?: (-?\d+) (-?\d+) (-?\d+))?$/.exec(t))) {
      const args = { file: m[1] }
      if (m[2] != null) Object.assign(args, { x: +m[2], y: +m[3], z: +m[4] })
      return this.order('build', args, `building ${m[1]}`, reply)
    }

    if (!agent.cfg.ai.enabled) return reply(`didn't get that. ${HELP}`)
    const context = {
      from: user,
      distanceToThem: Number.isFinite(this.playerDistance(user)) ? Math.round(this.playerDistance(user)) : 'not visible',
      status: this.statusLine(),
      doing: this.doingLine(),
      goal: agent.cfg.goal?.text ?? null,
      inventory: this.inventoryLine(),
      schematics: agent.schematics?.() ?? [],
      currentDirective: this.memory.data.ownerDirective ?? null
    }
    const r = await chatJSON(agent.ai(), ownerSystem(this.me, agent.cfg.personality), `${user} says: ${JSON.stringify(text)}\nContext: ${JSON.stringify(context)}`, OWNER_SCHEMA, { temperature: 0.2 })
    agent.log(`[owner] interpreted as ${r.kind} ${r.action ?? ''} ${JSON.stringify(r.args ?? {})} ${r.directive ?? ''}`)
    if (r.kind === 'directive' && r.directive) {
      this.memory.data.ownerDirective = String(r.directive).slice(0, 300)
      this.memory.event(`directive from ${user}: ${this.memory.data.ownerDirective}`)
      agent.setAutonomous(true)
      agent.interruptBrain()
    }
    if (r.kind === 'action' && SKILLS[r.action]) {
      const args = r.args ?? {}
      if (['come_to', 'follow_player', 'give'].includes(r.action)) args.name ??= user
      // The model copies coordinates from the status line (its own position); only trust ones the person typed.
      if (!/-?\d+\s+-?\d+/.test(text)) for (const k of ['x', 'y', 'z']) delete args[k]
      return this.order(r.action, args, r.reply || 'ok', reply)
    }
    reply(r.reply || 'ok')
  }

  order (action, args, ack, reply) {
    // Free will: a bot that dislikes this says no the first time; asked again within 3 minutes, it gives in.
    const p = this.agent.cfg.personality
    if (mayRefuse(p, action)) {
      const what = action.replace(/_/g, ' ')
      const insisted = this.refused?.action === action && Date.now() - this.refused.at < 3 * 60e3
      if (!insisted) {
        this.refused = { action, at: Date.now() }
        this.agent.log(`[personality] refused ${action} (dislikes it)`)
        return reply(`${line(p, 'refuse', what)} (ask again if you really mean it)`)
      }
      this.refused = null
      ack = line(p, 'grudging', what)
    }
    this.agent.setOrder({ action, args, reply })
    if (this.agent.queue.state !== 'game') {
      return reply(`not in the game right now${this.agent.queue.position ? ` (queue #${this.agent.queue.position})` : ''}, will do it once I'm in`)
    }
    reply(ack)
  }

  statusLine () {
    const q = this.agent.queue
    const body = this.body
    if (q.state === 'offline') return this.agent.instance.offlineReason ? `offline: ${this.agent.instance.offlineReason}` : 'offline'
    if (q.state !== 'game' || !body.inWorld) return `in queue${q.position ? ` #${q.position}` : ''}${q.eta() ? `, ~${Math.round(q.eta() / 60000)} min left` : ''}`
    const p = body.pos
    const task = this.agent.task?.name ?? (this.agent.autonomous ? 'thinking' : 'idle (stopped)')
    return `${Math.round(p.x)} ${Math.round(p.y)} ${Math.round(p.z)} ${body.dimension} (${Math.round(Math.hypot(p.x, p.z))} from spawn) hp ${Math.round(body.health)} food ${body.food} gear ${gearScore(body)} doing: ${task}`
  }

  inventoryLine () {
    if (!this.body.inWorld) return `can't check, not in a world right now (${this.statusLine()})`
    const parts = Object.entries(this.body.inventory()).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k.replace(/_/g, ' ')} ${v}`)
    const eq = this.body.equipment()
    const worn = ['head', 'chest', 'legs', 'feet', 'offhand'].map(k => eq[k]?.item).filter(Boolean).map(i => i.replace(/_/g, ' '))
    return `${parts.length ? parts.join(', ') : 'nothing in my bags'}${worn.length ? `. wearing: ${worn.join(', ')}` : ''}`
  }

  /** "do you have diamonds" / "how many logs": count anything whose id contains the word. */
  haveLine (what) {
    if (!this.body.inWorld) return this.inventoryLine()
    const word = what.replace(/^(any|some|a|an)\s+/, '').replace(/\s+/g, '_').replace(/(es|s)$/, '')
    const hits = Object.entries(this.body.inventory()).filter(([k]) => word === 'food' ? k in FOODS : k.includes(word))
    if (!hits.length) return `no ${what}`
    return hits.map(([k, v]) => `${k.replace(/_/g, ' ')} ${v}`).join(', ')
  }

  doingLine () {
    const agent = this.agent
    const task = agent.task?.name
    const why = agent.lastDecision?.thought
    const escape = agent.cfg.behavior.mode === 'anarchy' ? agent.instance.escapeStatus?.() : null
    if (!this.body.inWorld) return this.statusLine()
    const what = task ? task.replace(/_/g, ' ') : agent.autonomous ? 'deciding what to do next' : 'waiting for orders'
    return `${what}${why && task ? ` (${why})` : ''}${escape && !/out of spawn/.test(escape) ? `, anarchy: ${escape}` : ''}`.slice(0, 200)
  }

  // ------------------------------------------------------------ strangers

  async handleStranger (ev) {
    const { user, text } = ev
    if (!this.agent.cfg.ai.enabled || this.relation(user) === 'hostile') return
    const ent = this.body.players().find(e => e.name === user)
    const p = this.record(user)
    const convo = this.talk.get(user) ?? []
    if (text) convo.push(`${user}: ${text}`)
    const context = {
      player: user,
      theirMessage: text ?? '(no message: they just walked up to you)',
      channel: ev.channel,
      distance: ent ? Math.round(ent.dist) : 'far away',
      theirVisibleGear: [ent?.held, ...(ent?.armor ?? [])].filter(Boolean),
      yourHealth: Math.round(this.body.health),
      trustSoFar: p.trust,
      recentConversation: convo.slice(-6)
    }
    const r = await chatJSON(this.agent.ai(), strangerSystem(this.me, this.agent.cfg.personality), JSON.stringify(context), STRANGER_SCHEMA, { temperature: 0.7 })
    const delta = clamp(Math.round(Number(r.trustDelta) || 0), -30, 10)
    p.trust = r.intent === 'hostile' ? Math.min(p.trust + delta, HOSTILE_TRUST) : clamp(p.trust + delta, -100, Math.max(TALK_CAP, p.trust))
    p.lastIntent = r.intent
    this.memory.save()
    this.agent.log(`[social] ${user} (${ev.channel}${text ? `: ${text.slice(0, 80)}` : ', approached'}) -> ${r.intent}, trust ${p.trust}${r.reply ? `, replying "${r.reply}"` : ''}`)
    if (p.trust <= HOSTILE_TRUST) this.memory.event(`${user} judged hostile from chat`)
    const reply = this.cleanForStranger(r.reply)
    // Public chat is global: only answer publicly when they named us from far away; otherwise whisper.
    const publicReply = ev.channel === 'public' && !ent
    if (reply && this.say(reply, publicReply ? null : user)) convo.push(`you: ${reply}`)
    this.talk.set(user, convo.slice(-8))
  }

  // Never leak numbers that could be coordinates, the owner's or staff names, or anything that runs as a command.
  cleanForStranger (text) {
    const t = this.clean(text)
    if (!t) return ''
    if (/-?\d{3,}/.test(t)) return ''
    const lower = t.toLowerCase()
    if ([this.cfg.owner, ...(this.cfg.staff ?? [])].some(n => n && lower.includes(n.toLowerCase()))) return ''
    return t.slice(0, 100)
  }

  clean (text) {
    return String(text ?? '').replace(/[\r\n]+/g, ' ').replace(/^[\s/!.#$%>]+/, '').replace(/\s+/g, ' ').trim().slice(0, 200)
  }

  // ------------------------------------------------------------ output

  say (text, to = null, important = false) {
    text = this.clean(text)
    if (!text) return false
    const now = Date.now()
    this.sent = this.sent.filter(t => now - t < 60000)
    if (!important && this.sent.length >= this.cfg.maxMessagesPerMinute) return false
    if (!to && now - this.lastPublic < this.cfg.publicCooldownMs) return false
    const k = `${to ?? '*'}|${text}`
    if (!important && now - (this.lastTexts.get(k) ?? 0) < 5 * 60e3) return false
    this.lastTexts.set(k, now)
    this.sent.push(now)
    if (!to) this.lastPublic = now
    const send = to
      ? Array.from({ length: Math.ceil(text.length / 180) }, (_, i) => `/${this.cfg.whisperCommand} ${to} ${text.slice(i * 180, i * 180 + 180)}`)
      : [text]
    for (const line of send) this.body.chat(line).catch(e => this.agent.log(`[social] send failed: ${e.message}`))
    this.agent.log(`[say${to ? ` -> ${to}` : ' public'}] ${text}`)
    this.agent.emit('said', { to, text })
    return true
  }
}
