// Everything the AI can choose to do, built on Baritone and the bridge mod.
import fs from 'node:fs'
import path from 'node:path'
import { check, wait, isAbort, hdist, tierOf, isNightTime } from '../util.js'
import { travelTo } from './travel.js'
import { fight, gearScore } from './combat.js'
import { readLitematic, missingMaterials, normalizeLitematic } from '../build/litematic.js'

const LOGS = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry', 'pale_oak'].map(w => `${w}_log`)
const FOOD_ANIMALS = new Set(['cow', 'pig', 'sheep', 'chicken', 'rabbit', 'mooshroom'])
const BEDS = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black'].map(c => `${c}_bed`)
const ORES = ['diamond', 'iron', 'gold', 'coal', 'lapis', 'redstone', 'emerald', 'copper'].flatMap(o => [`${o}_ore`, `deepslate_${o}_ore`])
const COVER = /^(cobblestone|cobbled_deepslate|dirt|netherrack|stone|deepslate|.*_planks)$/

const clean = s => String(s ?? '').toLowerCase().replace(/^minecraft:/, '').trim().replace(/\s+/g, '_')

// Friendly names the LLM may use -> block ids for Baritone.
function blocksFor (what) {
  const w = clean(what)
  if (/^(wood|log|logs|tree)s?$/.test(w)) return LOGS
  if (/^(stone|cobblestone|cobble|rock)$/.test(w)) return ['stone', 'cobblestone', 'deepslate']
  const base = w.replace(/^raw_/, '').replace(/_(ore|ingot)$/, '').replace(/^deepslate_/, '')
  if (['coal', 'iron', 'gold', 'diamond', 'copper', 'lapis', 'redstone', 'emerald'].includes(base)) return [`${base}_ore`, `deepslate_${base}_ore`]
  return [w]
}

const find = (body, names, radius = 24, max = 1) => body.b.request('findBlocks', { names, radius, max })

// ---------------------------------------------------------------- gathering

async function mine (ctx, names, want, signal, timeoutMs = 4 * 60e3) {
  const { body } = ctx
  const before = body.items().reduce((n, i) => n + i.count, 0)
  await body.baritone(`mine ${want} ${names.join(' ')}`)
  await body.waitBaritone(signal, { timeoutMs, stuckMs: 90000 })
  const got = body.items().reduce((n, i) => n + i.count, 0) - before
  if (got <= 0) throw new Error(`couldn't find or mine any ${names[0].replace(/_/g, ' ')} nearby`)
  return `mined, picked up ${got} items`
}

// ---------------------------------------------------------------- placing & crafting

/** Place a block item on the ground next to us; returns its position. */
async function placeNear (ctx, item, signal) {
  const { body } = ctx
  await body.refresh()
  if (!body.count(item)) throw new Error(`no ${item} to place`)
  const p = body.pos
  const fx = Math.floor(p.x)
  const fy = Math.floor(p.y)
  const fz = Math.floor(p.z)
  for (const [dx, dz] of [[2, 0], [-2, 0], [0, 2], [0, -2], [2, 1], [1, 2], [-2, -1], [-1, -2]]) {
    check(signal)
    const target = { x: fx + dx, y: fy, z: fz + dz }
    const [at, below] = await Promise.all([
      body.b.request('blockAt', target),
      body.b.request('blockAt', { ...target, y: fy - 1 })
    ])
    if (!at.air || !below.solid) continue
    await body.hold(i => i.item === item)
    await body.b.request('useOn', { x: target.x, y: fy - 1, z: target.z, face: 'up' })
    await wait(400, signal)
    if ((await body.b.request('blockAt', target)).name === item) return target
  }
  throw new Error(`no space to place ${item}`)
}

// Planks and sticks get made automatically when a recipe needs them.
async function prepWood (body) {
  const log = body.find(i => /_(log|stem)$/.test(i.item) && !i.item.startsWith('stripped_'))
  if (log && body.count(/_planks$/) < 8) await body.craft(log.item.replace(/_(log|stem)$/, '_planks'), 2)
  if (body.count(/_planks$/) >= 2 && body.count('stick') < 4) await body.craft('stick', 1)
}

