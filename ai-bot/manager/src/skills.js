import { Vec3 } from 'vec3'
import { goals, goto } from './nav.js'
import { check, wait, isAbort, items, count, find, hdist, bestTier, dimension, isNight } from './util.js'
import { fight, collectDrops, equipBestArmor, equipBestWeapon, gearScore, FOOD_ANIMALS } from './combat.js'
import { eat } from './reflexes.js'

// ---------------------------------------------------------------- blocks

const isLog = n => /_(log|stem)$/.test(n) && !n.startsWith('stripped_')

// Friendly names the LLM may use -> concrete block names.
function blockNamesFor (bot, what) {
  const w = String(what).toLowerCase().replace(/^minecraft:/, '').replace(/\s+/g, '_')
  const all = bot.registry.blocksArray.map(b => b.name)
  if (/^(wood|log|logs|tree)s?$/.test(w)) return all.filter(isLog)
  if (/^(stone|cobblestone|cobble|rock)$/.test(w)) return ['stone', 'cobblestone', 'deepslate', 'cobbled_deepslate']
  const base = w.replace(/^raw_/, '').replace(/_(ore|ingot)$/, '').replace(/^deepslate_/, '')
  if (['coal', 'iron', 'gold', 'diamond', 'copper', 'lapis', 'redstone', 'emerald'].includes(base)) {
    return [`${base}_ore`, `deepslate_${base}_ore`]
  }
  if (bot.registry.blocksByName[w]) return [w, `deepslate_${w}`].filter(n => bot.registry.blocksByName[n])
  return []
}

const idsOf = (bot, names) => names.map(n => bot.registry.blocksByName[n]?.id).filter(id => id != null)

function nearestBlock (bot, names, maxDistance = 32) {
  return bot.findBlock({ matching: idsOf(bot, names), maxDistance })
}

function harvestable (bot, block) {
  if (!block.harvestTools) return true
  return items(bot).some(i => block.harvestTools[i.type])
}

async function digAt (ctx, pos, signal) {
  const { bot } = ctx
  const block = bot.blockAt(pos)
  if (!block || block.boundingBox === 'empty') return false
  if (!harvestable(bot, block)) throw new Error(`need a better pickaxe to mine ${block.name}`)
  await goto(bot, new goals.GoalLookAtBlock(pos, bot.world, { reach: 4 }), signal, 40000)
  const b = bot.blockAt(pos)
  if (!b || b.type !== block.type) return false
  const tool = bot.pathfinder.bestHarvestTool(b)
  if (tool) await bot.equip(tool, 'hand')
  const stop = () => { try { bot.stopDigging() } catch {} }
  signal?.addEventListener('abort', stop, { once: true })
  try { await bot.dig(b, true) } finally { signal?.removeEventListener('abort', stop) }
  check(signal)
  return true
}

async function mineBlocks (ctx, names, want, signal, maxDistance = 48) {
  const { bot } = ctx
  const ids = idsOf(bot, names)
  if (!ids.length) throw new Error(`unknown block "${names}"`)
  const skip = new Set()
  let mined = 0
  let fails = 0
  let lastErr = ''
  while (mined < want) {
    check(signal)
    const me = bot.entity.position
    const pos = bot.findBlocks({ matching: ids, maxDistance, count: 64 })
      .filter(p => !skip.has(p.toString()))
      .sort((a, b) => a.distanceTo(me) - b.distanceTo(me))[0]
    if (!pos) break
    try {
      if (await digAt(ctx, pos, signal)) {
        mined++
        await collectDrops(bot, signal, 6, 4000)
      }
    } catch (e) {
      if (isAbort(e)) throw e
      if (/better pickaxe/.test(e.message)) throw e
      lastErr = e.message
      skip.add(pos.toString())
      if (++fails > 8) break
    }
  }
  if (!mined) throw new Error(`could not mine any ${names.slice(0, 3).join('/')} within ${maxDistance} blocks${lastErr ? ` (${lastErr})` : ''}`)
  return `mined ${mined} ${names.some(isLog) ? 'logs' : names[0]}`
}

