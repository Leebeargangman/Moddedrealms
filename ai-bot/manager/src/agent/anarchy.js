// Anarchy mode (2b2t, 9b9t...): spawn is picked clean and full of players. Leave at once: into the nearest nether
// portal, far along the nether (1 block there = 8 here) until food runs low, then back out and live there.
// Progress is kept in memory.data.escape so a rejoin carries on where it stopped.
import { check, isAbort } from '../util.js'
import { usePortal, nearbyPortal, knownPortal, buildPortal, hasPortalMaterials } from './travel.js'

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
  for (let attempt = 0; attempt < 8; attempt++) {
    check(signal)
    const portal = await nearbyPortal(body) ?? knownPortal(memory, 'overworld', body.pos, 400)
    try {
      if (portal) return await usePortal(ctx, portal, signal)
      if (hasPortalMaterials(body)) return await usePortal(ctx, await buildPortal(ctx, signal), signal)
    } catch (err) {
      if (isAbort(err)) throw err
      log(`[anarchy] portal didn't work: ${err.message}`)
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
  for (let i = 0; i < 8 && body.dimension === 'the_nether'; i++) {
    check(signal)
    try {
      const portal = await nearbyPortal(body)
      if (portal) return await usePortal(ctx, portal, signal)
      if (hasPortalMaterials(body)) return await usePortal(ctx, await buildPortal(ctx, signal), signal)
    } catch (err) {
      if (isAbort(err)) throw err
      log(`[anarchy] couldn't use that portal: ${err.message}`)
    }
    // Keep going a bit and look again.
    const p = body.pos
    await body.goto(p.x + Math.cos(e.heading) * SEGMENT, p.z + Math.sin(e.heading) * SEGMENT, null, signal, 5 * 60e3)
      .catch(err => { if (isAbort(err)) throw err })
  }
  // Last resort: the nearest portal Baritone has seen, even if it's behind us.
  if (body.dimension === 'the_nether') await usePortal(ctx, null, signal)
}