async function craft (ctx, item, want, signal) {
  const { body } = ctx
  item = clean(item)
  want = Math.max(1, Math.min(64, Number(want) || 1))
  await body.refresh()
  let r = await body.craft(item, want)
  if (r.made > 0) return `crafted ${r.made} ${item}`
  if (!r.needsTable) {
    await prepWood(body)
    r = await body.craft(item, want)
    if (r.made > 0) return `crafted ${r.made} ${item}`
  }
  // Needs the 3x3 grid of a crafting table (or the recipe isn't makeable yet).
  let table = (await find(body, ['crafting_table'], 24))[0]
  if (!table) {
    if (!body.count('crafting_table')) {
      await prepWood(body)
      if (!(await body.craft('crafting_table', 1)).made) throw new Error('need planks for a crafting table (gather_wood first)')
    }
    table = await placeNear(ctx, 'crafting_table', signal)
  }
  await body.open(table.x, table.y, table.z, signal)
  try {
    await prepWood(body)
    r = await body.craft(item, want)
    if (r.made > 0) return `crafted ${r.made} ${item}`
    throw new Error(`can't craft ${item}: missing materials or recipe not unlocked`)
  } finally {
    await body.closeContainer()
  }
}

const FUELS = [['coal', 8], ['charcoal', 8], [/_planks$/, 1.5], [/_(log|stem)$/, 1.5]]

async function smelt (ctx, input, want, signal) {
  const { body } = ctx
  input = clean(input)
  const src = body.find(input)
  if (!src) throw new Error(`no ${input} in inventory`)
  want = Math.max(1, Math.min(16, Number(want) || 8, body.count(input)))
  const fuel = FUELS.map(([pred, per]) => [body.find(i => (pred instanceof RegExp ? pred.test(i.item) : i.item === pred) && i.item !== input), per]).find(([f]) => f)
  if (!fuel) throw new Error('no fuel (coal, charcoal, planks or logs)')

  let furnace = (await find(body, ['furnace'], 24))[0]
  if (!furnace) {
    if (!body.count('furnace')) await craft(ctx, 'furnace', 1, signal)
    await body.refresh()
    furnace = await placeNear(ctx, 'furnace', signal)
  }
  const c = await body.open(furnace.x, furnace.y, furnace.z, signal)
  const n = c.size - 36 // 3: input 0, fuel 1, output 2
  const move = async (from, to) => {
    await body.b.request('click', { slot: body.menuSlot(from, n), mode: 'PICKUP' })
    await body.b.request('click', { slot: to, mode: 'PICKUP' })
    await body.b.request('click', { slot: body.menuSlot(from, n), mode: 'PICKUP' }) // put back any leftover
  }
  let got = 0
  try {
    await move(src.slot, 0)
    await move(fuel[0].slot, 1)
    const t0 = Date.now()
    while (got < want && Date.now() - t0 < want * 11000 + 5000) {
      await wait(2000, signal)
      const now = await body.container()
      const out = now.slots.find(s => s.slot === 2)
      if (out) {
        await body.b.request('click', { slot: 2, mode: 'QUICK_MOVE' })
        got += out.count
      }
    }
  } finally {
    await body.closeContainer()
  }
  return `smelted ${got} ${input}`
}

// ---------------------------------------------------------------- survival

async function hunt (ctx, args, signal) {
  const { body } = ctx
  const want = Math.max(1, Math.min(6, Number(args.count) || 3))
  let killed = 0
  while (killed < want) {
    check(signal)
    const target = body.entities().find(e => FOOD_ANIMALS.has(e.type) && e.dist < 48)
    if (!target) break
    if (await fight(ctx, target.id, signal, 30000) === 'killed') killed++
  }
  if (!killed) throw new Error('no animals nearby')
  // Walk over the drops.
  const p = body.pos
  await body.goto(p.x, p.z, null, signal, 10000).catch(() => {})
  return `killed ${killed} animals`
}