// Place a block item on solid ground next to us; returns the placed block.
async function placeNear (ctx, itemName, signal) {
  const { bot } = ctx
  const item = find(bot, itemName)
  if (!item) throw new Error(`no ${itemName} to place`)
  const me = bot.entity.position
  const base = me.floored()
  const spots = []
  for (let dx = -2; dx <= 2; dx++) {
    for (let dz = -2; dz <= 2; dz++) {
      for (let dy = -1; dy <= 1; dy++) {
        const t = base.offset(dx, dy, dz)
        const overlapsMe = Math.abs(t.x + 0.5 - me.x) < 0.85 && Math.abs(t.z + 0.5 - me.z) < 0.85 && t.y >= base.y - 1 && t.y <= base.y + 1
        if (!overlapsMe) spots.push(t)
      }
    }
  }
  spots.sort((a, b) => a.distanceTo(me) - b.distanceTo(me))
  for (const t of spots) {
    check(signal)
    const below = bot.blockAt(t.offset(0, -1, 0))
    const at = bot.blockAt(t)
    if (!below || below.boundingBox !== 'block' || !at || at.boundingBox !== 'empty' || /water|lava/.test(at.name)) continue
    try {
      await bot.equip(item, 'hand')
      await bot.placeBlock(below, new Vec3(0, 1, 0))
      const placed = bot.blockAt(t)
      if (placed && placed.name !== 'air') return placed
    } catch {}
  }
  throw new Error(`no space to place ${itemName}`)
}

// ---------------------------------------------------------------- crafting

const itemName = (bot, id) => bot.registry.items[id]?.name
const RARE_VARIANT = /deepslate|blackstone|pale_oak|bamboo|crimson|warped|cherry|mangrove/
const RAW_HINT = {
  cobblestone: 'cobblestone (mine stone)',
  cobbled_deepslate: 'cobblestone (mine stone)',
  iron_ingot: 'iron_ingot (strip_mine for iron, then smelt raw_iron)',
  diamond: 'diamond (strip_mine)',
  white_wool: 'white_wool (hunt sheep)'
}
const rawHint = n => RAW_HINT[n] ?? (/_(log|stem|planks)$/.test(n) ? `${n} (gather_wood first)` : n)

async function obtainBlock (ctx, name, signal) {
  const { bot } = ctx
  const near = nearestBlock(bot, [name], 24)
  if (near) return near
  if (!find(bot, name)) await craft(ctx, name, 1, signal, 2)
  return placeNear(ctx, name, signal)
}

async function craft (ctx, name, want, signal, depth = 0) {
  const { bot } = ctx
  name = String(name).toLowerCase().replace(/^minecraft:/, '').replace(/\s+/g, '_')
  want = Math.max(1, Math.min(64, Number(want) || 1))
  const item = bot.registry.itemsByName[name]
  if (!item) throw new Error(`unknown item "${name}"`)
  const all = bot.recipesAll(item.id, null, true)
  if (!all.length) throw new Error(`${name} cannot be crafted`)

  const have = id => count(bot, i => i.type === id)
  // Choose the recipe variant we are closest to affording (e.g. oak vs birch planks).
  const choosePlan = () => all.map(r => {
    const times = Math.ceil(want / r.result.count)
    const missing = r.delta.filter(d => d.count < 0)
      .map(d => ({ id: d.id, total: -d.count * times, need: -d.count * times - have(d.id) }))
      .filter(m => m.need > 0)
    const cost = missing.reduce((s, m) => {
      const n = itemName(bot, m.id) ?? ''
      const easyPlanks = n.endsWith('_planks') && count(bot, n.replace('_planks', '_log')) + count(bot, n.replace('_planks', '_stem')) > 0
      return s + m.need * (easyPlanks ? 0.2 : 1) + (RARE_VARIANT.test(n) ? 0.01 : 0)
    }, 0)
    return { r, missing, cost }
  }).sort((a, b) => a.cost - b.cost)[0]

  // Get the table first so its planks don't eat the ingredients we gather below.
  let table = null
  if (choosePlan().r.requiresTable) {
    table = await obtainBlock(ctx, 'crafting_table', signal)
    await goto(bot, new goals.GoalNear(table.position.x, table.position.y, table.position.z, 2), signal, 30000)
  }

  // Several passes: one sub-craft can consume another's output (sticks eat planks).
  for (let pass = 0; pass < 3; pass++) {
    const missing = choosePlan().missing
    if (!missing.length) break
    for (const m of missing) {
      check(signal)
      const n = itemName(bot, m.id)
      const need = m.total - have(m.id) // earlier sub-crafts may already have produced some
      if (need <= 0) continue
      if (depth >= 3 || !bot.recipesAll(m.id, null, true).length) {
        throw new Error(`missing ${need} ${rawHint(n)} to craft ${name}`)
      }
      await craft(ctx, n, need, signal, depth + 1)
    }
  }

  if (table) await goto(bot, new goals.GoalNear(table.position.x, table.position.y, table.position.z, 2), signal, 30000)
  const recipe = bot.recipesFor(item.id, null, 1, table)[0]
  if (!recipe) throw new Error(`missing materials for ${name}`)
  const before = count(bot, name)
  const times = Math.ceil(want / recipe.result.count)
  for (let i = 0; i < times; i++) {
    check(signal)
    try { await bot.craft(recipe, 1, table) } catch (e) { if (i === 0) throw e; break }
  }
  return `crafted ${count(bot, name) - before} ${name}`
}

