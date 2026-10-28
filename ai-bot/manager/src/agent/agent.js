import { EventEmitter } from 'node:events'
import { Reflexes, PRI } from './reflexes.js'
import { Brain } from './brain.js'
import { SKILLS, inSpawnArea } from './skills.js'
import { isEscaping, escapeSpawn } from './anarchy.js'
import { Social } from './social.js'
import { observe } from './state.js'
import { gearScore } from './combat.js'
import { sleep, withTimeout, isAbort } from '../util.js'

// Things that make sense right where the bot stands, even inside spawn protection.
const STAY_AT_SPAWN = new Set(['leave_spawn', 'escape_spawn', 'wait', 'eat', 'give', 'come_to', 'follow_player'])
// Run by the agent itself, not offered to the AI.
const INTERNAL = { escape_spawn: { maxMs: 6 * 60 * 60e3, run: escapeSpawn } }

/**
 * One bot's behaviour while it is in a world: a single running task at a time, which reflexes can
 * pre-empt. Emits 'status', 'chat', 'said', 'task', 'build' for the website.
 */
export class Agent extends EventEmitter {
  constructor ({ instance, body, cfg, memory, queue, log }) {
    super()
    this.instance = instance
    this.body = body
    this.cfg = cfg
    this.memory = memory
    this.queue = queue
    this.log = log
    this.task = null
    this.history = []
    this.pendingResume = null
    this.lastDecision = null
    this.order = null
    this.running = false
    this.ctx = {
      body,
      cfg,
      memory,
      log,
      gameDir: instance.gameDir,
      emit: (ev, data) => this.emit(ev, data),
      locate: name => instance.team?.locate(name) ?? null,
      team: () => instance.team ?? null,
      instance: () => instance,
      allies: () => [cfg.social.owner, ...(cfg.social.staff ?? []), ...cfg.behavior.friendlyPlayers, ...(instance.team?.names() ?? [])].filter(Boolean).map(n => n.toLowerCase())
    }
    this.social = new Social(this)
    this.ctx.social = this.social
    this.reflexes = new Reflexes(this)
    this.brain = new Brain(this)

    let lastHealth = null
    body.b.on('state', s => {
      const h = s.player?.health
      if (h != null && lastHealth != null && h < lastHealth) { this.lastHurt = Date.now(); this.social.onHurt() }
      lastHealth = h
    })
    body.b.on('death', () => this.onDeath())
    body.b.on('system', m => { if (this.body.name && m.text.includes(this.body.name) && !/whispers|->/.test(m.text)) this.lastDeathMsg = m.text.slice(0, 200) })
  }

  ai () { return this.instance.ai() }
  schematics () { return this.instance.schematics() }
  get autonomous () { return this.memory.data.autonomous !== false }
  inGame () { return this.running && this.queue.state === 'game' && this.body.inWorld && !this.body.p.dead }

  start () {
    if (this.running) return
    this.running = true
    this.gen = (this.gen ?? 0) + 1
    // Baritone defaults that suit a bot: use the whole inventory for building, don't break chests.
    for (const cmd of ['set allowInventory true', 'set allowParkour true', 'set blocksToAvoidBreaking chest,trapped_chest,barrel,shulker_box,ender_chest,crafting_table,furnace']) {
      this.body.baritone(cmd).catch(() => {})
    }
    this.reflexTimer = setInterval(() => this.reflexTick(), 250)
    this.statusTimer = setInterval(() => this.emit('status', this.status()), 2000)
    this.brainLoop(this.gen).catch(e => this.log(`[agent] brain loop crashed: ${e.stack}`))
    this.log('[agent] started')
  }

  stop () {
    this.running = false
    clearInterval(this.reflexTimer)
    clearInterval(this.statusTimer)
    this.task?.controller.abort(new Error('agent stopped'))
  }

  // ------------------------------------------------------------ owner control

  /** A job from staff (source 'owner') or the team leader (source 'team'). Staff orders win. */
  setOrder ({ action, args, reply, source = 'owner' }) {
    this.order = { action, args: args ?? {}, reply, source, at: Date.now() }
    this.pendingResume = null
    this.interruptBrain()
  }

