// One bot: a real Minecraft client process, the bridge to it, and the AI agent driving it.
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { EventEmitter } from 'node:events'
import { ROOT } from '../config.js'
import { log as baseLog } from '../log.js'
import { Memory } from '../memory.js'
import { launchClient } from '../client/launcher.js'
import { detectServerVersion } from '../client/serverVersion.js'
import { Bridge } from '../client/bridge.js'
import { Body } from '../agent/body.js'
import { QueueTracker } from '../agent/queue.js'
import { Agent } from '../agent/agent.js'
import { session, getAccount } from './accounts.js'
import { normalizeLitematic } from '../build/litematic.js'

// A child killed by a signal has exitCode null and signalCode set, so check both.
const alive = child => !!child && child.exitCode == null && child.signalCode == null
const killClient = child => { try { process.kill(-child.pid, 'SIGKILL') } catch {} }
const exited = (child, ms) => new Promise(resolve => {
  if (!alive(child)) return resolve()
  child.once('exit', resolve)
  setTimeout(resolve, ms)
})

const TOKEN_MAX_AGE = 20 * 3600e3 // Minecraft access tokens last ~24h; relaunch before they expire.
// The server couldn't verify our login with Mojang: the token is dead even if it hasn't expired.
const BAD_SESSION = /invalid session|failed to verify username|session (has )?expired|not authenticated with minecraft/i

/**
 * States: stopped -> starting -> online (queue/game come from the agent) -> offline (auto log-off,
 * kicked, waiting to rejoin) -> stopped. Emits 'status' and every agent event with the bot id.
 */
export class BotInstance extends EventEmitter {
  constructor ({ cfg, bridgePort, relay }) {
    super()
    this.cfg = cfg
    this.id = cfg.id
    this.dir = path.join(ROOT, 'bots', this.id)
    this.gameDir = path.join(this.dir, 'game')
    fs.mkdirSync(path.join(this.gameDir, 'schematics'), { recursive: true })
    this.memory = new Memory(path.join(this.dir, 'memory.json'))
    this.bridgePort = bridgePort
    this.relay = relay
    this.state = 'stopped'
    this.offlineReason = null
    this.backoff = 15000
    this.log = (...a) => baseLog(`[${this.id}]`, ...a)
  }

  // ------------------------------------------------------------ lifecycle

  async start () {
    if (this.state !== 'stopped') return
    this.wanted = true
    this.setState('starting')
    try {
      await this.launch()
    } catch (e) {
      this.log('start failed:', e.message)
      this.emit('event', { type: 'error', text: e.message })
      this.scheduleRestart()
    }
  }

  async launch () {
    // A client we just stopped may still be shutting down: it holds the account and the bridge port, so the
    // new one can't start until it's gone.
    const old = this.stopping
    if (alive(old)) {
      await exited(old, 10000)
      if (alive(old)) { killClient(old); await exited(old, 5000) }
    }
    this.session = await session(this.cfg.account, { fresh: this.freshToken })
    this.freshTokenAt = this.freshToken ? Date.now() : this.freshTokenAt
    this.freshToken = false
    const token = crypto.randomBytes(16).toString('hex')
    const child = this.child = await launchClient({
      gameDir: this.gameDir,
      account: this.session,
      server: this.cfg.server,
      bridgePort: this.bridgePort,
      bridgeToken: token,
      heapMb: this.cfg.heapMb ?? 1400,
      logFile: path.join(this.dir, 'client.log')
    })
    child.once('exit', (code, signal) => this.onClientExit(child, code ?? signal))
    try {
      this.bridge = new Bridge({ port: this.bridgePort, token })
      await this.bridge.connect()
      if (this.child !== child || !alive(child)) throw new Error('the game client closed while starting')
    } catch (e) {
      // Never leave a half-started client running: it would hold the account and the bridge port.
      if (this.child === child) this.cleanup()
      killClient(child)
      throw e
    }
    this.body = new Body(this.bridge)
    this.queue = new QueueTracker(this.bridge)
    this.agent = new Agent({ instance: this, body: this.body, cfg: this.cfg, memory: this.memory, queue: this.queue, log: this.log })
    for (const ev of ['status', 'chat', 'said', 'task', 'build', 'death']) this.agent.on(ev, data => this.emit('event', { type: ev, ...data }))
    this.queue.on('state', (state, prev) => {
      this.log(`[queue] ${prev} -> ${state}`)
      if (state === 'game') {
        this.backoff = 15000
        this.offlineReason = null
        this.setState('online')
        this.memory.data.stats.joins++
        this.memory.save()
        this.agent.start()
      } else {
        this.agent.stop()
        if (state === 'queue') this.setState('online')
      }
    })
    this.queue.on('position', p => this.emit('event', { type: 'queue', position: p, etaMin: this.queue.eta() != null ? Math.round(this.queue.eta() / 60000) : null }))
    this.bridge.on('disconnect', m => this.onDisconnect(m.reason))
    this.bridge.on('closed', () => this.relink())
    this.log(`client up for ${this.session.name}`)
  }