const FUELS = [['coal', 8], ['charcoal', 8], [/_planks$/, 1.5], [/_(log|stem)$/, 1.5], ['stick', 0.5]]

async function smelt (ctx, input, want, signal) {
  const { bot } = ctx
  input = String(input).toLowerCase().replace(/^minecraft:/, '')
  const src = find(bot, input)
  if (!src) throw new Error(`no ${input} in inventory`)
  want = Math.max(1, Math.min(16, Number(want) || 8, count(bot, input)))

  let fuel = null
  let fuelCount = 0
  for (const [pred, per] of FUELS) {
    const f = find(bot, i => (pred instanceof RegExp ? pred.test(i.name) : i.name === pred) && i.name !== input)
    if (f && count(bot, f.name) * per >= Math.min(want, 2)) { fuel = f; fuelCount = Math.min(count(bot, f.name), Math.ceil(want / per)); break }
  }
  if (!fuel) throw new Error('no fuel (coal, charcoal, planks or logs)')

  const block = await obtainBlock(ctx, 'furnace', signal)
  await goto(bot, new goals.GoalNear(block.position.x, block.position.y, block.position.z, 2), signal, 30000)
  const furnace = await bot.openFurnace(block)
  let got = 0
  try {
    if (!furnace.fuelItem()) await furnace.putFuel(fuel.type, null, fuelCount)
    await furnace.putInput(src.type, null, want)
    const t0 = Date.now()
    while (got < want && Date.now() - t0 < want * 11000 + 5000) {
      await wait(2000, signal)
      if (furnace.outputItem()) got += (await furnace.takeOutput())?.count ?? 0
    }
  } finally {
    try { if (furnace.inputItem()) await furnace.takeInput() } catch {}
    furnace.close()
  }
  return `smelted ${got} ${input}`
}

// ---------------------------------------------------------------- travel

// Stranded far above everything (sky platform, griefed tower): around us, nothing solid from
// 3 to 24 blocks below our feet. The platform we stand on (y-1) doesn't count as ground.
function stranded (bot) {
  const p = bot.entity.position.floored()
  let empty = 0
  for (let i = 0; i < 8; i++) {
    const a = i * Math.PI / 4
    const x = p.x + Math.round(Math.cos(a) * 10)
    const z = p.z + Math.round(Math.sin(a) * 10)
    let ground = false
    for (let dy = -3; dy >= -24; dy--) {
      const b = bot.blockAt(new Vec3(x, p.y + dy, z))
      if (!b || b.boundingBox === 'block' || /water|lava/.test(b.name)) { ground = true; break }
    }
    if (!ground) empty++
  }
  return empty >= 5
}

// With nothing worth keeping, the quickest way down is to step off the edge: we respawn on the
// ground at world spawn.
async function dropOffEdge (ctx, signal) {
  const { bot, memory } = ctx
  const p = bot.entity.position.floored()
  const isAir = v => bot.blockAt(v)?.name === 'air'
  const standable = v => bot.blockAt(v.offset(0, -1, 0))?.boundingBox === 'block' && isAir(v) && isAir(v.offset(0, 1, 0))
  for (let r = 1; r <= 8; r++) {
    for (let dx = -r; dx <= r; dx++) {
      for (let dz = -r; dz <= r; dz++) {
        if (Math.max(Math.abs(dx), Math.abs(dz)) !== r) continue
        const drop = p.offset(dx, 0, dz)
        if (![0, 1, -1, -2, -3, -4].every(dy => isAir(drop.offset(0, dy, 0)))) continue
        const edge = [[1, 0], [-1, 0], [0, 1], [0, -1]].map(([ex, ez]) => drop.offset(ex, 0, ez)).find(standable)
        if (!edge) continue
        memory.event(`stranded at ${p} with no gear: stepping off the edge to respawn`)
        await goto(bot, new goals.GoalBlock(edge.x, edge.y, edge.z), signal, 30000)
        await bot.lookAt(drop.offset(0.5, 1, 0.5), true)
        bot.setControlState('forward', true)
        await wait(1500, signal).finally(() => bot.setControlState('forward', false))
        return 'stepped off the edge'
      }
    }
  }
  throw new Error('stranded high up but found no edge to drop from')
}