  ownerStop () {
    this.order = null
    this.pendingResume = null
    this.memory.data.autonomous = false
    this.memory.save()
    if (this.task && this.task.priority <= PRI.owner) this.task.controller.abort(new Error('stopped by owner'))
    this.body.stop()
  }

  setAutonomous (on) {
    this.memory.data.autonomous = on
    this.memory.save()
  }

  interruptBrain () {
    if (this.task && this.task.priority <= PRI.owner) this.task.controller.abort(new Error('interrupted by owner'))
  }

  // ------------------------------------------------------------ tasks

  async reflexTick () {
    if (!this.inGame() || this.reflexBusy) return
    this.reflexBusy = true
    try { await this.reflexes.tick() } catch (e) { this.log(`[reflex] error: ${e.message}`) } finally { this.reflexBusy = false }
  }

  // Start a task if its priority beats the current one. Resolves with {ok, result}.
  async runTask (name, priority, fn, { timeoutMs = 60000, meta = null } = {}) {
    const prev = this.task
    if (prev) {
      if (prev.priority >= priority) return { ok: false, result: 'busy' }
      if (prev.meta) this.pendingResume = { ...prev.meta, at: Date.now() }
      prev.controller.abort(new Error(`interrupted by ${name}`))
      await withTimeout(prev.done, 3000).catch(() => {})
    }
    const controller = new AbortController()
    const task = { name, priority, controller, meta, started: Date.now() }
    const timer = setTimeout(() => controller.abort(new Error(`${name} timed out`)), timeoutMs)
    this.task = task
    this.emit('task', { name, started: true })
    task.done = (async () => {
      try {
        return { ok: true, result: String(await fn(controller.signal) ?? 'done') }
      } catch (e) {
        return { ok: false, result: isAbort(e) ? `aborted (${e.message})` : e.message }
      } finally {
        clearTimeout(timer)
        if (this.task === task) {
          this.task = null
          this.body.stop()
        }
      }
    })()
    const res = await task.done
    if (priority > PRI.owner) this.log(`[reflex] ${name}: ${res.result}`)
    return res
  }

  async brainLoop (gen) {
    while (this.running && gen === this.gen) {
      await sleep(500)
      if (!this.inGame() || this.task || this.thinking) continue

      let decision
      const r = this.pendingResume
      if (this.order) {
        decision = { ...this.order, thought: `${this.order.source} order`, resumes: 0 }
        this.order = null
        this.log(`[brain] (${decision.source} order) ${decision.action} ${JSON.stringify(decision.args)}`)
      } else if (r && r.resumes < 2 && Date.now() - r.at < 120000 && (this.autonomous || r.source === 'owner' || r.source === 'team')) {
        // Something interrupted the task (eating, a mob...): pick it up again. Staff orders resume even when stopped.
        this.pendingResume = null
        decision = { ...r, resumes: r.resumes + 1 }
      } else if (!this.autonomous) {
        continue
      } else if (this.escaping()) {
        this.pendingResume = null
        decision = { action: 'escape_spawn', args: {}, thought: 'anarchy mode: leaving spawn through the nether', source: 'rule', resumes: 0 }
      } else {
        this.pendingResume = null
        this.thinking = true
        const t0 = Date.now()
        try {
          decision = await this.brain.decide(await observe(this), this.history)
        } catch (e) {
          this.log(`[brain] decide failed: ${e.message}`)
          continue
        } finally { this.thinking = false }
        decision.resumes = 0
        this.log(`[brain] (${decision.source}, ${Date.now() - t0}ms) ${decision.action} ${JSON.stringify(decision.args)} - ${decision.thought}`)
      }
      if (!this.inGame() || this.task) continue
      if (decision.action !== 'escape_spawn') decision = this.instance.team?.filterOwnDecision(this.instance, decision) ?? decision

      // Nothing gets done inside spawn protection: walk out first (staff orders and quick things excepted).
      if (decision.source !== 'owner' && !STAY_AT_SPAWN.has(decision.action) && inSpawnArea(this.ctx)) {
        this.log(`[brain] in the spawn area, leaving it before ${decision.action}`)
        this.pendingResume = { ...decision, resumes: 0, at: Date.now() }
        decision = { action: 'leave_spawn', args: {}, thought: 'spawn is protected', source: decision.source, resumes: 0 }
      }
      const skill = SKILLS[decision.action] ?? INTERNAL[decision.action]
      if (!skill) continue
      this.lastDecision = decision
      const fromOwner = decision.source === 'owner' || decision.source === 'team'
      const res = await this.runTask(decision.action, fromOwner ? PRI.owner : PRI.brain, s => skill.run(this.ctx, decision.args ?? {}, s), {
        timeoutMs: skill.maxMs,
        meta: { action: decision.action, args: decision.args, thought: decision.thought, resumes: decision.resumes, source: decision.source, reply: decision.reply }
      })
      if (res.result === 'busy') continue
      if (fromOwner && !/interrupted by (?!owner)/.test(res.result)) {
        decision.reply?.(`${decision.action}: ${res.ok ? 'done' : 'failed'} - ${res.result}`.slice(0, 200))
      }
      if (/(interrupted|stopped) by owner/.test(res.result)) continue
      // Interrupted by a reflex: it will be resumed, don't record as a failure.
      if (!res.ok && /interrupted by/.test(res.result)) continue
      this.history.push({ action: decision.action, args: decision.args, ok: res.ok, result: res.result.slice(0, 200) })
      if (this.history.length > 20) this.history.shift()
      this.log(`[task] ${decision.action} -> ${res.ok ? 'ok' : 'FAILED'}: ${res.result}`)
      this.memory.event(`${decision.action} ${res.ok ? 'ok' : 'failed'}: ${res.result.slice(0, 120)}`)
      this.emit('task', { name: decision.action, ok: res.ok, result: res.result })
    }
  }