async function sleep (ctx, args, signal) {
  const { body, memory } = ctx
  if (body.dimension !== 'overworld') throw new Error('beds explode outside the overworld')
  if (!isNightTime(body.s.time)) throw new Error('can only sleep at night')
  let bed = (await find(body, BEDS, 24))[0]
  if (!bed) {
    const item = body.find(i => i.item.endsWith('_bed'))
    if (!item) throw new Error('no bed')
    bed = await placeNear(ctx, item.item, signal)
  }
  if (body.dist(bed.x, bed.y, bed.z) > 3) await body.goto(bed.x, bed.z, null, signal, 60000).catch(() => {})
  await body.b.request('useOn', { ...bed, face: 'up' })
  memory.data.spawnBed = { x: bed.x, y: bed.y, z: bed.z }
  memory.event(`respawn point set at bed ${bed.x} ${bed.y} ${bed.z}`)
  const t0 = Date.now()
  while (isNightTime(body.s.time) && Date.now() - t0 < 3 * 60e3) await wait(2000, signal)
  return 'slept through the night'
}

// Dig two blocks down and close the hole above: survives a night without a bed.
async function hide (ctx, args, signal) {
  const { body } = ctx
  const seconds = Math.max(10, Math.min(600, Number(args.seconds) || 120))
  const p = body.pos
  const x = Math.floor(p.x)
  const y = Math.floor(p.y)
  const z = Math.floor(p.z)
  const ground = await body.b.request('groundBelow', { points: [{ x, z }], fromY: y - 3, depth: 2 })
  if (ground[0] == null) throw new Error('no solid ground to dig into here')
  await body.goto(x, z, y - 2, signal, 60000)
  const cover = body.find(i => COVER.test(i.item))
  if (cover) {
    await body.hold(i => i.item === cover.item)
    for (const [dx, dz, face] of [[1, 0, 'west'], [-1, 0, 'east'], [0, 1, 'north'], [0, -1, 'south']]) {
      const side = await body.b.request('blockAt', { x: x + dx, y, z: z + dz })
      if (!side.solid) continue
      await body.b.request('useOn', { x: x + dx, y, z: z + dz, face })
      break
    }
  }
  const t0 = Date.now()
  while (Date.now() - t0 < seconds * 1000) {
    await wait(5000, signal)
    if (args.untilDay && !isNightTime(body.s.time)) break
  }
  return `hid for ${Math.round((Date.now() - t0) / 1000)}s`
}

async function stripMine (ctx, args, signal) {
  const { body } = ctx
  if (body.dimension !== 'overworld') throw new Error('strip mining only in the overworld')
  if (body.bestTier('pickaxe') < 1) throw new Error('need a pickaxe')
  const minutes = Math.max(1, Math.min(15, Number(args.minutes) || 6))
  const wanted = ORES.filter(o => body.bestTier('pickaxe') >= 3 || !/diamond|gold|redstone|emerald/.test(o))
  // Legit mining: only ores it can actually see, from a tunnel at y=-53 (below that is lava).
  await body.baritone('set legitMine true')
  await body.baritone('set legitMineYLevel -53')
  const before = body.inventory()
  try {
    await body.baritone(`mine ${wanted.join(' ')}`)
    await body.waitBaritone(signal, { timeoutMs: minutes * 60e3, stuckMs: 120000 }).catch(e => { if (!/timed out/.test(e.message)) throw e })
  } finally {
    await body.stop()
  }
  const after = body.inventory()
  const gained = Object.entries(after).filter(([k, v]) => /raw_|coal|diamond|lapis|redstone|emerald/.test(k) && v > (before[k] ?? 0)).map(([k, v]) => `${v - (before[k] ?? 0)} ${k}`)
  return `strip mined ${minutes} min: ${gained.join(', ') || 'nothing yet'}`
}

// ---------------------------------------------------------------- stash

const KEEP_ALL = /_(pickaxe|axe|shovel|sword|helmet|chestplate|leggings|boots|bed)$|^(shield|totem_of_undying|bow|crossbow|arrow|elytra|ender_pearl|water_bucket|flint_and_steel|golden_apple|enchanted_golden_apple|crafting_table|obsidian)$/
const KEEP_SOME = { cobblestone: 64, cobbled_deepslate: 32, dirt: 16, torch: 32, coal: 16, stick: 8, iron_ingot: 8 }

function strangerNear (ctx, radius) {
  return ctx.body.players().find(p => !['owner', 'friend', 'staff'].includes(ctx.social?.relation(p.name)) && p.dist < radius)
}