// On the build-limit roof (y >= world top) the pathfinder can't plan at all, so walk by hand
// toward the heading until we drop off the roof or stop making progress.
const atWorldTop = bot => bot.entity.position.y >= (bot.game.minY ?? 0) + (bot.game.height ?? 256) - 1

async function walkManually (bot, heading, ms, signal) {
  const start = bot.entity.position.clone()
  let last = start.clone()
  let stuckSince = Date.now()
  const t0 = Date.now()
  try {
    while (Date.now() - t0 < ms) {
      const p = bot.entity.position
      await bot.lookAt(new Vec3(p.x + Math.cos(heading) * 20, p.y + 1.6, p.z + Math.sin(heading) * 20), true)
      bot.setControlState('forward', true)
      bot.setControlState('sprint', bot.food > 6)
      await wait(250, signal)
      if (bot.entity.position.y < start.y - 2) break // dropped off the roof
      if (bot.entity.position.distanceTo(last) > 0.5) { last = bot.entity.position.clone(); stuckSince = Date.now() }
      bot.setControlState('jump', Date.now() - stuckSince > 1000)
      if (Date.now() - stuckSince > 5000) heading += (Math.random() - 0.5) * 1.5
    }
  } finally {
    bot.clearControlStates()
  }
  return hdist(start, bot.entity.position)
}

async function escapeIfStranded (ctx, signal) {
  const { bot, cfg, memory } = ctx
  if (!stranded(bot)) return null
  if (gearScore(bot) >= 10) throw new Error('stranded high above the ground with valuable gear; not respawning')
  // Many anarchy servers (9b9t...) allow /kill: the cleanest way to respawn on the ground.
  const cmd = cfg.behavior.killCommand
  if (cmd) {
    memory.event(`stranded at ${bot.entity.position.floored()} with no gear: using ${cmd} to respawn`)
    const died = new Promise(resolve => bot.once('death', () => resolve(true)))
    bot.chat(cmd)
    if (await Promise.race([died, wait(6000, signal).then(() => false)])) return `used ${cmd} to respawn`
  }
  return dropOffEdge(ctx, signal)
}

// 2b2t highways run along the axes and diagonals; walk on a bearing 15-30 degrees off them.
function pickBearing () {
  const sector = Math.floor(Math.random() * 8)
  return (sector * 45 + 15 + Math.random() * 15) * Math.PI / 180
}

async function travelFar (ctx, args, signal) {
  const { bot, memory, cfg } = ctx
  const m = memory.data
  const distance = cfg.behavior.settleDistance
  if (!m.travel || m.travel.distance !== distance) {
    m.travel = { angle: pickBearing(), distance, fails: 0 }
    memory.save()
  }
  // Once the far target is reached, keep exploring in 300-block off-highway hops.
  const target = () => {
    if (!m.travel.done) return { x: Math.cos(m.travel.angle) * distance, z: Math.sin(m.travel.angle) * distance }
    if (!m.explore) {
      const a = pickBearing()
      const p = bot.entity.position
      m.explore = { x: Math.round(p.x + Math.cos(a) * 300), z: Math.round(p.z + Math.sin(a) * 300) }
      memory.save()
    }
    return m.explore
  }
  const escaped = await escapeIfStranded(ctx, signal)
  if (escaped) return escaped
  const leg = Number(args.blocks) || 200
  const start = bot.entity.position.clone()
  const t0 = Date.now()
  let fails = 0
  while (hdist(start, bot.entity.position) < leg && Date.now() - t0 < 5 * 60e3) {
    check(signal)
    const p = bot.entity.position
    const { x: tx, z: tz } = target()
    const remaining = Math.hypot(tx - p.x, tz - p.z)
    if (remaining < 48) {
      if (m.travel.done) m.explore = null
      m.travel.done = true
      memory.save()
      return `arrived at (${Math.round(tx)}, ${Math.round(tz)}), ${Math.round(Math.hypot(p.x, p.z))} from spawn`
    }
    const heading = Math.atan2(tz - p.z, tx - p.x) + (fails ? (Math.random() - 0.5) * fails * 0.6 : 0)
    const step = 40
    if (atWorldTop(bot)) {
      await walkManually(bot, heading, 60000, signal)
      continue
    }
    try {
      await goto(bot, new goals.GoalNearXZ(p.x + Math.cos(heading) * step, p.z + Math.sin(heading) * step, 3), signal, 60000)
      fails = 0
    } catch (e) {
      if (isAbort(e)) throw e
      if (++fails >= 6) {
        // Blocked (ocean, ravine...): permanently swing the bearing.
        if (m.travel.done) m.explore = null
        else m.travel.angle += (Math.random() < 0.5 ? -1 : 1) * 0.3
        memory.save()
        fails = 0
      }
    }
    m.lastPos = { x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) }
  }
  if (hdist(start, bot.entity.position) < 5) {
    const escaped = await escapeIfStranded(ctx, signal)
    if (escaped) return escaped
  }
  const p = bot.entity.position
  return `moved ${Math.round(hdist(start, p))} blocks, now ${Math.round(Math.hypot(p.x, p.z))} from spawn`
}

