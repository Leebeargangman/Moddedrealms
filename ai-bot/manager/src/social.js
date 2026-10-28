import { log, notify } from './log.js'
import { chatJSON } from './llm.js'
import { SKILLS } from './skills.js'
import { HOSTILE, RANGED, gearScore } from './combat.js'

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

const HELP = 'commands: status, inv, come, follow, stop, auto, goto x z, give <item> [n], or just tell me what to do'

const OWNER_SYSTEM = `You are TheDrVillager, a Minecraft survival bot on an anarchy server. Your owner just sent you a
private message. Decide what it means:
- kind "action": a one-off job you should do now, using one of the actions below.
- kind "directive": standing guidance that should shape everything you do from now on (put it in "directive").
- kind "answer": a question or chat; just reply.
Always include a short whisper back to your owner in "reply" (under 150 chars, casual).
Actions:
${Object.entries(SKILLS).map(([k, v]) => `- ${k} ${v.args}: ${v.desc}`).join('\n')}`

const OWNER_SCHEMA = {
  type: 'object',
  properties: {
    reply: { type: 'string' },
    kind: { type: 'string', enum: ['action', 'directive', 'answer'] },
    action: { type: 'string', enum: [...Object.keys(SKILLS), 'none'] },
    args: { type: 'object' },
    directive: { type: 'string' }
  },
  required: ['reply', 'kind', 'action', 'args', 'directive']
}