  // The bridge mod serves one connection, so anything else connecting to it drops ours while the game keeps
  // running: connect again, or the bot stands in the world with nobody steering it until it's kicked.
  async relink () {
    const { bridge, child } = this
    if (!this.wanted || !bridge || !alive(child)) return
    this.log('lost the link to the game client, reconnecting')
    try {
      await bridge.connect(60000)
    } catch (e) {
      if (this.bridge !== bridge || !alive(child)) return
      this.log(`could not reconnect to the game client (${e.message}), restarting it`)
      killClient(child)
      return
    }
    if (this.bridge === bridge) this.queue.resume()
  }

  onDisconnect (reason) {
    if (!this.wanted) return
    this.agent?.stop()
    if (this.offlineReason?.startsWith('auto log-off')) return // rejoin timer already set
    this.offlineReason = `disconnected${reason ? `: ${reason}` : ''}`
    this.setState('offline')
    this.emit('event', { type: 'disconnect', reason })
    let delay = this.backoff
    this.backoff = Math.min(this.backoff * 2, 5 * 60e3)
    if (/already (connected|online)|another location|logged in from/i.test(reason ?? '')) this.backoff = Math.max(this.backoff, 60e3)
    if (BAD_SESSION.test(reason ?? '')) {
      // Rejoin with a new token. If a token we just fetched is refused too, the account is signed in somewhere
      // else (another bot host, or someone playing on it), which takes the session away from us each time.
      this.freshToken = true
      if (Date.now() - (this.freshTokenAt ?? 0) < 10 * 60e3) {
        delay = this.backoff = 5 * 60e3
        this.offlineReason = `${this.session?.name ?? 'this account'} is signed in somewhere else (another bot host, or someone playing on it): only one can use it at a time`
        this.emit('event', { type: 'error', text: this.offlineReason })
      }
    }
    // Retrying fast won't help until staff fix the whitelist/ban; keep trying every 5 minutes.
    if (/whitelist|white-listed|banned/i.test(reason ?? '')) this.backoff = 5 * 60e3
    this.log(`disconnected (${reason ?? 'no reason'}), rejoining in ${Math.round(delay / 1000)}s`)
    this.rejoinTimer = setTimeout(() => this.rejoin('reconnect'), delay)
  }

  onClientExit (child, code) {
    // An old client finishing its shutdown after a new one started: not ours to clean up after.
    if (child !== this.child) return this.log(`old client exited (${code})`)
    this.log(`client exited (${code})`)
    this.cleanup()
    if (this.wanted) {
      this.setState('starting')
      this.scheduleRestart()
    } else this.setState('stopped')
  }

  scheduleRestart () {
    clearTimeout(this.restartTimer) // a failed start and the client's exit both ask for one: start only once
    const delay = this.backoff
    this.backoff = Math.min(this.backoff * 2, 5 * 60e3)
    this.restartTimer = setTimeout(() => {
      if (!this.wanted) return
      this.state = 'stopped'
      this.start()
    }, delay)
  }

  cleanup () {
    this.agent?.stop()
    this.agent?.social.stop()
    this.queue?.stop()
    this.bridge?.close()
    this.agent = this.queue = this.bridge = this.body = null
  }

  async stop () {
    this.wanted = false
    clearTimeout(this.rejoinTimer)
    clearTimeout(this.restartTimer)
    const child = this.child
    await this.bridge?.request('quit').catch(() => {})
    this.cleanup()
    // From here its exit is not a crash to restart from; the next launch waits for it to be gone.
    this.child = null
    this.stopping = child
    if (alive(child)) {
      setTimeout(() => { if (alive(child)) killClient(child) }, 8000)
    }
    this.setState('stopped')
  }