// ---------------------------------------------------------------- survival

async function hunt (ctx, args, signal) {
  const { bot } = ctx
  const want = Math.max(1, Math.min(6, Number(args.count) || 3))
  let killed = 0
  while (killed < want) {
    check(signal)
    const target = bot.nearestEntity(e => FOOD_ANIMALS.has(e.name) && e.position.distanceTo(bot.entity.position) < 48)
    if (!target) break
    const r = await fight(bot, target, signal, 30000)
    if (r === 'target gone') killed++
    await collectDrops(bot, signal, 8, 4000)
  }
  if (!killed) throw new Error('no animals nearby')
  return `killed ${killed} animals`
}

async function sleepInBed (ctx, args, signal) {
  const { bot, memory } = ctx
  if (dimension(bot) !== 'overworld') throw new Error('beds explode outside the overworld')
  if (!isNight(bot) && !bot.thunderState) throw new Error('can only sleep at night')
  let bed = bot.findBlock({ matching: b => b.name.endsWith('_bed'), maxDistance: 24 })
  if (!bed) {
    const item = find(bot, /_bed$/)
    if (!item) throw new Error('no bed')
    bed = await placeNear(ctx, item.name, signal)
  }
  await goto(bot, new goals.GoalNear(bed.position.x, bed.position.y, bed.position.z, 2), signal, 30000)
  await bot.sleep(bed)
  memory.data.spawnBed = { x: bed.position.x, y: bed.position.y, z: bed.position.z }
  memory.event(`respawn point set at bed ${bed.position}`)
  while (bot.isSleeping) await wait(1000, signal).catch(e => { bot.wake().catch(() => {}); throw e })
  return 'slept through the night'
}

const COVER_BLOCKS = /^(cobblestone|cobbled_deepslate|dirt|netherrack|stone|deepslate|.*_planks)$/

// Dig a 2-deep hole and seal it: a cheap way to survive nights without a bed.
async function hide (ctx, args, signal) {
  const { bot } = ctx
  const seconds = Math.max(10, Math.min(600, Number(args.seconds) || 120))
  const safeHere = () => {
    const p = bot.entity.position.floored()
    for (let i = 1; i <= 4; i++) {
      const b = bot.blockAt(p.offset(0, -i, 0))
      if (!b || /lava|water|bedrock|leaves/.test(b.name) || (i <= 3 && b.boundingBox !== 'block')) return false
    }
    return true
  }
  // Look around a little for solid ground before giving up.
  for (let tries = 0; !safeHere(); tries++) {
    if (tries >= 5) throw new Error('no safe ground to dig into here')
    const me = bot.entity.position
    const a = Math.random() * Math.PI * 2
    await goto(bot, new goals.GoalNearXZ(me.x + Math.cos(a) * 6, me.z + Math.sin(a) * 6, 1), signal, 15000).catch(e => { if (isAbort(e)) throw e })
  }
  const p = bot.entity.position.floored()
  await equipBestWeapon(bot)
  for (let i = 1; i <= 2; i++) {
    const b = bot.blockAt(p.offset(0, -i, 0))
    const tool = bot.pathfinder.bestHarvestTool(b)
    if (tool) await bot.equip(tool, 'hand')
    await bot.dig(b, true)
    await wait(700, signal)
  }
  const cover = find(bot, i => COVER_BLOCKS.test(i.name))
  if (cover) {
    for (const off of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const ref = bot.blockAt(p.offset(off[0], 0, off[1]))
      if (ref?.boundingBox !== 'block') continue
      try {
        await bot.equip(cover, 'hand')
        await bot.placeBlock(ref, new Vec3(-off[0], 0, -off[1]))
        break
      } catch {}
    }
  }
  const t0 = Date.now()
  while (Date.now() - t0 < seconds * 1000) {
    await wait(5000, signal)
    if (args.untilDay && !isNight(bot)) break
  }
  return `hid for ${Math.round((Date.now() - t0) / 1000)}s`
}

const ORE_NAMES = ['diamond', 'iron', 'gold', 'coal', 'lapis', 'emerald', 'redstone', 'copper']
  .flatMap(o => [`${o}_ore`, `deepslate_${o}_ore`])

