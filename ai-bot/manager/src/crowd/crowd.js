// A crowd: many lightweight bots on an offline-mode server, run in a child process (worker.js) so a crash or a
// busy moment there can't stall the AI bots. It sits under a leader (one of the AI bots) in the hierarchy:
// team leader -> AI bots -> crowd. The leader's AI tells the crowd to follow, wander, gather or stop; staff can
// too, from the Bots page.
import { fork } from 'node:child_process'
import path from 'node:path'
import { EventEmitter } from 'node:events'
import { fileURLToPath } from 'node:url'
import { chatJSON } from '../llm.js'
import { label } from '../agent/personality.js'

const WORKER = path.join(path.dirname(fileURLToPath(import.meta.url)), 'worker.js')

export class Crowd extends EventEmitter {
  constructor ({ spec, manager, log }) {
    super()
    this.spec = spec
    this.manager = manager
    this.log = (...a) => log(`[crowd ${spec.id}]`, ...a)
    this.child = null
    this.last = null // the worker's latest status
    this.feed = []   // recent crowd events (orders, refusals) for the Bots page
  }

  note (ev) {
    if (ev.type !== 'crowd') return
    this.feed.push({ t: new Date().toISOString(), text: ev.text })
    if (this.feed.length > 20) this.feed.shift()
  }

  get id () { return this.spec.id }
  get running () { return !!this.child }

  start () {
    if (this.child) return
    this.last = null
    this.child = fork(WORKER, [], { stdio: ['ignore', 'inherit', 'inherit', 'ipc'] })
    this.child.on('message', msg => this.onMessage(msg))
    this.child.on('exit', code => {
      if (code) this.log(`stopped unexpectedly (${code})`)
      this.child = null
      clearInterval(this.timer)
      this.unclaim()
      const ev = { type: 'crowd', crowd: this.id, text: code ? 'the crowd process stopped unexpectedly' : 'stopped' }
      this.note(ev)
      this.emit('event', ev)
    })
    this.send({ type: 'spec', spec: this.spec })
    this.timer = setInterval(() => this.tick(), 2000)
    this.log(`starting ${Object.keys(this.spec.members).length} bots on ${this.spec.server}`)
  }

  async stop () {
    const child = this.child
    if (!child) return
    this.send({ type: 'stop' })
    await new Promise(resolve => {
      const t = setTimeout(() => { try { child.kill('SIGKILL') } catch {} resolve() }, 5000)
      child.once('exit', () => { clearTimeout(t); resolve() })
    })
  }

  send (msg) {
    try { this.child?.send(msg) } catch {}
  }

  update (spec) {
    this.spec = spec
    this.send({ type: 'spec', spec })
  }

  /**
   * follow | wander | gather | settle | stop. "at" is where to gather or found the settlement (defaults to the
   * leader's position).
   */
  order (mode, at, by) {
    if (!['follow', 'wander', 'gather', 'settle', 'stop'].includes(mode)) throw new Error('crowd orders: follow, wander, gather, settle, stop')
    if (!this.child) throw new Error(`the crowd ${this.spec.name} isn't running`)
    if (mode === 'settle' && !at && !this.leaderPos() && !this.spec.settle) throw new Error('settling needs a place: a leader in the game, or coordinates')
    this.spec.mode = mode
    if (mode === 'gather') this.spec.gather = at ?? this.leaderPos() ?? this.spec.gather ?? null
    // A new camp means new plots; settling again at the same camp keeps them.
    if (mode === 'settle') {
      const camp = at ?? this.spec.settle ?? this.leaderPos()
      if (this.spec.settle && Math.hypot(camp.x - this.spec.settle.x, camp.z - this.spec.settle.z) > 16) for (const m of Object.values(this.spec.members)) delete m.plot
      this.spec.settle = { x: Math.round(camp.x), y: Math.round(camp.y), z: Math.round(camp.z) }
    }
    this.send({ type: 'order', mode, at: mode === 'settle' ? this.spec.settle : this.spec.gather, by })
    return `told ${this.spec.name} to ${mode}`
  }