async function openStash (ctx, signal, create) {
  const { body, memory, cfg } = ctx
  const watcher = strangerNear(ctx, 64)
  if (watcher) throw new Error(`${watcher.name} is nearby, not revealing the stash`)
  const s = memory.data.stash
  if (s && s.dimension === body.dimension && hdist(s, body.pos) < 400) {
    const block = await body.b.request('blockAt', s)
    if (block.name === 'chest' || block.name === 'barrel') return body.open(s.x, s.y, s.z, signal)
    memory.event(`stash at ${s.x} ${s.y} ${s.z} is gone (raided?)`)
    memory.data.stash = null
  }
  if (!create) throw new Error('no stash nearby')
  const p = body.pos
  if (Math.hypot(p.x, p.z) < cfg.behavior.settleDistance * 0.8) throw new Error('too close to spawn to make a stash')
  if (!body.count('chest')) await craft(ctx, 'chest', 1, signal)
  const at = await placeNear(ctx, 'chest', signal)
  memory.data.stash = { ...at, dimension: body.dimension }
  memory.event(`new stash chest at ${at.x} ${at.y} ${at.z}`)
  return body.open(at.x, at.y, at.z, signal)
}

async function stash (ctx, args, signal) {
  const { body } = ctx
  const c = await openStash(ctx, signal, true)
  const n = c.size - 36
  let moved = 0
  try {
    const kept = {}
    for (const i of body.items()) {
      check(signal)
      if (KEEP_ALL.test(i.item) || body.bestFood()?.item === i.item) continue
      const keep = Math.max(0, (KEEP_SOME[i.item] ?? 0) - (kept[i.item] ?? 0))
      kept[i.item] = (kept[i.item] ?? 0) + Math.min(keep, i.count)
      if (i.count - Math.min(keep, i.count) <= 0) continue
      await body.b.request('click', { slot: body.menuSlot(i.slot, n), mode: 'QUICK_MOVE' })
      moved += i.count
    }
  } finally {
    await body.closeContainer()
  }
  return `stashed ${moved} items`
}

async function withdraw (ctx, args, signal) {
  const { body } = ctx
  const want = clean(args.item)
  const c = await openStash(ctx, signal, false)
  try {
    const stacks = c.slots.filter(s => !s.playerInv && s.item === want)
    if (!stacks.length) throw new Error(`stash has no ${want}`)
    let left = Number(args.count) || Infinity
    for (const s of stacks) {
      if (left <= 0) break
      await body.b.request('click', { slot: s.slot, mode: 'QUICK_MOVE' })
      left -= s.count
    }
  } finally {
    await body.closeContainer()
  }
  return `took ${want} from the stash`
}

// ---------------------------------------------------------------- players

function allyEntity (ctx, name) {
  const allies = ctx.allies()
  const wanted = String(name ?? ctx.cfg.social.owner)
  if (!allies.includes(wanted.toLowerCase())) throw new Error(`${wanted} is not the owner, staff or a friend`)
  return ctx.body.players().find(p => p.name.toLowerCase() === wanted.toLowerCase())
}

async function followPlayer (ctx, args, signal) {
  const { body } = ctx
  const ent = allyEntity(ctx, args.name)
  if (!ent) throw new Error(`can't see ${args.name ?? 'you'}: tell me your coordinates`)
  const seconds = Math.max(10, Math.min(1800, Number(args.seconds) || 300))
  await body.baritone(`follow player ${ent.name}`)
  try {
    await wait(seconds * 1000, signal)
  } finally {
    await body.stop()
  }
  return `followed ${ent.name} for ${seconds}s`
}

/** Come to a player: walk over if visible, else travel to the coordinates they gave (nether for long trips). */
async function comeTo (ctx, args, signal) {
  const { body } = ctx
  const ent = allyEntity(ctx, args.name)
  if (ent) {
    await body.goto(ent.x, ent.z, ent.y, signal, 5 * 60e3).catch(e => { if (isAbort(e) || ent.dist > 6) throw e })
    return `next to ${ent.name}`
  }
  if (args.x == null || args.z == null) throw new Error(`can't see ${args.name ?? 'you'}: send your coordinates`)
  const target = { x: Number(args.x), z: Number(args.z), y: args.y == null ? undefined : Number(args.y) }
  return travelTo(ctx, target, signal)
}

