// Runs bots and teams on this machine. Can run on several machines (e.g. a VPS and a gaming PC);
// each connects to the website on its own and runs the bots assigned to it.
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { EventEmitter } from 'node:events'
import { ROOT, loadManagerConfig, botConfig } from './config.js'
import { initLog, log } from './log.js'
import { BotInstance } from './bot/instance.js'
import { Team } from './team/team.js'
import * as accounts from './bot/accounts.js'
import { availableMemMb, totalMemMb, hostInfo, modelRamMb, ensureModel, pullingModels, watchHost } from './hostinfo.js'

const DATA = path.join(ROOT, 'data')
const BOTS_FILE = path.join(DATA, 'bots.json')
const TEAMS_FILE = path.join(DATA, 'teams.json')
const BASE_PORT = 25700

const readJson = (file, fallback) => { try { return JSON.parse(fs.readFileSync(file, 'utf8')) } catch { return fallback } }
const writeJson = (file, data) => { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file + '.tmp', JSON.stringify(data, null, 2)); fs.renameSync(file + '.tmp', file) }
const slug = s => String(s).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 24) || 'bot'

export { availableMemMb }

export class Manager extends EventEmitter {
  constructor (cfg = loadManagerConfig()) {
    super()
    this.cfg = cfg
    this.specs = readJson(BOTS_FILE, []) // [{ id, name, account, server, ai, behavior, social, autoStart }]
    this.teamSpecs = readJson(TEAMS_FILE, []) // [{ id, name, leader, members, leaderModel, autoStart }]
    this.bots = new Map()
    this.teams = new Map()
    this.relay = null // set by the website link: (staffName, request, timeoutMs) => ollama response
  }

  save () {
    writeJson(BOTS_FILE, this.specs)
    writeJson(TEAMS_FILE, this.teamSpecs)
  }

  // ------------------------------------------------------------ bots

  instance (id) {
    let bot = this.bots.get(id)
    if (bot) return bot
    const spec = this.specs.find(s => s.id === id)
    if (!spec) throw new Error(`no bot ${id}`)
    const used = new Set([...this.bots.values()].map(b => b.bridgePort))
    let port = BASE_PORT
    while (used.has(port)) port++
    bot = new BotInstance({ cfg: botConfig(this.cfg, spec), bridgePort: port, relay: (...a) => this.relayLlm(...a) })
    bot.on('event', e => this.emit('event', { bot: id, ...e }))
    this.bots.set(id, bot)
    return bot
  }

  createBot (spec) {
    if (!spec.account || !accounts.getAccount(spec.account)) throw new Error('pick an account for the bot')
    const id = spec.id ?? `${slug(spec.name ?? accounts.getAccount(spec.account).name)}-${Date.now().toString(36).slice(-4)}`
    if (this.specs.some(s => s.id === id)) throw new Error(`bot ${id} already exists`)
    const clean = { id, name: spec.name ?? accounts.getAccount(spec.account).name, account: spec.account, server: spec.server ?? this.cfg.defaults.server }
    for (const k of ['ai', 'behavior', 'social', 'heapMb', 'autoStart', 'owner']) if (spec[k] != null) clean[k] = spec[k]
    this.specs.push(clean)
    this.save()
    this.emitList()
    return clean
  }

  updateBot (id, changes) {
    const spec = this.specs.find(s => s.id === id)
    if (!spec) throw new Error(`no bot ${id}`)
    for (const k of ['name', 'account', 'server', 'ai', 'heapMb', 'autoStart']) if (changes[k] !== undefined) spec[k] = changes[k]
    // The Bots page sends only the settings it shows (e.g. behavior.mode); keep the rest.
    for (const k of ['behavior', 'social']) if (changes[k]) spec[k] = { ...spec[k], ...changes[k] }
    this.save()
    const bot = this.bots.get(id)
    if (bot) {
      const server = bot.cfg.server
      const mode = bot.cfg.behavior.mode
      bot.cfg = botConfig(this.cfg, spec)
      if (bot.agent) { bot.agent.cfg = bot.cfg; bot.agent.ctx.cfg = bot.cfg }
      // Switched to anarchy mode: drop what it's doing so it heads for a portal now.
      if (bot.cfg.behavior.mode !== mode) bot.agent?.interruptBrain()
      if (changes.server && changes.server !== server) bot.switchServer(changes.server)
      const ai = bot.cfg.ai
      if (changes.ai && bot.state !== 'stopped' && ai.enabled !== false && ai.source !== 'connector' && hostInfo().ollama.up) {
        ensureModel(this.cfg.ollamaUrl, ai.model, log).then(() => this.emitList())
      }
    }
    this.emitList()
  }