async function stripMine (ctx, args, signal) {
  const { bot, memory } = ctx
  if (dimension(bot) !== 'overworld') throw new Error('strip mining only supported in the overworld')
  if (bestTier(bot, 'pickaxe') < 1) throw new Error('need a pickaxe')
  const minutes = Math.max(1, Math.min(15, Number(args.minutes) || 6))
  // Below y=-54 caves are flooded with lava; -53 still has good diamond rates.
  const targetY = (bot.game.minY ?? 0) < 0 ? -53 : 12
  const t0 = Date.now()
  const end = t0 + minutes * 60e3

  if (bot.entity.position.y > targetY + 2) {
    await goto(bot, new goals.GoalY(targetY), signal, Math.min(end - Date.now(), 5 * 60e3))
  }

  const dirs = [[1, 0], [0, 1], [-1, 0], [0, -1]]
  let d = Math.floor(Math.random() * 4)
  let lastTorch = bot.entity.position.clone()
  let fails = 0
  let ores = 0
  const oreIds = idsOf(bot, ORE_NAMES)
  while (Date.now() < end) {
    check(signal)
    if (bot.inventory.emptySlotCount() < 2) return `inventory full after mining ${ores} ores (stash items)`
    if (bestTier(bot, 'pickaxe') < 1) return `pickaxe broke after ${ores} ores (craft a new one)`
    const p = bot.entity.position.floored()
    try {
      await goto(bot, new goals.GoalBlock(p.x + dirs[d][0] * 6, targetY, p.z + dirs[d][1] * 6), signal, 60000)
      fails = 0
    } catch (e) {
      if (isAbort(e)) throw e
      d = (d + 1 + Math.floor(Math.random() * 3)) % 4
      if (++fails > 5) break
    }
    for (const pos of bot.findBlocks({ matching: oreIds, maxDistance: 5, count: 8 })) {
      const b = bot.blockAt(pos)
      if (!b) continue
      if (b.name === 'coal_ore' && count(bot, 'coal') > 48) continue
      try {
        if (await digAt(ctx, pos, signal)) { ores++; await collectDrops(bot, signal, 5, 2500) }
      } catch (e) {
        if (isAbort(e)) throw e
        if (/better pickaxe/.test(e.message)) memory.note(`${b.name} at ${pos} needs a better pickaxe`)
      }
    }
    const torch = find(bot, 'torch')
    if (torch && bot.entity.position.distanceTo(lastTorch) > 10) {
      const floor = bot.blockAt(bot.entity.position.floored().offset(-dirs[d][0], -1, -dirs[d][1]))
      if (floor?.boundingBox === 'block') {
        try { await bot.equip(torch, 'hand'); await bot.placeBlock(floor, new Vec3(0, 1, 0)); lastTorch = bot.entity.position.clone() } catch {}
      }
    }
  }
  return `strip mined at y=${targetY} for ${Math.round((Date.now() - t0) / 60000)} min, ${ores} ores`
}

// ---------------------------------------------------------------- stash

const KEEP_ALL = /_(pickaxe|axe|shovel|sword|helmet|chestplate|leggings|boots|bed)$|^(shield|totem_of_undying|bow|crossbow|arrow|elytra|ender_pearl|water_bucket|flint_and_steel|golden_apple|enchanted_golden_apple|crafting_table)$/
const KEEP_SOME = { cobblestone: 64, cobbled_deepslate: 32, dirt: 16, torch: 32, coal: 16, stick: 8, iron_ingot: 8 }

async function openStash (ctx, signal, create) {
  const { bot, memory, cfg } = ctx
  const watcher = Object.values(bot.players).find(p => p.entity && p.username !== bot.username &&
    !['owner', 'friend'].includes(ctx.social?.relation(p.username)) && p.entity.position.distanceTo(bot.entity.position) < 64)
  if (watcher) throw new Error(`${watcher.username} is nearby, not revealing the stash`)
  const s = memory.data.stash
  if (s && s.dimension === dimension(bot) && hdist(s, bot.entity.position) < 400) {
    await goto(bot, new goals.GoalNear(s.x, s.y, s.z, 2), signal, 4 * 60e3)
    const block = bot.blockAt(new Vec3(s.x, s.y, s.z))
    if (block?.name === 'chest') return { block, container: await bot.openContainer(block) }
    memory.event(`stash at ${s.x} ${s.y} ${s.z} is gone (raided?)`)
    memory.data.stash = null
  }
  if (!create) throw new Error('no stash nearby')
  const p = bot.entity.position
  if (Math.hypot(p.x, p.z) < cfg.behavior.settleDistance * 0.8) throw new Error('too close to spawn to make a stash')
  if (!find(bot, 'chest')) await craft(ctx, 'chest', 1, signal)
  const block = await placeNear(ctx, 'chest', signal)
  memory.data.stash = { x: block.position.x, y: block.position.y, z: block.position.z, dimension: dimension(bot) }
  memory.event(`new stash chest at ${block.position}`)
  return { block, container: await bot.openContainer(block) }
}

