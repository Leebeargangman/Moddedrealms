// Getting places: Baritone for walking, and the nether (1 block there = 8 here) for long trips.
import path from 'node:path'
import fs from 'node:fs'
import { check, wait, isAbort, hdist } from '../util.js'
import { writePortalSchematic } from '../build/portal.js'

const PORTAL_MATERIALS = { obsidian: 10, flint_and_steel: 1 }

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

/** Build and light a portal frame next to us with the bundled schematic. */
async function buildPortal (ctx, signal) {
  const { body, gameDir } = ctx
  if (!hasPortalMaterials(body)) throw new Error('need 10 obsidian and flint and steel to build a portal')
  const file = path.join(gameDir, 'schematics', 'nether_portal.litematic')
  if (!fs.existsSync(file)) writePortalSchematic(file)
  const p = body.pos
  const origin = { x: Math.floor(p.x) + 2, y: Math.floor(p.y), z: Math.floor(p.z) + 2 }
  const ok = await body.b.request('build', { file: 'nether_portal.litematic', ...origin })
  if (!ok) throw new Error('Baritone could not load the portal schematic')
  await body.waitBaritone(signal, { timeoutMs: 4 * 60e3 })
  // Light it: flint and steel on top of a bottom frame block.
  await body.hold(i => i.item === 'flint_and_steel')
  await body.b.request('useOn', { x: origin.x + 1, y: origin.y, z: origin.z, face: 'up' })
  await wait(1000, signal)
  const lit = await body.b.request('blockAt', { x: origin.x + 1, y: origin.y + 1, z: origin.z })
  if (lit.name !== 'nether_portal') throw new Error('portal frame built but would not light')
  return { x: origin.x + 1, y: origin.y + 1, z: origin.z }
}

async function nearbyPortal (body) {
  const hits = await body.b.request('findBlocks', { names: ['nether_portal'], radius: 32, max: 1 })
  return hits[0] ?? null
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

export { buildPortal, usePortal, nearbyPortal, knownPortal, hasPortalMaterials }
