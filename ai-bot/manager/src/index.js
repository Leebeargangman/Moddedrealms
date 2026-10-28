import mineflayer from 'mineflayer'
import prismarineAuth from 'prismarine-auth'
import { loadConfig } from './config.js'
import { initLog, log, notify } from './log.js'
import { Memory } from './memory.js'
import { QueueTracker } from './queue.js'
import { Agent } from './agent.js'
import { Social } from './social.js'
import { pathfinder } from './nav.js'
import { inputsPlugin } from './inputs.js'
import { sleep } from './util.js'

const { Titles } = prismarineAuth
const cfg = loadConfig()
initLog(cfg)
const memory = new Memory()
let currentBot = null

function createBot () {
  return mineflayer.createBot({
    host: cfg.server.host,
    port: cfg.server.port,
    version: cfg.server.version || false,
    username: cfg.account.username,
    auth: cfg.account.auth,
    profilesFolder: cfg.account.profilesFolder,
    // Keep in sync with src/login.js so both use the same cached sign-in.
    authTitle: Titles[cfg.account.authTitle],
    deviceType: cfg.account.deviceType,
    flow: cfg.account.flow,
    onMsaCode: d => notify(`Microsoft sign-in needed for ${cfg.account.username}: open ${d.verification_uri} and enter code ${d.user_code}`),
    checkTimeoutInterval: 90_000,
    respawn: true
  })
}

function runSession () {
  return new Promise(resolve => {
    const startedAt = Date.now()
    let reason = 'connection ended'
    let gameSince = 0
    const bot = createBot()
    currentBot = bot
    bot.loadPlugin(pathfinder)
    bot.once('login', () => inputsPlugin(bot))
    const queue = new QueueTracker(bot)
    const agent = new Agent({ bot, cfg, memory, queue })
    new Social(agent) // only hears owner whispers, its own name, and players close by
    const stats = memory.data.stats
    let died = false

    bot.once('login', () => {
      log(`[conn] logged in as ${bot.username} (protocol ${bot.version})`)
      if (cfg.account.auth === 'microsoft' && bot.username.toLowerCase() !== cfg.account.username.toLowerCase()) {
        log(`[conn] WARNING: signed-in profile is ${bot.username}, expected ${cfg.account.username}`)
      }
    })

    queue.on('state', (state, prev) => {
      log(`[queue] ${prev} -> ${state}`)
      if (state === 'game') {
        gameSince = Date.now()
        stats.joins++
        memory.save()
        notify(`✅ ${bot.username} is in the game${prev === 'queue' ? ' (queue finished)' : ''}.`)
        agent.start()
      } else if (state === 'queue') {
        if (gameSince) { stats.playMs += Date.now() - gameSince; gameSince = 0 }
        agent.stop()
      }
    })

    queue.on('position', p => {
      const eta = queue.eta()
      const etaText = eta != null ? `, ETA ~${Math.round(eta / 60000)} min` : ''
      log(`[queue] position ${p}${etaText}`)
      const every = cfg.notify.queueEvery || 100
      if (p <= 10 || p % every === 0) notify(`⏳ ${bot.username} queue position ${p}${etaText}`)
    })

    bot.on('messagestr', (msg, position) => {
      if (position === 'game_info') return
      // System lines (deaths, joins, server notices) only; player chat goes through Social.
      if (msg.startsWith('<')) return
      log('[chat]', msg.slice(0, 300))
      if (bot.username && msg.includes(bot.username) && !/whispers|->/.test(msg)) agent.lastDeathMsg = msg.slice(0, 200)
    })

    bot.on('death', () => { died = true; agent.onDeath() })
    bot.on('spawn', () => {
      if (!died) return
      died = false
      const bed = memory.data.spawnBed
      const p = bot.entity.position
      if (bed && Math.hypot(p.x - bed.x, p.z - bed.z) > 16) {
        memory.event('respawned away from our bed: bed spawn lost')
        memory.data.spawnBed = null
        memory.save()
      }
      log(`[conn] respawned at ${p.floored()}`)
    })

    bot.on('kicked', r => { reason = `kicked: ${typeof r === 'string' ? r : JSON.stringify(r)}`.slice(0, 500) })
    bot.on('error', e => { log('[conn] error:', e.message); reason = `error: ${e.message}` })

    const statusTimer = setInterval(() => agent.writeStatus(), 5000)
    bot.once('end', () => {
      clearInterval(statusTimer)
      agent.stop()
      if (gameSince) stats.playMs += Date.now() - gameSince
      memory.save()
      agent.writeStatus()
      resolve({ reason, durationMs: Date.now() - startedAt, wasInGame: !!gameSince, panicked: agent.panicked })
    })
  })
}

async function main () {
  log(`[main] starting bot for ${cfg.account.username} -> ${cfg.server.host}:${cfg.server.port} (AI: ${cfg.ai.enabled ? cfg.ai.model : 'rules only'})`)
  let delay = cfg.reconnect.minDelayMs
  for (;;) {
    memory.data.stats.sessions++
    let r
    try {
      r = await runSession()
    } catch (e) {
      r = { reason: `crash: ${e.message}`, durationMs: 0 }
    }
    if (r.panicked) delay = 60_000
    else if (r.durationMs > 10 * 60e3) delay = cfg.reconnect.minDelayMs
    else delay = Math.min(delay * 2, cfg.reconnect.maxDelayMs)
    if (/already (connected|online)|another location/i.test(r.reason)) delay = Math.max(delay, 60_000)
    notify(`🔌 ${cfg.account.username} disconnected (${r.reason}) after ${Math.round(r.durationMs / 60000)} min. Reconnecting in ${Math.round(delay / 1000)}s.`)
    await sleep(delay)
  }
}

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    log(`[main] ${sig}, quitting`)
    try { memory.save(); currentBot?.quit('shutdown') } catch {}
    setTimeout(() => process.exit(0), 1000)
  })
}
// Writing to a closed stdout pipe must not take the bot down.
process.stdout.on('error', () => {})
process.on('unhandledRejection', e => log('[main] unhandled rejection:', e?.stack ?? String(e)))
process.on('uncaughtException', e => log('[main] uncaught exception:', e?.stack ?? String(e)))

main()