async function stash (ctx, args, signal) {
  const { bot } = ctx
  const { container } = await openStash(ctx, signal, true)
  let moved = 0
  try {
    const kept = {}
    for (const i of items(bot)) {
      check(signal)
      if (KEEP_ALL.test(i.name) || bot.registry.foodsByName[i.name]) continue
      const keep = Math.max(0, (KEEP_SOME[i.name] ?? 0) - (kept[i.name] ?? 0))
      kept[i.name] = (kept[i.name] ?? 0) + Math.min(keep, i.count)
      const n = i.count - Math.min(keep, i.count)
      if (n <= 0) continue
      try { await container.deposit(i.type, null, n); moved += n } catch { break }
    }
  } finally { container.close() }
  return `stashed ${moved} items`
}

async function withdraw (ctx, args, signal) {
  const { bot } = ctx
  const item = bot.registry.itemsByName[String(args.item)]
  if (!item) throw new Error(`unknown item ${args.item}`)
  const { container } = await openStash(ctx, signal, false)
  try {
    const have = container.containerItems().filter(i => i.type === item.id).reduce((s, i) => s + i.count, 0)
    if (!have) throw new Error(`stash has no ${item.name}`)
    await container.withdraw(item.id, null, Math.min(have, Number(args.count) || have))
  } finally { container.close() }
  return `took ${item.name} from stash`
}

// ---------------------------------------------------------------- owner / friends

// Only ever walk up to or hand things to the owner and configured friends.
function allyEntity (ctx, name) {
  const { bot, cfg } = ctx
  const allies = [cfg.social.owner, ...cfg.behavior.friendlyPlayers].map(n => n.toLowerCase())
  const wanted = String(name ?? cfg.social.owner)
  if (!allies.includes(wanted.toLowerCase())) throw new Error(`${wanted} is not the owner or a friend`)
  const player = Object.values(bot.players).find(p => p.username.toLowerCase() === wanted.toLowerCase())
  if (!player?.entity) throw new Error(`can't see ${wanted} (too far away?)`)
  return player.entity
}

async function followPlayer (ctx, args, signal) {
  const { bot } = ctx
  const ent = allyEntity(ctx, args.name)
  const seconds = Math.max(10, Math.min(1800, Number(args.seconds) || 300))
  bot.pathfinder.setGoal(new goals.GoalFollow(ent, 3), true)
  const t0 = Date.now()
  try {
    while (Date.now() - t0 < seconds * 1000) {
      await wait(1000, signal)
      if (!bot.entities[ent.id]) return `lost sight of ${ent.username}`
      if (!bot.pathfinder.goal) bot.pathfinder.setGoal(new goals.GoalFollow(ent, 3), true)
    }
  } finally { bot.pathfinder.setGoal(null) }
  return `followed ${ent.username} for ${seconds}s`
}

async function comeTo (ctx, args, signal) {
  const ent = allyEntity(ctx, args.name)
  const p = ent.position
  await goto(ctx.bot, new goals.GoalNear(p.x, p.y, p.z, 2), signal, 5 * 60e3)
  return `next to ${ent.username}`
}

async function give (ctx, args, signal) {
  const { bot } = ctx
  const ent = allyEntity(ctx, args.name)
  const want = String(args.item ?? '').toLowerCase().replace(/^minecraft:/, '').replace(/\s+/g, '_')
  const match = items(bot).filter(i => i.name === want || i.name.includes(want))
  if (!want || !match.length) throw new Error(`I don't have any ${want || 'such item'}`)
  await goto(bot, new goals.GoalNear(ent.position.x, ent.position.y, ent.position.z, 2), signal, 3 * 60e3)
  await bot.lookAt(ent.position.offset(0, 1.5, 0), true)
  let left = Math.max(1, Number(args.count) || 64)
  let given = 0
  for (const i of match) {
    if (left <= 0) break
    const n = Math.min(left, i.count)
    await bot.toss(i.type, null, n)
    given += n
    left -= n
  }
  return `threw ${given} ${match[0].name} to ${ent.username}`
}

// ---------------------------------------------------------------- registry