  /** Very low health: leave the server now, come back after rejoinAfterMs or when staff say so. */
  autoLogoff (reason) {
    if (this.state !== 'online') return
    this.offlineReason = `auto log-off (${reason})`
    this.log(this.offlineReason)
    this.agent?.stop()
    this.bridge?.request('disconnect', { reason: 'brb' }).catch(() => {})
    this.setState('offline')
    this.emit('event', { type: 'logoff', reason })
    const after = this.cfg.behavior.rejoinAfterMs ?? 5 * 60e3
    clearTimeout(this.rejoinTimer)
    this.rejoinTimer = setTimeout(() => this.rejoin('auto log-off timer'), after)
  }

  async rejoin (why) {
    clearTimeout(this.rejoinTimer)
    if (!this.wanted) return this.start()
    this.log(`rejoining (${why})`)
    this.offlineReason = null
    // Old or refused token: relaunch the client so it signs in fresh.
    if (!alive(this.child) || this.freshToken || (this.session?.at && Date.now() - this.session.at > TOKEN_MAX_AGE)) {
      const wanted = this.wanted
      await this.stop()
      this.wanted = wanted
      return this.start()
    }
    this.setState('starting')
    // Check the version again: the server may have updated, or the first check got no answer.
    const version = await detectServerVersion(this.cfg.server)
    await this.bridge.request('connect', { host: this.cfg.server, version }).catch(e => this.log('connect failed:', e.message))
  }

  async switchServer (server) {
    this.cfg.server = server
    if (this.state === 'stopped') return
    const version = await detectServerVersion(server)
    await this.bridge?.request('connect', { host: server, version }).catch(e => this.log('connect failed:', e.message))
  }

  /** Anarchy mode progress for the Bots page. */
  escapeStatus () {
    const e = this.memory.data.escape
    if (!e) return 'leaving spawn'
    if (e.done) return `out of spawn, ${Math.round(Math.hypot(e.out.x, e.out.z))} blocks away`
    const p = this.body?.inWorld && this.body.dimension === 'the_nether' ? this.body.pos : null
    if (e.entered) return `in the nether, ${p ? Math.round(Math.hypot(p.x - e.entered.x, p.z - e.entered.z)) : e.netherBlocks ?? 0} blocks along`
    return 'looking for a nether portal'
  }

  setState (state) {
    if (this.state === state) return
    this.state = state
    this.emit('event', { type: 'state', state, reason: this.offlineReason })
  }

  // ------------------------------------------------------------ helpers for the agent

  /** AI settings: the VPS's Ollama, or relayed to a staff member's own computer. */
  ai () {
    const ai = { ...this.cfg.ai }
    if (ai.source === 'connector' && this.relay) ai.relay = (request, timeoutMs) => this.relay(this.cfg.ai.connector, request, timeoutMs)
    return ai
  }

  schematics () {
    try { return fs.readdirSync(path.join(this.gameDir, 'schematics')).filter(f => f.endsWith('.litematic')) } catch { return [] }
  }

  async addSchematic (name, buffer) {
    const safe = path.basename(name).replace(/[^\w.-]/g, '_')
    fs.writeFileSync(path.join(this.gameDir, 'schematics', safe), await normalizeLitematic(buffer))
    return safe
  }

  /** A staff member typed in the website's Bot tab. */
  fromWebsite (staffName, text) {
    if (!this.agent) return this.emit('event', { type: 'reply', to: staffName, text: `not in a world right now (${this.state}${this.offlineReason ? `: ${this.offlineReason}` : ''})` })
    this.agent.social.fromWebsite(staffName, text, reply => this.emit('event', { type: 'reply', to: staffName, text: reply }))
  }

  status () {
    return {
      id: this.id,
      name: this.cfg.name,
      account: this.session?.name ?? getAccount(this.cfg.account)?.name ?? null,
      server: this.cfg.server,
      state: this.state,
      offlineReason: this.offlineReason,
      model: this.cfg.ai.enabled === false ? 'rules only' : `${this.cfg.ai.model} (${this.cfg.ai.source === 'connector' ? `on ${this.cfg.ai.connector}'s PC` : 'VPS'})`,
      mode: this.cfg.behavior.mode === 'anarchy' ? 'anarchy' : 'survival',
      escape: this.cfg.behavior.mode === 'anarchy' ? this.escapeStatus() : null,
      goal: this.cfg.goal ?? null,
      personality: this.cfg.personality ?? null,
      schematics: this.schematics(),
      stats: this.memory.data.stats,
      ...(this.agent ? this.agent.status() : {})
    }
  }
}