  async removeBot (id) {
    await this.stopBot(id)
    this.bots.delete(id)
    this.specs = this.specs.filter(s => s.id !== id)
    for (const t of this.teamSpecs) t.members = t.members.filter(m => m !== id)
    this.save()
    this.emitList()
  }

  async startBot (id) {
    const bot = this.instance(id)
    if (bot.state !== 'stopped') return
    const running = [...this.bots.values()].filter(b => b.state !== 'stopped').length
    if (running >= this.cfg.maxBots) throw new Error(`this machine already runs ${running} bots (maxBots ${this.cfg.maxBots})`)
    const free = availableMemMb()
    const client = (bot.cfg.heapMb ?? 1400) + 500
    const model = modelRamMb(bot.cfg.ai)
    const reserve = this.reserveMemMb()
    if (free - client - model < reserve) {
      throw new Error(`not enough memory on ${this.hostName()}: ${free} MB free, this bot needs about ${client} MB` +
        (model ? ` plus ${model} MB for ${bot.cfg.ai.model}` : '') + `, and ${reserve} MB stays free for the system`)
    }
    if (bot.cfg.ai.enabled !== false && bot.cfg.ai.source !== 'connector' && hostInfo().ollama.up) {
      ensureModel(this.cfg.ollamaUrl, bot.cfg.ai.model, log).then(() => this.emitList())
    }
    await bot.start()
  }

  async stopBot (id) {
    const bot = this.bots.get(id)
    if (bot) await bot.stop()
  }

  // ------------------------------------------------------------ teams

  createTeam ({ name, leader, members, leaderModel, chat }) {
    if (!members?.length || members.length < 2) throw new Error('a team needs at least 2 bots')
    if (!members.includes(leader)) throw new Error('the leader must be one of the members')
    for (const m of members) if (!this.specs.some(s => s.id === m)) throw new Error(`no bot ${m}`)
    const spec = { id: `${slug(name)}-${Date.now().toString(36).slice(-4)}`, name, leader, members, leaderModel: leaderModel || null, chat: !!chat }
    this.teamSpecs.push(spec)
    this.save()
    this.emitList()
    return spec
  }

  /** Change a team's name, leader model or team chat; a running team uses the same spec, so it applies at once. */
  updateTeam (id, changes) {
    const spec = this.teamSpecs.find(t => t.id === id)
    if (!spec) throw new Error(`no team ${id}`)
    if (changes.name) spec.name = String(changes.name).slice(0, 40)
    if (changes.leaderModel !== undefined) spec.leaderModel = changes.leaderModel || null
    if (changes.chat !== undefined) spec.chat = !!changes.chat
    this.save()
    this.emitList()
  }

  async startTeam (id) {
    const spec = this.teamSpecs.find(t => t.id === id)
    if (!spec) throw new Error(`no team ${id}`)
    this.teams.get(id)?.stop()
    const team = new Team({ cfg: spec, bots: this.bots, log })
    team.on('event', e => this.emit('event', e))
    this.teams.set(id, team)
    const leaderAi = this.specs.find(s => s.id === spec.leader) && this.instance(spec.leader).cfg.ai
    if (spec.leaderModel && leaderAi?.enabled !== false && leaderAi?.source !== 'connector' && hostInfo().ollama.up) {
      ensureModel(this.cfg.ollamaUrl, spec.leaderModel, log).then(() => this.emitList())
    }
    // Start members one by one: each client takes a while and a lot of CPU while loading.
    for (const m of spec.members) {
      try { await this.startBot(m) } catch (e) { log(`[team ${id}] could not start ${m}: ${e.message}`) }
    }
    team.start()
  }

  async stopTeam (id) {
    const team = this.teams.get(id)
    team?.stop()
    this.teams.delete(id)
    const spec = this.teamSpecs.find(t => t.id === id)
    for (const m of spec?.members ?? []) await this.stopBot(m)
  }

  removeTeam (id) {
    this.teams.get(id)?.stop()
    this.teams.delete(id)
    this.teamSpecs = this.teamSpecs.filter(t => t.id !== id)
    this.save()
    this.emitList()
  }

  // ------------------------------------------------------------ misc

  async relayLlm (staffName, request, timeoutMs) {
    if (!this.relay) throw new Error('no website connection to reach the staff member\'s computer')
    return this.relay(staffName, request, timeoutMs)
  }

  hostName () { return this.cfg.name ?? os.hostname() }

  /** RAM left for the OS and everything else; never handed to bots or models. */
  reserveMemMb () { return this.cfg.reserveMemMb ?? 1536 }