export const SKILLS = {
  travel_far: {
    desc: 'walk ~200 blocks further along a secret off-highway bearing away from spawn',
    args: '{}',
    maxMs: 6 * 60e3,
    run: travelFar
  },
  gather_wood: {
    desc: 'chop nearby tree logs',
    args: '{"count": number}',
    maxMs: 4 * 60e3,
    run: (ctx, a, s) => mineBlocks(ctx, blockNamesFor(ctx.bot, 'log'), Math.min(Number(a.count) || 8, 32), s, 48)
  },
  mine: {
    desc: 'mine visible blocks of a type, e.g. stone, coal, iron, diamond, dirt, sand',
    args: '{"block": string, "count": number}',
    maxMs: 4 * 60e3,
    run: (ctx, a, s) => {
      const names = blockNamesFor(ctx.bot, a.block)
      if (!names.length) throw new Error(`unknown block "${a.block}"`)
      return mineBlocks(ctx, names, Math.min(Number(a.count) || 8, 64), s, 32)
    }
  },
  craft: {
    desc: 'craft an item (auto-crafts planks/sticks/crafting_table as needed)',
    args: '{"item": string, "count": number}',
    maxMs: 2 * 60e3,
    run: (ctx, a, s) => craft(ctx, a.item, a.count, s)
  },
  smelt: {
    desc: 'smelt/cook an inventory item in a furnace (raw_iron, raw_beef, oak_log for charcoal...)',
    args: '{"item": string, "count": number}',
    maxMs: 4 * 60e3,
    run: (ctx, a, s) => smelt(ctx, a.item, a.count, s)
  },
  hunt: {
    desc: 'kill nearby cows/pigs/sheep/chickens for food',
    args: '{"count": number}',
    maxMs: 3 * 60e3,
    run: hunt
  },
  eat: { desc: 'eat the best food now', args: '{}', maxMs: 10e3, run: (ctx, a, s) => eat(ctx.bot, s) },
  sleep: {
    desc: 'sleep in a bed at night (also sets your respawn point, very valuable)',
    args: '{}',
    maxMs: 3 * 60e3,
    run: sleepInBed
  },
  hide: {
    desc: 'dig into the ground and seal yourself in to wait out danger or the night',
    args: '{"seconds": number, "untilDay": boolean}',
    maxMs: 11 * 60e3,
    run: hide
  },
  strip_mine: {
    desc: 'go deep underground and tunnel for iron/diamonds/coal (needs a pickaxe)',
    args: '{"minutes": number}',
    maxMs: 16 * 60e3,
    run: stripMine
  },
  stash: {
    desc: 'deposit non-essential items into your hidden stash chest (creates one if far from spawn)',
    args: '{}',
    maxMs: 5 * 60e3,
    run: stash
  },
  withdraw: {
    desc: 'take items from your stash',
    args: '{"item": string, "count": number}',
    maxMs: 5 * 60e3,
    run: withdraw
  },
  goto: {
    desc: 'walk to coordinates',
    args: '{"x": number, "y": number|null, "z": number}',
    maxMs: 6 * 60e3,
    run: async (ctx, a, s) => {
      const g = a.y == null ? new goals.GoalNearXZ(Number(a.x), Number(a.z), 2) : new goals.GoalNear(Number(a.x), Number(a.y), Number(a.z), 2)
      await goto(ctx.bot, g, s, 5.5 * 60e3)
      return 'arrived'
    }
  },
  collect_items: {
    desc: 'pick up dropped items nearby',
    args: '{}',
    maxMs: 30e3,
    run: async (ctx, a, s) => `picked up ${await collectDrops(ctx.bot, s, 16, 20000)} stacks`
  },
  equip_best: {
    desc: 'put on best armor and hold best weapon',
    args: '{}',
    maxMs: 15e3,
    run: async (ctx) => { await equipBestArmor(ctx.bot); await equipBestWeapon(ctx.bot); return 'equipped' }
  },
  follow_player: {
    desc: 'follow your owner around (only works for owner/friends)',
    args: '{"name": string, "seconds": number}',
    maxMs: 31 * 60e3,
    run: followPlayer
  },
  come_to: {
    desc: 'walk to your owner (only works for owner/friends)',
    args: '{"name": string}',
    maxMs: 6 * 60e3,
    run: comeTo
  },
  give: {
    desc: 'walk to your owner and throw them items (only works for owner/friends)',
    args: '{"item": string, "count": number, "name": string}',
    maxMs: 4 * 60e3,
    run: give
  },
  wait: {
    desc: 'stand still for a while',
    args: '{"seconds": number}',
    maxMs: 2 * 60e3,
    run: async (ctx, a, s) => { await wait(Math.min(Number(a.seconds) || 10, 110) * 1000, s); return 'waited' }
  }
}

export { craft, mineBlocks, placeNear }