  // ------------------------------------------------------------ status

  status () {
    const b = this.body
    const p = b.p
    return {
      queue: this.queue.state,
      queuePosition: this.queue.position,
      queueEtaMin: this.queue.eta() != null ? Math.round(this.queue.eta() / 60000) : null,
      position: p ? { x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) } : null,
      dimension: p ? b.dimension : null,
      health: p?.health ?? null,
      food: p?.food ?? null,
      gameMode: p?.gameMode ?? null,
      gearScore: p ? gearScore(b) : null,
      task: this.task?.name ?? null,
      autonomous: this.autonomous,
      thought: this.lastDecision?.thought ?? null,
      baritone: b.baritoneState,
      inventory: b.inventory(),
      directive: this.memory.data.ownerDirective ?? null,
      history: this.history.slice(-5)
    }
  }

  /** Anarchy mode and not yet out of spawn: the team leaves this bot alone until it is. */
  escaping () { return isEscaping(this.cfg, this.memory) }

  onDeath () {
    const m = this.memory.data
    // Anarchy mode without a bed: we respawn at spawn, so escape again.
    if (this.cfg.behavior.mode === 'anarchy' && !m.spawnBed) m.escape = null
    m.stats.deaths++
    const pos = this.body.pos
    const where = pos ? `${Math.round(pos.x)} ${Math.round(pos.y)} ${Math.round(pos.z)}` : 'unknown'
    m.deaths.push({ t: new Date().toISOString(), where, cause: this.lastDeathMsg ?? null })
    if (m.deaths.length > 30) m.deaths.shift()
    // Died on the way out without a bed spawn: try a different bearing next time.
    if (m.travel && !m.spawnBed) m.travel.angle += (Math.random() < 0.5 ? -1 : 1) * (0.5 + Math.random() * 0.5)
    this.memory.event(`DIED at ${where}${this.lastDeathMsg ? ` (${this.lastDeathMsg})` : ''}`)
    this.history = []
    this.pendingResume = null
    this.emit('death', { where, cause: this.lastDeathMsg ?? null, deaths: m.stats.deaths })
    setTimeout(() => this.body.b.request('respawn').catch(() => {}), 1500)
  }
}
