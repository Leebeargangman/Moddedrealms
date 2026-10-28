import fs from 'node:fs'
import path from 'node:path'
import { ROOT } from './config.js'
import { log, notify } from './log.js'
import { setupMovements, updateScaffolding } from './nav.js'
import { Reflexes, PRI } from './reflexes.js'
import { Brain } from './brain.js'
import { SKILLS } from './skills.js'
import { observe } from './state.js'
import { sleep, withTimeout, isAbort } from './util.js'

const STATUS_FILE = path.join(ROOT, 'data', 'status.json')

// Owns one bot connection's behaviour: a single running task at a time, reflexes can pre-empt it.
export class Agent {
  constructor ({ bot, cfg, memory, queue }) {
    this.bot = bot
    this.cfg = cfg
    this.memory = memory
    this.queue = queue
    this.task = null
    this.history = []
    this.pendingResume = null
    this.lastDecision = null
    this.running = false
    this.panicked = false
    this.order = null // pending owner order {action, args}
  }

  get autonomous () { return this.memory.data.autonomous !== false }

  // Owner gave a one-off job: drop whatever the brain was doing and run it next.
  setOrder ({ action, args }) {
    this.order = { action, args: args ?? {}, at: Date.now() }
    this.pendingResume = null
    this.interruptBrain()
  }

  ownerStop () {
    this.order = null
    this.pendingResume = null
    this.memory.data.autonomous = false
    this.memory.save()
    if (this.task && this.task.priority <= PRI.owner) this.task.controller.abort(new Error('stopped by owner'))
  }

  setAutonomous (on) {
    this.memory.data.autonomous = on
    this.memory.save()
  }

  interruptBrain () {
    if (this.task && this.task.priority <= PRI.owner) this.task.controller.abort(new Error('interrupted by owner'))
  }

  inGame () {
    return this.running && this.queue.state === 'game' && this.bot.entity && this.bot.health > 0
  }

  start () {
    if (this.running) return
    this.running = true
    this.movements = setupMovements(this.bot)
    this.reflexes = new Reflexes(this)
    this.brain = new Brain(this)
    this.reflexTimer = setInterval(() => this.reflexTick(), 250)
    this.gen = (this.gen ?? 0) + 1
    this.brainLoop(this.gen).catch(e => log('[agent] brain loop crashed:', e.stack))
    log('[agent] started')
  }

  stop () {
    this.running = false
    clearInterval(this.reflexTimer)
    this.task?.controller.abort(new Error('agent stopped'))
  }

  async reflexTick () {
    if (!this.inGame() || this.reflexBusy) return
    this.reflexBusy = true
    try { await this.reflexes.tick() } catch (e) { log('[reflex] error:', e.message) } finally { this.reflexBusy = false }
  }

  // Start a task if its priority beats the current one. Resolves with {ok, result}.
  async runTask (name, priority, fn, { timeoutMs = 60000, meta = null } = {}) {
    const prev = this.task
    if (prev) {
      if (prev.priority >= priority) return { ok: false, result: 'busy' }
      if (prev.meta) this.pendingResume = { ...prev.meta, at: Date.now() }
      prev.controller.abort(new Error(`interrupted by ${name}`))
      await withTimeout(prev.done, 2000).catch(() => {})
    }
    const controller = new AbortController()
    const task = { name, priority, controller, meta, started: Date.now() }
    const timer = setTimeout(() => controller.abort(new Error(`${name} timed out`)), timeoutMs)
    this.task = task
    task.done = (async () => {
      try {
        return { ok: true, result: String(await fn(controller.signal) ?? 'done') }
      } catch (e) {
        return { ok: false, result: isAbort(e) ? `aborted (${e.message})` : e.message }
      } finally {
        clearTimeout(timer)
        if (this.task === task) {
          this.task = null
          try { this.bot.pathfinder.setGoal(null); this.bot.clearControlStates() } catch {}
        }
      }
    })()
    const res = await task.done
    if (priority > PRI.brain) log(`[reflex] ${name}: ${res.result}`)
    return res
  }

