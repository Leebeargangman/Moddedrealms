// Anarchy mode (2b2t, 9b9t...): spawn is picked clean and full of players. Leave at once: into the nearest nether
// portal, far along the nether (1 block there = 8 here) until food runs low, then back out and live there.
// Anarchy worlds are full of portals players left behind, so it only uses existing ones, never builds.
// Progress is kept in memory.data.escape so a rejoin carries on where it stopped.
import { check, isAbort, wait } from '../util.js'
import { usePortal, nearbyPortals, knownPortal } from './travel.js'

const SEGMENT = 250 // nether blocks per Baritone trip

export function isEscaping (cfg, memory) {
  return cfg.behavior?.mode === 'anarchy' && !memory.data.escape?.done
}

export async function escapeSpawn (ctx, args, signal) {
  const { body, cfg, memory, log } = ctx
  const opts = { maxNetherBlocks: 15000, foodLow: 6, ...(cfg.behavior.anarchy ?? {}) }
  // Teammates share one direction so they come out near each other.
  const e = memory.data.escape ??= { heading: ctx.team()?.heading ?? Math.floor(Math.random() * 8) * Math.PI / 4, startedAt: new Date().toISOString() }
  memory.save()

  if (body.dimension === 'overworld' && !e.entered) await enterNether(ctx, e, signal)
  if (body.dimension === 'the_nether') {
    e.entered ??= { x: Math.round(body.pos.x), z: Math.round(body.pos.z) }
    memory.save()
    await netherRun(ctx, e, opts, signal)
    await leaveNether(ctx, e, signal)
  }
  if (body.dimension !== 'overworld') throw new Error(`ended up in ${body.dimension}`)

  const p = body.pos
  e.done = true
  e.out = { x: Math.round(p.x), z: Math.round(p.z) }
  // Any further travelling carries on outward, the way we came out, not in some random direction back past spawn.
  const distance = cfg.behavior.settleDistance
  memory.data.travel = { angle: Math.atan2(p.z, p.x), distance, done: Math.hypot(p.x, p.z) >= distance - 48 }
  memory.data.explore = null
  memory.save()
  const far = Math.round(Math.hypot(p.x, p.z))
  memory.event(`escaped spawn through the nether, now ${far} blocks out`)
  const team = ctx.team()
  if (team && team.leader() !== ctx.instance()) team.whisper(ctx.instance(), team.leader(), 'made it out of the nether')
  log(`[anarchy] out of the nether at ${e.out.x} ${e.out.z} (${far} from 0 0)`)
  return `escaped spawn, ${far} blocks out`
}

async function enterNether (ctx, e, signal) {
  const { body, memory, log } = ctx
  const tried = new Set()
  for (let attempt = 0; attempt < 8; attempt++) {
    check(signal)
    if (await tryPortals(ctx, [...await nearbyPortals(body), knownPortal(memory, 'overworld', body.pos, 400)], tried, signal)) return
    try {
      // Baritone remembers portals in every chunk it has loaded, a lot further than we can scan.
      return await usePortal(ctx, null, signal)
    } catch (err) {
      if (isAbort(err)) throw err
      log(`[anarchy] no usable portal yet: ${err.message}`)
    }
    // None in sight: head out along our direction and look again (spawn regions are full of portals).
    const p = body.pos
    log('[anarchy] looking for a nether portal')
    await body.goto(p.x + Math.cos(e.heading) * 120, p.z + Math.sin(e.heading) * 120, null, signal, 3 * 60e3)
      .catch(err => { if (isAbort(err)) throw err })
  }
  throw new Error('no nether portal found around spawn')
}

async function netherRun (ctx, e, opts, signal) {
  const { body, memory, log } = ctx
  let fails = 0
  while (true) {
    check(signal)
    const p = body.pos
    const gone = Math.hypot(p.x - e.entered.x, p.z - e.entered.z)
    if (gone >= opts.maxNetherBlocks) return log(`[anarchy] went ${Math.round(gone)} blocks in the nether, heading out`)
    if (body.food <= opts.foodLow && !body.bestFood()) return log(`[anarchy] getting hungry after ${Math.round(gone)} nether blocks, heading out`)
    try {
      await body.goto(p.x + Math.cos(e.heading) * SEGMENT, p.z + Math.sin(e.heading) * SEGMENT, null, signal, 5 * 60e3)
      fails = 0
    } catch (err) {
      if (isAbort(err)) throw err
      // Lava lake, wall... after a few failures bend the route a little.
      if (++fails >= 3) {
        e.heading += (Math.random() < 0.5 ? -1 : 1) * Math.PI / 8
        fails = 0
      }
    }
    e.netherBlocks = Math.round(Math.hypot(body.pos.x - e.entered.x, body.pos.z - e.entered.z))
    memory.save()
  }
}

async function leaveNether (ctx, e, signal) {
  const { body, log } = ctx
  const tried = new Set()
  const base = e.heading
  let fails = 0
  log('[anarchy] looking for a portal out')
  // Keep going roughly the same way for up to ~3000 blocks, taking the first portal that shows up.
  for (let i = 0; i < 25 && body.dimension === 'the_nether'; i++) {
    check(signal)
    if (await tryPortals(ctx, await nearbyPortals(body), tried, signal)) return
    const p = body.pos
    const target = { x: p.x + Math.cos(e.heading) * 120, z: p.z + Math.sin(e.heading) * 120 }
    const seen = await walkLooking(ctx, target, tried, signal)
    if (seen && await tryPortals(ctx, seen, tried, signal)) return
    if (Math.hypot(body.pos.x - target.x, body.pos.z - target.z) > 20 && !seen) {
      // Blocked (lava, a wall): bend the route a little, alternating sides.
      e.heading = base + (++fails % 2 ? 1 : -1) * Math.min(fails, 6) * Math.PI / 12
    } else {
      fails = 0
      e.heading = (e.heading + base) / 2 // drift back to the original direction
    }
  }
  // Last resort: the nearest portal Baritone has seen, even if it's behind us.
  if (body.dimension === 'the_nether') {
    log('[anarchy] no portal found ahead, using the nearest one seen')
    await usePortal(ctx, null, signal)
  }
}

/** Walk towards target, scanning for portals every few seconds; returns the portals seen (or null). */
async function walkLooking (ctx, target, tried, signal) {
  const { body } = ctx
  let found = null
  let done = false
  const scanner = (async () => {
    while (!done && !found) {
      await wait(4000, signal).catch(() => {})
      if (done) return
      const fresh = (await nearbyPortals(body).catch(() => [])).filter(p => !tried.has(`${p.x},${p.y},${p.z}`))
      if (fresh.length) found = fresh
    }
  })()
  try {
    await body.baritone(`goto ${Math.round(target.x)} ${Math.round(target.z)}`)
    await body.waitBaritone(signal, { timeoutMs: 3 * 60e3, until: () => !!found })
  } catch (err) {
    if (isAbort(err)) throw err
  } finally {
    done = true
    await scanner
  }
  return found
}

/** Walk into each portal not tried yet, nearest first; true once one takes us through. */
async function tryPortals (ctx, portals, tried, signal) {
  for (const portal of portals) {
    if (!portal) continue
    const key = `${portal.x},${portal.y},${portal.z}`
    if (tried.has(key)) continue
    tried.add(key)
    try {
      await usePortal(ctx, portal, signal)
      return true
    } catch (err) {
      if (isAbort(err)) throw err
      ctx.log(`[anarchy] portal at ${portal.x} ${portal.y} ${portal.z} didn't work: ${err.message}`)
    }
  }
  return false
}
