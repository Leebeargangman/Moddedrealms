// Getting places: Baritone for walking, and the nether (1 block there = 8 here) for long trips.
import { check, wait, isAbort, hdist } from '../util.js'

const PORTAL_MATERIALS = { obsidian: 10, flint_and_steel: 1 }
// Cheap blocks for the frame's 4 corners.
const CORNER_BLOCKS = ['cobblestone', 'netherrack', 'dirt', 'cobbled_deepslate', 'blackstone', 'stone', 'andesite', 'diorite', 'granite',
  'basalt', 'soul_soil', 'oak_planks', 'spruce_planks', 'birch_planks', 'jungle_planks', 'acacia_planks', 'dark_oak_planks']

const hasPortalMaterials = body => body.count('obsidian') >= PORTAL_MATERIALS.obsidian && body.count('flint_and_steel') >= 1

function rememberPortal (memory, dimension, pos) {
  const portals = memory.data.portals ??= []
  const p = { dimension, x: Math.round(pos.x), y: Math.round(pos.y), z: Math.round(pos.z) }
  if (!portals.some(q => q.dimension === p.dimension && Math.hypot(q.x - p.x, q.z - p.z) < 8)) {
    portals.push(p)
    if (portals.length > 60) portals.shift()
    memory.save()
  }
}

function knownPortal (memory, dimension, near, maxDist) {
  return (memory.data.portals ?? [])
    .filter(p => p.dimension === dimension && Math.hypot(p.x - near.x, p.z - near.z) <= maxDist)
    .sort((a, b) => Math.hypot(a.x - near.x, a.z - near.z) - Math.hypot(b.x - near.x, b.z - near.z))[0]
}

/** Walk into a portal and wait for the dimension to change. */
async function usePortal (ctx, portal, signal) {
  const { body, memory } = ctx
  const from = body.dimension
  const entry = body.pos
  if (portal) await body.baritone(`goto ${portal.x} ${portal.y} ${portal.z}`)
  else await body.baritone('goto nether_portal') // nearest portal Baritone knows about
  try {
    await body.waitBaritone(signal, { timeoutMs: 4 * 60e3, until: () => body.dimension !== from })
  } catch (e) {
    if (isAbort(e)) throw e
  }
  // Standing in a portal takes a few seconds to teleport.
  for (let i = 0; i < 30 && body.dimension === from; i++) await wait(500, signal)
  if (body.dimension === from) throw new Error('could not get through a nether portal')
  rememberPortal(memory, from, portal ?? entry)
  await wait(2000, signal) // let chunks load on the other side
  rememberPortal(memory, body.dimension, body.pos)
}

/**
 * Build a portal frame on a clear spot nearby and light it. Placed block by block from one spot in front, in the
 * order a player would: corners and bottom row, both sides, top corners, then the top row against them.
 */
async function buildPortal (ctx, signal) {
  const { body } = ctx
  if (!hasPortalMaterials(body)) throw new Error('need 10 obsidian and flint and steel to build a portal')
  // Corner blocks first: digging for them afterwards could wreck the chosen spot.
  const corner = await cornerBlock(ctx, signal)
  if (!corner) throw new Error('need 4 cheap blocks (dirt, cobblestone, netherrack...) for the portal corners')
  let site = await body.b.request('portalSite', { radius: 10 })
  // Forests and hills: walk a bit and look again.
  for (let i = 0; !site && i < 4; i++) {
    const p = body.pos
    const a = Math.random() * 2 * Math.PI
    await body.goto(p.x + Math.cos(a) * 24, p.z + Math.sin(a) * 24, null, signal, 60000).catch(e => { if (isAbort(e)) throw e })
    site = await body.b.request('portalSite', { radius: 10 })
  }
  if (!site) throw new Error('no clear flat spot for a portal nearby')
  const L = site.axis === 'x' ? { x: 1, z: 0 } : { x: 0, z: 1 } // along the frame
  const F = site.axis === 'x' ? { x: 0, z: 1 } : { x: 1, z: 0 } // in front of it (where portalSite checked for room)
  const at = (i, j) => ({ x: site.x + L.x * i, y: site.y + j, z: site.z + L.z * i })
  const stand = { x: site.x + L.x + F.x, y: site.y, z: site.z + L.z + F.z }
  await body.goto(stand.x, stand.z, stand.y, signal, 90000)

  const toward = site.axis === 'x' ? ['east', 'west'] : ['south', 'north']
  const steps = [
    [at(0, 0), at(0, -1), 'up', corner], [at(3, 0), at(3, -1), 'up', corner],
    [at(1, 0), at(1, -1), 'up', 'obsidian'], [at(2, 0), at(2, -1), 'up', 'obsidian'],
    ...[1, 2, 3].flatMap(j => [[at(0, j), at(0, j - 1), 'up', 'obsidian'], [at(3, j), at(3, j - 1), 'up', 'obsidian']]),
    [at(0, 4), at(0, 3), 'up', corner], [at(3, 4), at(3, 3), 'up', corner],
    [at(1, 4), at(0, 4), toward[0], 'obsidian'], [at(2, 4), at(3, 4), toward[1], 'obsidian']
  ]
  for (const [pos, against, face, item] of steps) {
    for (let tries = 0; tries < 3; tries++) {
      check(signal)
      if ((await body.b.request('blockAt', pos)).name === item) break
      await body.hold(i => i.item === item)
      await body.b.request('useOn', { ...against, face })
      await wait(250, signal)
    }
    if ((await body.b.request('blockAt', pos)).name !== item) throw new Error(`couldn't place the portal frame at ${pos.x} ${pos.y} ${pos.z}`)
  }

  // Light it: flint and steel on top of a bottom frame block.
  const base = at(1, 0)
  await body.hold(i => i.item === 'flint_and_steel')
  await body.b.request('useOn', { ...base, face: 'up' })
  await wait(1000, signal)
  const lit = await body.b.request('blockAt', { x: base.x, y: base.y + 1, z: base.z })
  if (lit.name !== 'nether_portal') throw new Error('portal frame built but would not light')
  return { x: base.x, y: base.y + 1, z: base.z }
}