  async brainLoop (gen) {
    while (this.running && gen === this.gen) {
      await sleep(500)
      if (!this.inGame() || this.task || this.thinking) continue

      let decision
      const r = this.pendingResume
      if (this.order) {
        decision = { ...this.order, thought: 'owner order', source: 'owner', resumes: 0 }
        this.order = null
        log(`[brain] (owner) ${decision.action} ${JSON.stringify(decision.args)}`)
      } else if (!this.autonomous) {
        continue
      } else if (r && r.resumes < 2 && Date.now() - r.at < 120000) {
        this.pendingResume = null
        decision = { ...r, resumes: r.resumes + 1 }
      } else {
        this.pendingResume = null
        this.thinking = true
        const t0 = Date.now()
        try {
          decision = await this.brain.decide(observe(this), this.history)
        } catch (e) {
          log('[brain] decide failed:', e.message)
          continue
        } finally { this.thinking = false }
        decision.resumes = 0
        log(`[brain] (${decision.source}, ${Date.now() - t0}ms) ${decision.action} ${JSON.stringify(decision.args)} - ${decision.thought}`)
      }
      if (!this.inGame() || this.task) continue

      const skill = SKILLS[decision.action]
      this.lastDecision = decision
      updateScaffolding(this.bot, this.movements)
      const ctx = { bot: this.bot, cfg: this.cfg, memory: this.memory, social: this.social }
      const fromOwner = decision.source === 'owner'
      const res = await this.runTask(decision.action, fromOwner ? PRI.owner : PRI.brain, s => skill.run(ctx, decision.args ?? {}, s), {
        timeoutMs: skill.maxMs,
        meta: { action: decision.action, args: decision.args, thought: decision.thought, resumes: decision.resumes, source: decision.source }
      })
      if (res.result === 'busy') continue
      if (fromOwner && !/interrupted by (?!owner)/.test(res.result)) this.social?.reportOrder(decision.action, res)
      if (/(interrupted|stopped) by owner/.test(res.result)) continue
      // Interrupted by a reflex: it will be resumed, don't record as a failure.
      if (!res.ok && /interrupted by/.test(res.result)) continue
      this.history.push({ action: decision.action, args: decision.args, ok: res.ok, result: res.result.slice(0, 200) })
      if (this.history.length > 20) this.history.shift()
      log(`[task] ${decision.action} -> ${res.ok ? 'ok' : 'FAILED'}: ${res.result}`)
      this.memory.event(`${decision.action} ${res.ok ? 'ok' : 'failed'}: ${res.result.slice(0, 120)}`)
    }
  }

  writeStatus () {
    const bot = this.bot
    const p = bot.entity?.position
    const status = {
      updated: new Date().toISOString(),
      username: bot.username,
      state: this.queue.state,
      queuePosition: this.queue.position,
      queueEtaMin: this.queue.eta() != null ? Math.round(this.queue.eta() / 60000) : null,
      position: p ? { x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) } : null,
      distFromSpawn: p ? Math.round(Math.hypot(p.x, p.z)) : null,
      health: bot.health,
      food: bot.food,
      dimension: bot.game?.dimension ?? null,
      // Quick "why am I stuck" view: what we stand on and the ground around us.
      surroundings: p ? {
        below: [1, 2, 3, 10, 30].map(d => `${d}:${bot.blockAt(p.offset(0, -d, 0))?.name ?? 'unloaded'}`).join(' '),
        around: [[3, 0], [-3, 0], [0, 3], [0, -3]].map(([dx, dz]) => bot.blockAt(p.offset(dx, -1, dz))?.name ?? 'unloaded').join(' '),
        moving: bot.pathfinder?.isMoving?.() ?? null,
        onGround: bot.entity?.onGround ?? null
      } : null,
      task: this.task?.name ?? null,
      lastDecision: this.lastDecision && { action: this.lastDecision.action, args: this.lastDecision.args, thought: this.lastDecision.thought, source: this.lastDecision.source },
      history: this.history.slice(-5),
      memory: { stash: this.memory.data.stash, spawnBed: this.memory.data.spawnBed, stats: this.memory.data.stats, notes: this.memory.data.notes.slice(-5) }
    }
    try {
      fs.writeFileSync(STATUS_FILE + '.tmp', JSON.stringify(status, null, 2))
      fs.renameSync(STATUS_FILE + '.tmp', STATUS_FILE)
    } catch {}
  }

  onDeath () {
    const m = this.memory.data
    m.stats.deaths++
    const pos = this.bot.entity?.position
    const where = pos ? `${Math.round(pos.x)} ${Math.round(pos.y)} ${Math.round(pos.z)}` : 'unknown'
    m.deaths.push({ t: new Date().toISOString(), where, cause: this.lastDeathMsg ?? null })
    if (m.deaths.length > 30) m.deaths.shift()
    // Died on the way out without a bed spawn: try a different bearing next time.
    if (m.travel && !m.spawnBed) m.travel.angle += (Math.random() < 0.5 ? -1 : 1) * (0.5 + Math.random() * 0.5)
    this.memory.event(`DIED at ${where}${this.lastDeathMsg ? ` (${this.lastDeathMsg})` : ''}`)
    this.history = []
    this.pendingResume = null
    notify(`☠️ ${this.bot.username} died at ${where}. ${this.lastDeathMsg ?? ''} Total deaths: ${m.stats.deaths}`)
  }
}