async function give (ctx, args, signal) {
  const { body } = ctx
  const ent = allyEntity(ctx, args.name)
  if (!ent) throw new Error(`can't see ${args.name ?? 'you'}`)
  const want = clean(args.item)
  const stacks = body.items().filter(i => i.item === want || i.item.includes(want))
  if (!want || !stacks.length) throw new Error(`I don't have any ${want || 'such item'}`)
  if (ent.dist > 3) await body.goto(ent.x, ent.z, ent.y, signal, 3 * 60e3).catch(() => {})
  await body.b.request('lookAt', { x: ent.x, y: ent.y + 1.5, z: ent.z })
  let left = Math.max(1, Number(args.count) || 64)
  let given = 0
  for (const s of stacks) {
    if (left <= 0) break
    if (s.count <= left) {
      await body.b.request('click', { slot: body.menuSlot(s.slot), button: 1, mode: 'THROW' })
      given += s.count
      left -= s.count
    } else {
      for (let k = 0; k < left; k++) await body.b.request('click', { slot: body.menuSlot(s.slot), button: 0, mode: 'THROW' })
      given += left
      left = 0
    }
  }
  return `threw ${given} ${stacks[0].item} to ${ent.name}`
}

// ---------------------------------------------------------------- building

/** Build an uploaded .litematic at x y z (or where we stand). */
async function build (ctx, args, signal) {
  const { body, gameDir } = ctx
  const file = path.basename(String(args.file ?? ''))
  const full = path.join(gameDir, 'schematics', file)
  if (!file || !fs.existsSync(full)) throw new Error(`no schematic "${file}" (upload it in the Bot tab first)`)
  fs.writeFileSync(full, await normalizeLitematic(fs.readFileSync(full)))
  const info = await readLitematic(fs.readFileSync(full))
  const p = body.pos
  const origin = {
    x: args.x == null ? Math.floor(p.x) + 2 : Number(args.x),
    y: args.y == null ? Math.floor(p.y) : Number(args.y),
    z: args.z == null ? Math.floor(p.z) + 2 : Number(args.z)
  }
  if (hdist(origin, p) > 64) await travelTo(ctx, origin, signal)

  const creative = body.p.gameMode === 'creative'
  const missing = missingMaterials(info.materials, body.inventory())
  if (!creative && Object.keys(missing).length) {
    ctx.emit?.('build', { file, status: 'missing', missing })
    if (args.force !== true) {
      throw new Error(`missing materials: ${Object.entries(missing).slice(0, 8).map(([k, v]) => `${v} ${k}`).join(', ')}${Object.keys(missing).length > 8 ? '...' : ''}`)
    }
  }
  await body.baritone('set allowInventory true')
  // Double slabs and waterlogging can't be placed from items directly; accept single/dry versions.
  await body.baritone('set buildIgnoreProperties type,waterlogged')
  if (!(await body.b.request('build', { file, ...origin }))) throw new Error('Baritone could not load the schematic')
  ctx.emit?.('build', { file, status: 'building', origin, totalBlocks: info.totalBlocks })

  // Creative: Baritone only uses what's in the inventory, so hand it the materials in rounds of 9.
  const types = Object.keys(info.materials)
  let round = 0
  while (true) {
    if (creative) {
      const batch = types.slice((round % Math.ceil(types.length / 9)) * 9, (round % Math.ceil(types.length / 9)) * 9 + 9)
      for (let s = 0; s < batch.length; s++) await body.b.request('creativeGive', { item: batch[s], count: 64, slot: s }).catch(() => {})
      await body.baritone('resume').catch(() => {})
    }
    try {
      await body.waitBaritone(signal, { timeoutMs: 60 * 60e3, stuckMs: 0 })
    } catch (e) {
      if (isAbort(e)) throw e
      // Survival and Baritone ran out of something it can't place: report what's left and stop.
      if (!creative) {
        await body.baritone('cancel').catch(() => {})
        ctx.emit?.('build', { file, status: 'stuck', message: e.message })
        throw new Error(`built what it could; ${e.message.replace(/^Baritone: /, '')}. Give me those and run the build again to finish.`)
      }
    }
    round++
    if (!creative || round >= Math.ceil(types.length / 9) * 2) break
  }
  ctx.emit?.('build', { file, status: 'done', origin })
  return `finished building ${info.name} at ${origin.x} ${origin.y} ${origin.z}`
}