  setMember (name, personality) {
    this.send({ type: 'member', name, personality })
  }

  // ------------------------------------------------------------ the leader

  leaderBot () {
    return this.spec.leader ? this.manager.bots.get(this.spec.leader) : null
  }

  leaderPos () {
    const b = this.leaderBot()
    const p = b?.body?.inWorld ? b.body.pos : null
    return p ? { x: p.x, y: p.y, z: p.z } : null
  }

  /** Every 2 s: tell the crowd where its leader is, and make sure the leader knows it leads us. */
  tick () {
    const b = this.leaderBot()
    const pos = this.leaderPos()
    const sameServer = b && String(b.cfg.server).toLowerCase() === String(this.spec.server).toLowerCase()
    this.send({ type: 'leader', leader: pos && sameServer ? { name: b.session?.name ?? b.cfg.name, ...pos } : null })
    if (b) {
      b.crowds ??= []
      if (!b.crowds.includes(this)) b.crowds.push(this)
    }
  }

  unclaim () {
    const b = this.leaderBot()
    if (b?.crowds) b.crowds = b.crowds.filter(c => c !== this)
  }

  /** For the leader's AI prompt. */
  describe () {
    const online = (this.last?.members ?? []).filter(m => m.state === 'online')
    const kinds = [...new Set(online.map(m => m.personality?.preset).filter(Boolean))]
    const st = this.status()
    return `a crowd of ${online.length} ("${this.spec.name}"${kinds.length ? `: ${kinds.join(', ')} types` : ''}), currently told to ${this.last?.mode ?? this.spec.mode}` +
      (this.spec.settle ? `; their settlement at ${this.spec.settle.x} ${this.spec.settle.z} has ${st.huts} finished huts` : '')
  }

  // ------------------------------------------------------------ the worker

  async onMessage (msg) {
    if (msg.type === 'status') this.last = msg
    else if (msg.type === 'plot') {
      if (this.spec.members[msg.name]) this.spec.members[msg.name].plot = msg.plot
      this.manager.save()
    }
    else if (msg.type === 'event') {
      this.note(msg.event)
      this.emit('event', msg.event)
    }
    else if (msg.type === 'llm') {
      try {
        const result = await chatJSON(this.ai(), msg.system, msg.user, msg.schema, { temperature: 0.8 })
        this.send({ type: 'llmResult', id: msg.id, result })
      } catch (e) {
        this.send({ type: 'llmResult', id: msg.id, error: e.message })
      }
    }
  }

  /** The crowd's AI: this host's Ollama, or a staff member's computer through the website. */
  ai () {
    const ai = { ...this.spec.ai, ollamaUrl: this.manager.cfg.ollamaUrl, timeoutMs: 60e3 }
    if (ai.source === 'connector') ai.relay = (request, timeoutMs) => this.manager.relayLlm(ai.connector, request, timeoutMs)
    return ai
  }

  status () {
    const members = this.last?.members ?? Object.keys(this.spec.members).map(name => ({ name, state: 'stopped', personality: this.spec.members[name].personality }))
    return {
      id: this.id,
      name: this.spec.name,
      server: this.spec.server,
      leader: this.spec.leader ?? null,
      state: this.child ? 'running' : 'stopped',
      mode: this.last?.mode ?? this.spec.mode,
      ai: this.spec.ai?.enabled ? this.spec.ai.model : null,
      online: members.filter(m => m.state === 'online').length,
      settle: this.spec.settle ?? null,
      huts: members.filter(m => m.hut && m.hut.size && m.hut.done >= m.hut.size).length,
      feed: this.feed.slice(-5),
      members: members.map(m => ({ ...m, label: label(m.personality) }))
    }
  }
}