  snapshot () {
    const { gpu, ollama } = hostInfo()
    return {
      host: this.hostName(),
      freeMemMb: availableMemMb(),
      totalMemMb: totalMemMb(),
      reserveMemMb: this.reserveMemMb(),
      gpu,
      ollama: { ...ollama, pulling: pullingModels() },
      maxBots: this.cfg.maxBots,
      bots: this.specs.map(s => this.bots.get(s.id)?.status() ?? { id: s.id, name: s.name, server: s.server, state: 'stopped', account: accounts.getAccount(s.account)?.name ?? null }),
      teams: this.teamSpecs.map(t => ({ ...t, ...(this.teams.get(t.id)?.status() ?? { phase: 'stopped' }) })),
      accounts: accounts.listAccounts().map(a => ({ id: a.id, type: a.type, name: a.name, addedBy: a.addedBy }))
    }
  }

  emitList () { this.emit('event', { type: 'list' }) }

  async autoStart () {
    for (const t of this.teamSpecs.filter(t => t.autoStart)) await this.startTeam(t.id).catch(e => log(`team ${t.id}: ${e.message}`))
    for (const s of this.specs.filter(s => s.autoStart)) await this.startBot(s.id).catch(e => log(`bot ${s.id}: ${e.message}`))
  }

  async shutdown () {
    for (const t of this.teams.values()) t.stop()
    await Promise.all([...this.bots.values()].map(b => b.stop()))
  }
}

// ------------------------------------------------------------ entry point

/**
 * Only one manager per folder: two would launch the same accounts on the same bridge ports, kick each
 * other off the server and send the website two different stories about the same bots, so the
 * Bots page flips between "online", "starting" and "stopped". Returns the running one's pid, or null.
 */
export function takeLock (file = path.join(DATA, 'manager.lock')) {
  fs.mkdirSync(DATA, { recursive: true })
  for (let i = 0; i < 2; i++) {
    try {
      fs.writeFileSync(file, String(process.pid), { flag: 'wx' })
      process.on('exit', () => { try { if (fs.readFileSync(file, 'utf8') === String(process.pid)) fs.unlinkSync(file) } catch {} })
      return null
    } catch (e) {
      if (e.code !== 'EEXIST') throw e
    }
    const pid = Number(fs.readFileSync(file, 'utf8'))
    let alive = false
    try { process.kill(pid, 0); alive = true } catch (e) { alive = e.code === 'EPERM' }
    // A reused pid that isn't a manager doesn't count.
    if (alive) try { alive = fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').includes('manager.js') } catch {}
    if (alive && pid !== process.pid) return pid
    fs.rmSync(file, { force: true }) // left behind by a crash
  }
  return null
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname)) {
  const running = takeLock()
  if (running) {
    let how = 'started by hand: stop it with Ctrl+C in its terminal, or kill ' + running
    try { if (fs.readFileSync(`/proc/${running}/cgroup`, 'utf8').includes('moddedrealms-bots.service')) how = 'as the moddedrealms-bots service: systemctl --user stop moddedrealms-bots' } catch {}
    console.error(`[manager] already running (pid ${running}, ${how}). Not starting a second one.`)
    process.exit(1)
  }
  initLog()
  const manager = new Manager()
  await watchHost(manager.cfg.ollamaUrl)
  log(`[manager] starting on ${os.hostname()} (${availableMemMb()} MB free, max ${manager.cfg.maxBots} bots)`)
  if (manager.cfg.website.url) {
    const { connectWebsite } = await import('./link.js')
    connectWebsite(manager)
  } else {
    log('[manager] no website configured: running bots/teams marked autoStart only; status in data/status.json')
    // Without the Bots page, this file is the way to see what the bots are doing.
    setInterval(() => {
      const snap = manager.snapshot()
      for (const b of snap.bots) {
        const body = manager.bots.get(b.id)?.body
        if (body?.inWorld) b.nearby = body.entities().slice(0, 12).map(e => `${e.type}${e.name ? ' ' + e.name : ''} ${e.dist?.toFixed(0)}m`)
      }
      fs.writeFile(path.join(DATA, 'status.json'), JSON.stringify(snap, null, 2), () => {})
    }, 5000).unref()
  }
  await manager.autoStart()
  for (const sig of ['SIGINT', 'SIGTERM']) {
    process.on(sig, async () => {
      log(`[manager] ${sig}, stopping bots`)
      await manager.shutdown()
      process.exit(0)
    })
  }
  process.stdout.on('error', () => {})
  process.on('unhandledRejection', e => log('[manager] unhandled rejection:', e?.stack ?? String(e)))
}