const STRANGER_SYSTEM = `You are TheDrVillager, a player on a lawless anarchy Minecraft server where anyone may kill you.
Another player is talking to you or has come close. Your goals: stay alive, come across as a friendly, harmless,
broke newbie who is not worth killing, calm things down, and judge whether this player can be trusted.
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
    reply: { type: 'string' },
    intent: { type: 'string', enum: ['friendly', 'neutral', 'suspicious', 'hostile'] },
    trustDelta: { type: 'integer' }
  },
  required: ['reply', 'intent', 'trustDelta']
}

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v))

// Everything the bot hears and says. It only ever looks at: whispers from the owner, messages
// that name the bot, and messages from players close by. All other chat is dropped unread.
export class Social {
  constructor (agent) {
    this.agent = agent
    this.bot = agent.bot
    this.cfg = agent.cfg.social
    this.owner = this.cfg.owner.toLowerCase()
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

    this.bot.on('messagestr', (msg, position) => this.onRaw(msg, position))
    this.bot.on('whisper', (user, msg) => this.onMessage(user, String(msg), 'whisper'))
    this.bot.on('entityHurt', e => { if (e === this.bot.entity) this.onHurt() })
    this.timer = setInterval(() => this.proximityTick(), 2000)
    this.bot.once('end', () => clearInterval(this.timer))
  }

  // ------------------------------------------------------------ relations

  record (name) {
    const key = name.toLowerCase()
    const players = this.memory.data.players
    players[key] ??= { name, trust: 0, passive: 0, hurtUs: 0, firstSeen: new Date().toISOString() }
    return players[key]
  }

  relation (name) {
    const key = name.toLowerCase()
    if (key === this.owner) return 'owner'
    if (this.friends.has(key)) return 'friend'
    const p = this.memory.data.players[key]
    if (!p) return 'unknown'
    if (p.trust <= HOSTILE_TRUST) return 'hostile'
    if (p.trust >= TRUSTED) return 'trusted'
    return 'unknown'
  }

  markHostile (name, why) {
    if (['owner', 'friend'].includes(this.relation(name))) return
    const p = this.record(name)
    if (p.trust > HOSTILE_TRUST) this.memory.event(`${name} is now HOSTILE: ${why}`)
    p.trust = -100
    p.hurtUs++
    this.memory.save()
  }

  // Took damage: blame a nearby player if no mob can explain it.
  onHurt () {
    const bot = this.bot
    const me = bot.entity.position
    const mobs = Object.values(bot.entities).some(e => HOSTILE.has(e.name) &&
      e.position.distanceTo(me) < (RANGED.has(e.name) ? 20 : 5))
    if (mobs || bot.entity.isInLava || bot.entity.onFire) return
    const near = Object.values(bot.players)
      .filter(p => p.entity && p.username !== bot.username && p.entity.position.distanceTo(me) < 8)
      .sort((a, b) => a.entity.position.distanceTo(me) - b.entity.position.distanceTo(me))[0]
    if (near) this.markHostile(near.username, 'attacked us')
  }

  playerDistance (name) {
    const ent = this.bot.players[name]?.entity
    return ent && this.bot.entity ? ent.position.distanceTo(this.bot.entity.position) : Infinity
  }

  proximityTick () {
    const bot = this.bot
    if (!bot.entity || this.agent.queue.state !== 'game') return
    for (const p of Object.values(bot.players)) {
      if (!p.entity || p.username === bot.username) continue
      const rel = this.relation(p.username)
      if (rel !== 'unknown' && rel !== 'trusted') continue
      const d = p.entity.position.distanceTo(bot.entity.position)
      if (d > this.cfg.nearbyRadius) continue
      // Slowly trust people who hang around without attacking.
      const r = this.record(p.username)
      if (r.passive < PASSIVE_CAP && Math.random() < 2 / 30) { r.passive++; r.trust++ }
      // Might say something when a stranger first comes close.
      if (this.cfg.greetStrangers && rel === 'unknown' && d < 16 &&
          Date.now() - (this.greeted.get(p.username) ?? 0) > 15 * 60e3) {
        this.greeted.set(p.username, Date.now())
        this.enqueue({ kind: 'stranger', user: p.username, text: null, channel: 'whisper' })
      }
    }
  }

  // ------------------------------------------------------------ input

  mentionsMe (text) {
    const t = text.toLowerCase()
    return [this.bot.username, ...this.cfg.aliases].some(n => n && t.includes(n.toLowerCase()))
  }

  onRaw (msg, position) {
    if (position === 'game_info') return
    for (const re of WHISPER_PATTERNS) {
      const m = re.exec(msg)
      if (m) return this.onMessage(m[1], m[2], 'whisper')
    }
    const m = PUBLIC_RE.exec(msg)
    if (m) this.onMessage(m[1], m[2], 'public')
  }

  onMessage (user, text, channel) {
    if (!user || user === this.bot.username) return
    const key = `${user}|${text}`
    if (Date.now() - (this.seen.get(key) ?? 0) < 3000) return
    this.seen.set(key, Date.now())
    if (this.seen.size > 200) this.seen.clear()

    const rel = this.relation(user)
    if (rel === 'owner') {
      if (channel === 'whisper' || this.mentionsMe(text)) this.enqueue({ kind: 'owner', user, text })
      return
    }
    if (rel === 'hostile') return
    const nearby = this.playerDistance(user) <= this.cfg.nearbyRadius
    // Strangers' whispers only count when they're actually next to us.
    if (channel === 'whisper' ? !nearby : !(nearby || this.mentionsMe(text))) return
    this.enqueue({ kind: 'stranger', user, text, channel })
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
          if (ev.kind === 'owner') await this.handleOwner(ev.text)
          else await this.handleStranger(ev)
        } catch (e) {
          log('[social] error:', e.message)
          if (ev.kind === 'owner') this.whisperOwner(`error: ${e.message}`.slice(0, 150))
        }
      }
    } finally { this.busy = false }
  }

  // ------------------------------------------------------------ owner

  async handleOwner (raw) {
    let text = raw.trim()
    const secret = this.cfg.ownerSecret
    if (secret) {
      if (!text.startsWith(secret)) return log(`[social] ignored owner message without secret`)
      text = text.slice(secret.length).trim()
    }
    log(`[owner] ${text}`)
    const agent = this.agent
    const t = text.toLowerCase().replace(/[!?.]+$/, '')
    let m

    if (/^(help|commands|\?)$/.test(t)) return this.whisperOwner(HELP)
    if (/^(status|where|where are you|pos|coords|hp|wya)$/.test(t)) return this.whisperOwner(this.statusLine())
    if (/^(inv|inventory|items)$/.test(t)) return this.whisperOwner(this.inventoryLine())
    if (/^(stop|cancel|halt|stay|wait|hold)$/.test(t)) {
      agent.ownerStop()
      return this.whisperOwner('ok, stopped. waiting for orders ("auto" to go back to normal)')
    }
    if (/^(auto|resume|continue|carry on|go)$/.test(t)) {
      agent.setAutonomous(true)
      return this.whisperOwner('ok, back to doing my own thing')
    }
    if (/^(forget|clear) (orders|directive)$/.test(t)) {
      this.memory.data.ownerDirective = null
      this.memory.save()
      return this.whisperOwner('standing orders cleared')
    }
    if (/^(come|come here|here|tp|to me)$/.test(t)) return this.order('come_to', { name: this.cfg.owner }, 'on my way')
    if (/^follow( me)?$/.test(t)) return this.order('follow_player', { name: this.cfg.owner, seconds: 600 }, 'following you')
    if ((m = /^goto (-?\d+) (-?\d+)(?: (-?\d+))?$/.exec(t))) {
      const args = m[3] != null ? { x: +m[1], y: +m[2], z: +m[3] } : { x: +m[1], z: +m[2] }
      return this.order('goto', args, `heading to ${m[1]} ${m[2]}${m[3] ? ' ' + m[3] : ''}`)
    }
    if ((m = /^(?:give|drop)(?: me)? ([a-z_]+)(?: (\d+))?$/.exec(t))) {
      return this.order('give', { item: m[1], count: m[2] ? +m[2] : 64, name: this.cfg.owner }, `bringing ${m[1]}`)
    }

    if (!agent.cfg.ai.enabled) return this.whisperOwner(`didn't get that. ${HELP}`)
    const context = {
      ownerDistance: Number.isFinite(this.playerDistance(this.cfg.owner)) ? Math.round(this.playerDistance(this.cfg.owner)) : 'not visible',
      status: this.statusLine(),
      currentDirective: this.memory.data.ownerDirective ?? null
    }
    const r = await chatJSON(agent.cfg.ai, OWNER_SYSTEM, `Owner (${this.cfg.owner}) says: ${JSON.stringify(text)}\nContext: ${JSON.stringify(context)}`, OWNER_SCHEMA, { temperature: 0.2 })
    log(`[owner] interpreted as ${r.kind} ${r.action ?? ''} ${JSON.stringify(r.args ?? {})} ${r.directive ?? ''}`)
    if (r.kind === 'directive' && r.directive) {
      this.memory.data.ownerDirective = String(r.directive).slice(0, 300)
      this.memory.event(`owner directive: ${this.memory.data.ownerDirective}`)
      agent.setAutonomous(true)
      agent.interruptBrain()
    }
    if (r.kind === 'action' && SKILLS[r.action]) return this.order(r.action, r.args ?? {}, r.reply || 'ok')
    this.whisperOwner(r.reply || 'ok')
  }

  order (action, args, reply) {
    if (this.agent.queue.state !== 'game') {
      this.agent.setOrder({ action, args })
      return this.whisperOwner(`still in queue${this.agent.queue.position ? ` (#${this.agent.queue.position})` : ''}, will do it once I'm in`)
    }
    this.agent.setOrder({ action, args })
    this.whisperOwner(reply)
  }

  statusLine () {
    const bot = this.bot
    const q = this.agent.queue
    if (q.state !== 'game') return `in queue${q.position ? ` #${q.position}` : ''}${q.eta() ? `, ~${Math.round(q.eta() / 60000)} min left` : ''}`
    const p = bot.entity.position
    const task = this.agent.task?.name ?? (this.agent.autonomous ? 'thinking' : 'idle (stopped)')
    return `${Math.round(p.x)} ${Math.round(p.y)} ${Math.round(p.z)} (${Math.round(Math.hypot(p.x, p.z))} from spawn) hp ${Math.round(bot.health)} food ${bot.food} gear ${gearScore(bot)} doing: ${task}`
  }

  inventoryLine () {
    const inv = {}
    for (const i of this.bot.inventory.items()) inv[i.name] = (inv[i.name] ?? 0) + i.count
    const parts = Object.entries(inv).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k.replace(/_/g, ' ')} ${v}`)
    return parts.length ? parts.join(', ') : 'empty'
  }

  // ------------------------------------------------------------ strangers

  async handleStranger (ev) {
    const { user, text } = ev
    if (!this.agent.cfg.ai.enabled || this.relation(user) === 'hostile') return
    const ent = this.bot.players[user]?.entity
    const p = this.record(user)
    const convo = this.talk.get(user) ?? []
    if (text) convo.push(`${user}: ${text}`)
    const gear = ent?.equipment?.filter(Boolean).map(i => i.name) ?? []
    const context = {
      player: user,
      theirMessage: text ?? '(no message: they just walked up to you)',
      channel: ev.channel,
      distance: Number.isFinite(this.playerDistance(user)) ? Math.round(this.playerDistance(user)) : 'far away',
      theirVisibleGear: gear,
      yourHealth: Math.round(this.bot.health ?? 20),
      trustSoFar: p.trust,
      recentConversation: convo.slice(-6)
    }
    const r = await chatJSON(this.agent.cfg.ai, STRANGER_SYSTEM, JSON.stringify(context), STRANGER_SCHEMA, { temperature: 0.7 })
    const delta = clamp(Math.round(Number(r.trustDelta) || 0), -30, 10)
    p.trust = r.intent === 'hostile' ? Math.min(p.trust + delta, HOSTILE_TRUST) : clamp(p.trust + delta, -100, Math.max(TALK_CAP, p.trust))
    p.lastIntent = r.intent
    this.memory.save()
    log(`[social] ${user} (${ev.channel}${text ? `: ${text.slice(0, 80)}` : ', approached'}) -> ${r.intent}, trust ${p.trust}${r.reply ? `, replying "${r.reply}"` : ''}`)
    if (p.trust <= HOSTILE_TRUST) this.memory.event(`${user} judged hostile from chat`)
    const reply = this.cleanForStranger(r.reply)
    // Public chat is global: only answer publicly when they named us from far away; otherwise whisper.
    const publicReply = ev.channel === 'public' && !Number.isFinite(this.playerDistance(user))
    if (reply && this.say(reply, publicReply ? null : user)) convo.push(`you: ${reply}`)
    this.talk.set(user, convo.slice(-8))
  }

  // Never leak numbers that could be coordinates, the owner's name, or anything that runs as a command.
  cleanForStranger (text) {
    let t = this.clean(text)
    if (!t) return ''
    if (/-?\d{3,}/.test(t)) return ''
    if (t.toLowerCase().includes(this.owner)) return ''
    return t.slice(0, 100)
  }

  clean (text) {
    return String(text ?? '').replace(/[\r\n]+/g, ' ').replace(/^[\s/!.#$%>]+/, '').replace(/\s+/g, ' ').trim().slice(0, 200)
  }

  // ------------------------------------------------------------ output

  whisperOwner (text) {
    this.say(text, this.cfg.owner, true)
  }

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
    try {
      if (to) {
        // Long owner replies go out in pieces so nothing is cut off.
        for (let i = 0; i < text.length; i += 180) this.bot.chat(`/${this.cfg.whisperCommand} ${to} ${text.slice(i, i + 180)}`)
      } else this.bot.chat(text)
      log(`[say${to ? ` -> ${to}` : ' public'}] ${text}`)
      return true
    } catch (e) {
      log('[social] send failed:', e.message)
      return false
    }
  }

  // The agent tells the owner how their order went.
  reportOrder (action, res) {
    this.whisperOwner(`${action}: ${res.ok ? 'done' : 'failed'} - ${res.result}`.slice(0, 200))
    if (!res.ok) notify(`order ${action} failed: ${res.result}`)
  }
}