// ---------------------------------------------------------------- travel

// 2b2t highways run along the axes and diagonals; walk on a bearing 15-30 degrees off them.
function pickBearing () {
  const sector = Math.floor(Math.random() * 8)
  return (sector * 45 + 15 + Math.random() * 15) * Math.PI / 180
}

// Stranded far above everything (sky platform, griefed tower) with nothing to lose: respawn with /kill.
async function escapeIfStranded (ctx, signal) {
  const { body, cfg, memory } = ctx
  const p = body.pos
  const points = Array.from({ length: 8 }, (_, i) => ({ x: Math.floor(p.x + Math.cos(i * Math.PI / 4) * 10), z: Math.floor(p.z + Math.sin(i * Math.PI / 4) * 10) }))
  const ground = await body.b.request('groundBelow', { points, fromY: Math.floor(p.y) - 3, depth: 22 })
  if (ground.filter(g => g == null).length < 5) return null
  if (gearScore(body) >= 10) throw new Error('stranded high above the ground with valuable gear; not respawning')
  if (!cfg.behavior.killCommand) throw new Error('stranded high above the ground')
  memory.event(`stranded at ${Math.round(p.x)} ${Math.round(p.y)} ${Math.round(p.z)} with no gear: using ${cfg.behavior.killCommand}`)
  const died = new Promise(resolve => body.b.once('death', () => resolve(true)))
  await body.chat(cfg.behavior.killCommand)
  if (await Promise.race([died, wait(6000, signal).then(() => false)])) return `used ${cfg.behavior.killCommand} to respawn`
  return null
}

async function travelFar (ctx, args, signal) {
  const { body, memory, cfg } = ctx
  const escaped = await escapeIfStranded(ctx, signal)
  if (escaped) return escaped
  const m = memory.data
  const distance = cfg.behavior.settleDistance
  if (!m.travel || m.travel.distance !== distance) {
    m.travel = { angle: pickBearing(), distance }
    memory.save()
  }
  const p = body.pos
  let target
  if (!m.travel.done) {
    target = { x: Math.cos(m.travel.angle) * distance, z: Math.sin(m.travel.angle) * distance }
    if (hdist(target, p) < 48) {
      m.travel.done = true
      memory.save()
    }
  }
  if (m.travel.done) {
    // Settled: keep exploring in 300-block off-highway hops.
    if (!m.explore || hdist(m.explore, p) < 48) {
      const a = pickBearing()
      m.explore = { x: Math.round(p.x + Math.cos(a) * 300), z: Math.round(p.z + Math.sin(a) * 300) }
      memory.save()
    }
    target = m.explore
  }
  // Long legs go through the nether when possible; otherwise walk a 300-block leg so the brain can re-plan.
  const remaining = hdist(target, p)
  if (remaining > (cfg.behavior.netherTravelMin ?? 1000) && body.dimension === 'overworld') {
    try {
      return await travelTo(ctx, target, signal, { timeoutMs: 20 * 60e3 })
    } catch (e) {
      if (isAbort(e)) throw e
    }
  }
  const leg = Math.min(300, remaining)
  const a = Math.atan2(target.z - p.z, target.x - p.x)
  try {
    await body.goto(p.x + Math.cos(a) * leg, p.z + Math.sin(a) * leg, null, signal, 6 * 60e3)
  } catch (e) {
    if (isAbort(e)) throw e
    // Blocked (ocean, ravine...): swing the bearing a little for next time.
    if (!m.travel.done) { m.travel.angle += (Math.random() < 0.5 ? -1 : 1) * 0.3; memory.save() } else m.explore = null
    throw e
  }
  const now = body.pos
  return `moved ${Math.round(hdist(p, now))} blocks, now ${Math.round(Math.hypot(now.x, now.z))} from spawn`
}

// ---------------------------------------------------------------- registry

