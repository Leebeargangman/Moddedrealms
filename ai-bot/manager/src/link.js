// Connects a manager to the website: long-polls for staff commands, reports events and a status snapshot,
// and relays AI prompts to staff members' own computers. Only outbound HTTPS; nothing listens here.
import os from 'node:os'
import { log } from './log.js'
import { sleep } from './util.js'
import * as accounts from './bot/accounts.js'
import { readLitematic } from './build/litematic.js'

// Live-only events (status ticks) are covered by the snapshot; everything else goes to the website.
const SKIP = new Set(['status'])

export function connectWebsite (manager) {
  const { url, key } = manager.cfg.website
  const name = (manager.cfg.name || os.hostname()).replace(/[^\w.-]/g, '-').slice(0, 32)
  const headers = { authorization: `Bearer ${key}`, 'x-manager-name': name }
  let outbox = []
  let snapshotDue = true

  async function post (path, body, timeoutMs = 15000) {
    const res = await fetch(url + path, { method: 'POST', headers: { ...headers, 'content-type': 'application/json' }, body: JSON.stringify(body), signal: AbortSignal.timeout(timeoutMs) })
    const data = await res.json().catch(() => ({}))
    if (!res.ok || data.ok === false) throw new Error(data.error || `HTTP ${res.status}`)
    return data
  }

  const send = ev => { outbox.push(ev) }
  manager.on('event', ev => {
    if (ev.type === 'list') snapshotDue = true
    else if (!SKIP.has(ev.type)) send(ev)
  })

  // Staff run the AI on their own PC: send the prompt through the website to their connector.
  manager.relay = async (staff, request, timeoutMs) => (await post('/api/botlink/llm', { staff, request, timeoutMs }, timeoutMs + 10000)).response

  // Events every ~0.7 s, a full snapshot every 3 s (or right after something changed).
  let lastSnapshot = 0
  ;(async function pushLoop () {
    while (true) {
      await sleep(700)
      const snap = snapshotDue || Date.now() - lastSnapshot > 3000
      if (!outbox.length && !snap) continue
      const events = outbox.splice(0, 500)
      try {
        await post('/api/botlink/events', { events, ...(snap ? { snapshot: manager.snapshot() } : {}) })
        if (snap) { lastSnapshot = Date.now(); snapshotDue = false }
      } catch (e) {
        outbox = [...events, ...outbox].slice(-2000)
        log('[link] could not reach the website:', e.message)
        await sleep(5000)
      }
    }
  })()

  ;(async function pollLoop () {
    let backoff = 2000
    while (true) {
      try {
        const res = await fetch(url + '/api/botlink/poll', { headers, signal: AbortSignal.timeout(40000) })
        const data = await res.json().catch(() => ({}))
        if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`)
        backoff = 2000
        for (const cmd of data.items ?? []) run(cmd)
      } catch (e) {
        log('[link] poll failed:', e.message)
        await sleep(backoff)
        backoff = Math.min(backoff * 2, 60000)
      }
    }
  })()

  async function download (buildId) {
    const res = await fetch(`${url}/api/botlink/builds/${buildId}`, { headers, signal: AbortSignal.timeout(60000) })
    if (!res.ok) throw new Error(`download failed: HTTP ${res.status}`)
    return { name: decodeURIComponent(res.headers.get('x-build-name') || `${buildId}.litematic`), buffer: Buffer.from(await res.arrayBuffer()) }
  }

  // Staff members' in-game names count as staff for every bot (whispers, "come to me"...).
  function rememberStaff (staff) {
    const mc = staff?.minecraft
    if (!mc) return
    for (const bot of manager.bots.values()) {
      const list = bot.cfg.social.staff ??= []
      if (!list.some(n => n.toLowerCase() === mc.toLowerCase())) list.push(mc)
    }
    const defaults = manager.cfg.defaults.social.staff ??= []
    if (!defaults.some(n => n.toLowerCase() === mc.toLowerCase())) defaults.push(mc)
  }

  async function run ({ id, command, args, staff }) {
    const reply = (ok, result) => send({ type: 'result', id, command, bot: args.bot ?? null, who: staff?.name, ok, ...(ok ? { result } : { error: result }) })
    rememberStaff(staff)
    try {
      const bot = args.bot ? manager.instance(args.bot) : null
      const who = staff?.minecraft || staff?.name
      switch (command) {
        case 'startBot': await manager.startBot(args.bot); return reply(true, 'starting')
        case 'stopBot': await manager.stopBot(args.bot); return reply(true, 'stopped')
        case 'rejoin': await bot.rejoin(`asked by ${staff?.name}`); return reply(true, 'rejoining')
        case 'createBot': {
          const ai = args.ai ?? {}
          if (ai.source === 'connector' && !ai.connector) ai.connector = staff?.name
          return reply(true, manager.createBot({ ...args, ai, owner: staff?.name, goal: args.goal ? String(args.goal) : undefined }))
        }
        case 'updateBot': {
          const changes = { ...(args.changes ?? {}) }
          if (changes.goal !== undefined) changes.goalBy = staff?.name
          manager.updateBot(args.bot, changes)
          return reply(true, 'saved')
        }
        case 'removeBot': await manager.removeBot(args.bot); return reply(true, 'removed')
        case 'createTeam': return reply(true, manager.createTeam(args))
        case 'updateTeam': manager.updateTeam(args.team, args.changes ?? {}); return reply(true, 'saved')
        case 'startTeam': manager.startTeam(args.team).catch(e => send({ type: 'error', text: e.message })); return reply(true, 'starting the team')
        case 'stopTeam': await manager.stopTeam(args.team); return reply(true, 'stopped')
        case 'removeTeam': manager.removeTeam(args.team); return reply(true, 'removed')
        case 'message':
          bot.fromWebsite(who, String(args.text ?? ''))
          return reply(true, 'sent')
        case 'order':
          if (!bot.agent) throw new Error(`${bot.cfg.name} isn't in a world right now`)
          bot.agent.setOrder({ action: args.action, args: args.args ?? {}, source: 'owner', reply: t => send({ type: 'reply', bot: args.bot, to: who, text: t }) })
          return reply(true, 'ordered')
        case 'inspectBuild': {
          const { name, buffer } = await download(args.buildId)
          return reply(true, { name, ...(await readLitematic(buffer)) })
        }
        case 'build': {
          if (!bot.agent) throw new Error(`${bot.cfg.name} isn't in a world right now`)
          const { name, buffer } = await download(args.buildId)
          const file = await bot.addSchematic(name, buffer)
          const where = args.x != null && args.y != null && args.z != null ? { x: Number(args.x), y: Number(args.y), z: Number(args.z) } : {}
          bot.agent.setOrder({ action: 'build', args: { file, ...where, force: !!args.force }, source: 'owner', reply: t => send({ type: 'reply', bot: args.bot, to: who, text: t }) })
          return reply(true, `building ${name}`)
        }
        case 'addOffline': manager.emitList(); return reply(true, accounts.addOffline(String(args.name ?? ''), staff?.name))
        case 'linkMicrosoft': {
          reply(true, 'starting Microsoft sign-in')
          accounts.linkMicrosoft(staff?.name, code => send({ type: 'linkCode', id, who: staff?.name, ...code }))
            .then(acc => { send({ type: 'result', id, command, ok: true, result: `linked ${acc.name}` }); manager.emitList() })
            .catch(e => send({ type: 'result', id, command, ok: false, error: e.message }))
          return
        }
        case 'removeAccount': accounts.removeAccount(String(args.account)); manager.emitList(); return reply(true, 'removed')
        default: throw new Error(`unknown command ${command}`)
      }
    } catch (e) {
      reply(false, e.message)
    }
  }

  log(`[link] connected to ${url} as "${name}"`)
}