/** A block we have 4 of for the frame corners; mines a few netherrack or dirt if there's none. */
async function cornerBlock (ctx, signal) {
  const { body } = ctx
  const have = CORNER_BLOCKS.find(b => body.count(b) >= 4)
  if (have) return have
  const dig = body.dimension === 'the_nether' ? 'netherrack' : 'dirt'
  await body.baritone(`mine 6 ${dig}`)
  await body.waitBaritone(signal, { timeoutMs: 90000, until: () => body.count(dig) >= 4 }).catch(e => { if (isAbort(e)) throw e })
  await body.stop()
  return body.count(dig) >= 4 ? dig : null
}

/**
 * Portals within 32 blocks, nearest first, as the spot to walk into: the bottom portal block, where you stand.
 * Anarchy spawns are full of broken or trapped portals, so callers try a few.
 */
async function nearbyPortals (body, max = 4) {
  const hits = await body.b.request('findBlocks', { names: ['nether_portal'], radius: 32, max: 400 })
  const at = new Set(hits.map(h => `${h.x},${h.y},${h.z}`))
  const portals = []
  for (const h of hits) {
    if (at.has(`${h.x},${h.y - 1},${h.z}`)) continue
    if (portals.some(p => Math.abs(p.x - h.x) + Math.abs(p.z - h.z) <= 3 && Math.abs(p.y - h.y) <= 3)) continue
    portals.push(h)
  }
  return portals.slice(0, max)
}

async function nearbyPortal (body) {
  return (await nearbyPortals(body, 1))[0] ?? null
}

/** Overworld -> nether -> overworld. Throws if the route isn't possible; the caller then walks. */
async function viaNether (ctx, target, signal) {
  const { body, memory, log } = ctx
  const here = body.pos
  const exitKnown = knownPortal(memory, 'the_nether', { x: target.x / 8, z: target.z / 8 }, 64)
  // Don't go in unless we can get out near the target again.
  if (!exitKnown && !hasPortalMaterials(body)) throw new Error('no way out of the nether near the target (need 10 obsidian + flint and steel)')

  const entry = knownPortal(memory, 'overworld', here, 300) ?? await nearbyPortal(body)
  log(`[travel] nether route to ${Math.round(target.x)} ${Math.round(target.z)}${entry ? ` via portal at ${entry.x} ${entry.z}` : ''}`)
  if (entry) await usePortal(ctx, entry, signal)
  else {
    try {
      await usePortal(ctx, null, signal)
    } catch (e) {
      if (isAbort(e)) throw e
      await usePortal(ctx, await buildPortal(ctx, signal), signal)
    }
  }
  check(signal)

  // In the nether: go to the matching spot, then out.
  const nx = target.x / 8
  const nz = target.z / 8
  if (exitKnown) {
    await usePortal(ctx, exitKnown, signal)
  } else {
    await body.goto(nx, nz, null, signal, 30 * 60e3)
    const near = await nearbyPortal(body)
    await usePortal(ctx, near ?? await buildPortal(ctx, signal), signal)
  }
  if (body.dimension !== 'overworld') throw new Error(`came out in ${body.dimension}`)
}

/**
 * Go to target {x, z, y?}. Overworld trips over `netherTravelMin` blocks go through the nether
 * when possible; otherwise (or if that fails) Baritone walks.
 */
export async function travelTo (ctx, target, signal, { timeoutMs = 60 * 60e3, nether = true } = {}) {
  const { body, cfg, log } = ctx
  const min = cfg.behavior.netherTravelMin ?? 1000
  if (nether && body.dimension === 'overworld' && hdist(body.pos, target) >= min) {
    try {
      await viaNether(ctx, target, signal)
    } catch (e) {
      if (isAbort(e)) throw e
      log(`[travel] nether route not possible (${e.message}), walking`)
      // Failed halfway: get back to the overworld before walking.
      if (body.dimension === 'the_nether') {
        const near = await nearbyPortal(body)
        await usePortal(ctx, near ?? (hasPortalMaterials(body) ? await buildPortal(ctx, signal) : null), signal)
      }
    }
  }
  await body.goto(target.x, target.z, target.y, signal, timeoutMs)
  return `arrived at ${Math.round(target.x)} ${Math.round(target.z)}`
}

export { buildPortal, usePortal, nearbyPortal, nearbyPortals, knownPortal, hasPortalMaterials }