export const SKILLS = {
  travel_far: { desc: 'travel away from spawn along a secret off-highway bearing (uses the nether for long distances)', args: '{}', maxMs: 25 * 60e3, run: travelFar },
  goto: {
    desc: 'go to coordinates (uses the nether for long distances)',
    args: '{"x": number, "y": number|null, "z": number}',
    maxMs: 65 * 60e3,
    run: (ctx, a, s) => travelTo(ctx, { x: Number(a.x), z: Number(a.z), y: a.y == null ? undefined : Number(a.y) }, s)
  },
  gather_wood: { desc: 'chop nearby tree logs', args: '{"count": number}', maxMs: 5 * 60e3, run: (ctx, a, s) => mine(ctx, LOGS, Math.min(Number(a.count) || 8, 64), s) },
  mine: {
    desc: 'mine a block type nearby, e.g. stone, coal, iron, diamond, dirt, sand',
    args: '{"block": string, "count": number}',
    maxMs: 6 * 60e3,
    run: (ctx, a, s) => mine(ctx, blocksFor(a.block), Math.min(Number(a.count) || 8, 128), s, 5 * 60e3)
  },
  craft: { desc: 'craft an item (makes planks/sticks and uses or places a crafting table as needed)', args: '{"item": string, "count": number}', maxMs: 3 * 60e3, run: (ctx, a, s) => craft(ctx, a.item, a.count, s) },
  smelt: { desc: 'smelt/cook an item in a furnace (raw_iron, beef, oak_log for charcoal...)', args: '{"item": string, "count": number}', maxMs: 5 * 60e3, run: (ctx, a, s) => smelt(ctx, a.item, a.count, s) },
  hunt: { desc: 'kill nearby cows/pigs/sheep/chickens for food', args: '{"count": number}', maxMs: 3 * 60e3, run: hunt },
  eat: { desc: 'eat the best food now', args: '{}', maxMs: 10e3, run: (ctx, a, s) => ctx.body.eat(s) },
  sleep: { desc: 'sleep in a bed at night (also sets your respawn point, very valuable)', args: '{}', maxMs: 4 * 60e3, run: sleep },
  hide: { desc: 'dig into the ground and seal yourself in to wait out danger or the night', args: '{"seconds": number, "untilDay": boolean}', maxMs: 11 * 60e3, run: hide },
  strip_mine: { desc: 'tunnel deep underground for iron/diamonds/coal (needs a pickaxe)', args: '{"minutes": number}', maxMs: 16 * 60e3, run: stripMine },
  explore: {
    desc: 'explore new terrain nearby',
    args: '{"minutes": number}',
    maxMs: 11 * 60e3,
    run: async (ctx, a, s) => {
      await ctx.body.baritone('explore')
      await ctx.body.waitBaritone(s, { timeoutMs: Math.min(10, Number(a.minutes) || 3) * 60e3 }).catch(e => { if (!/timed out/.test(e.message)) throw e })
      return 'explored'
    }
  },
  stash: { desc: 'put non-essential items in your hidden stash chest (creates one far from spawn)', args: '{}', maxMs: 6 * 60e3, run: stash },
  withdraw: { desc: 'take items from your stash', args: '{"item": string, "count": number}', maxMs: 6 * 60e3, run: withdraw },
  build: {
    desc: 'build an uploaded Litematica schematic (file) at x y z, default next to you',
    args: '{"file": string, "x": number|null, "y": number|null, "z": number|null}',
    maxMs: 4 * 60 * 60e3,
    run: build
  },
  follow_player: { desc: 'follow your owner/staff around', args: '{"name": string, "seconds": number}', maxMs: 31 * 60e3, run: followPlayer },
  come_to: { desc: 'go to your owner/staff: walk over if visible, else travel to the coordinates they gave', args: '{"name": string, "x": number|null, "y": number|null, "z": number|null}', maxMs: 65 * 60e3, run: comeTo },
  give: { desc: 'walk to your owner/staff and throw them items', args: '{"item": string, "count": number, "name": string}', maxMs: 4 * 60e3, run: give },
  wait: { desc: 'stand still for a while', args: '{"seconds": number}', maxMs: 2 * 60e3, run: async (ctx, a, s) => { await wait(Math.min(Number(a.seconds) || 10, 110) * 1000, s); return 'waited' } }
}

export { craft, placeNear, tierOf }
