// "Settle": a crowd founds a settlement around a camp, the plain survival way. Each settler gets a plot on a
// ring around the camp, punches trees for logs (by hand, like a new player), crafts planks in its inventory
// and builds itself a hut, block by block. After that its job (from its personality) takes over: scouts
// explore and report back, keepers stay by the camp, entertainers keep everyone company, builders put up a
// lookout tower. At night everyone with a hut goes inside.
//
// It only ever breaks logs (and leaves, to get through forests) within SETTLE_RADIUS of the camp, and only
// places planks inside its own plot.
import pathfinderPkg from 'mineflayer-pathfinder'
import vec3 from 'vec3'
import { likes, dislikes, line } from '../agent/personality.js'

const { goals } = pathfinderPkg
const { Vec3 } = vec3

export const SETTLE_RADIUS = 48
const HUT = 5            // a 5x5 hut: 2-high walls with a doorway, a flat plank roof on top (~55 planks, 14 logs)
const WALL = 2
const REACH = 4.2
const FACES = [new Vec3(0, -1, 0), new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1), new Vec3(0, 1, 0)]

const rand = (a, b) => a + Math.random() * (b - a)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

/** What a settler does with its time once its hut is up, from its personality. */
export function settlerJob (p) {
  if (likes(p, 'build')) return 'builder'
  if (likes(p, 'chat')) return 'entertainer'
  if (likes(p, 'explore')) return 'scout'
  if (likes(p, 'stay') || likes(p, 'follow') || dislikes(p, 'explore')) return 'keeper'
  return 'builder'
}

/** Plot slots on rings around the camp: 8 at 11 blocks, 12 at 19, 16 at 27. */
function slot (i) {
  const rings = [[11, 8], [19, 12], [27, 16]]
  for (const [r, n] of rings) {
    if (i < n) {
      const a = (i + 0.5) * 2 * Math.PI / n
      return { dx: Math.round(Math.cos(a) * r), dz: Math.round(Math.sin(a) * r) }
    }
    i -= n
  }
  return null
}

/** The hut's blocks in build order (walls bottom up, then the roof from the edges in), door toward the camp. */
function hutCells (plot) {
  const cells = []
  const m = HUT - 1
  const door = Math.floor(HUT / 2)
  for (let y = 0; y < WALL; y++) {
    for (let x = 0; x < HUT; x++) {
      for (let z = 0; z < HUT; z++) {
        const edge = x === 0 || x === m || z === 0 || z === m
        if (!edge || (z === 0 && x === door)) continue
        cells.push([x, y, z])
      }
    }
  }
  for (let ring = 0; ring <= door; ring++) {
    for (let x = 0; x < HUT; x++) {
      for (let z = 0; z < HUT; z++) {
        if (Math.min(x, z, m - x, m - z) === ring) cells.push([x, WALL, z])
      }
    }
  }
  // Turn so the doorway (local z = 0 side) faces the camp: rot 0..3 = north, east, south, west.
  return cells.map(([x, y, z]) => {
    let lx = x
    let lz = z
    if (plot.rot === 1) { lx = m - z; lz = x } else if (plot.rot === 2) { lx = m - x; lz = m - z } else if (plot.rot === 3) { lx = z; lz = m - x }
    return new Vec3(plot.x + lx, plot.y + y, plot.z + lz)
  })
}

const isLog = b => b && b.name.endsWith('_log') && !b.name.startsWith('stripped_')

// Servers stop non-ops from breaking or placing blocks near the world spawn (16 blocks on Paper): settlers keep
// out of that area, and remember any other spot where the server undid what they did.
const SPAWN_PROTECTION = 18
const protectedSpot = (bot, p) => bot.spawnPoint && Math.max(Math.abs(p.x - bot.spawnPoint.x), Math.abs(p.z - bot.spawnPoint.z)) <= SPAWN_PROTECTION
const refused = new Set()
const key = p => `${p.x},${p.y},${p.z}`
const isPlanks = name => name.endsWith('_planks')
const count = (bot, test) => bot.inventory.items().filter(i => test(i.name)).reduce((n, i) => n + i.count, 0)
const solid = b => b && b.boundingBox === 'block'

/**
 * Find a plot for a settler: its own slot on the rings, a few blocks off it, or any other free slot, where the
 * 5x5 ground is level, dry and open (no trees on it: forests are no place for a client bot to build). Returns
 * {x, y, z, rot} (corner and floor level) or null.
 */
function findPlot (bot, centre, index, taken) {
  for (let k = 0; k < 36; k++) {
    const p = plotAt(bot, centre, (index + k) % 36, taken)
    if (p) return p
  }
  return null
}

function plotAt (bot, centre, index, taken) {
  const s = slot(index)
  if (!s) return null
  for (const [nx, nz] of [[0, 0], [2, 0], [-2, 0], [0, 2], [0, -2], [3, 3], [-3, -3], [3, -3], [-3, 3], [5, 0], [-5, 0], [0, 5], [0, -5]]) {
    const cx = Math.floor(centre.x) + s.dx + nx
    const cz = Math.floor(centre.z) + s.dz + nz
    const x0 = cx - 2
    const z0 = cz - 2
    if (taken.some(t => Math.abs(t.x - x0) < HUT + 2 && Math.abs(t.z - z0) < HUT + 2)) continue
    if (protectedSpot(bot, { x: x0, z: z0 }) || protectedSpot(bot, { x: x0 + HUT, z: z0 + HUT })) continue
    // Level ground (one block of slope is filled in with planks), not water. Trees on it get cleared.
    let lo = Infinity
    let hi = -Infinity
    let ok = true
    for (let x = 0; x < HUT && ok; x++) {
      for (let z = 0; z < HUT && ok; z++) {
        const g = ground(bot, x0 + x, z0 + z, Math.floor(centre.y))
        if (!g || g.water) ok = false
        else {
          lo = Math.min(lo, g.y)
          hi = Math.max(hi, g.y)
        }
      }
    }
    if (!ok || hi - lo > 1) continue
    const groundY = hi
    // Open sky over it: no trunks or leaves in the hut's space.
    for (let x = 0; x < HUT && ok; x++) {
      for (let z = 0; z < HUT && ok; z++) {
        for (let y = 1; y <= 6 && ok; y++) if (needsClearing(bot.blockAt(new Vec3(x0 + x, groundY + y, z0 + z)))) ok = false
      }
    }
    if (!ok) continue
    const dx = centre.x - cx
    const dz = centre.z - cz
    const rot = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? 1 : 3) : (dz > 0 ? 2 : 0)
    return { x: x0, y: groundY + 1, z: z0, rot }
  }
  return null
}

/** The ground in a column near height y, looking through trees and plants: {y} or {water: true}, null if unloaded. */
function ground (bot, x, z, y) {
  for (let yy = y + 16; yy >= y - 16; yy--) {
    const b = bot.blockAt(new Vec3(x, yy, z))
    if (!b) return null
    if (b.name === 'water' || b.name === 'lava') return { water: true }
    if (b.boundingBox !== 'block' || b.name.endsWith('_leaves') || isLog(b)) continue
    return { y: yy }
  }
  return null
}

const needsClearing = b => b && (isLog(b) || b.name.endsWith('_leaves'))
const free = b => b && (b.name === 'air' || b.name === 'cave_air' || (b.boundingBox === 'empty' && b.name !== 'water' && b.name !== 'lava'))

/**
 * One step of settler life. Runs at most one errand at a time (m.errand); each errand is a promise that walks,
 * chops, crafts or builds a little, then returns so think() can check orders and the time of day.
 */
export function settleStep (m, ctx) {
  const bot = m.bot
  if (!bot?.entity || m.errand) return
  const centre = ctx.centre()
  if (!centre) return
  m.errand = (async () => {
    try {
      await step(m, bot, centre, ctx)
    } catch (e) {
      debug(bot, 'error', e.message)
      m.doing = `${m.doing.split(' (')[0]} (${String(e.message).slice(0, 60)})`
      bot.pathfinder?.setGoal(null)
      await sleep(2000)
    } finally {
      m.errand = null
    }
  })()
}

async function step (m, bot, centre, ctx) {
  // Up on a tree canopy (paths sometimes lead over the treetops): break the leaves underfoot to get down.
  const under = bot.blockAt(bot.entity.position.offset(0, -0.5, 0).floored())
  if (under && under.name.endsWith('_leaves')) {
    m.doing = 'climbing down from the treetops'
    await dig(bot, under)
    await sleep(800)
    return
  }
  const time = bot.time?.timeOfDay ?? 0
  const night = time > 12_800 && time < 23_200

  if (!m.plot && Date.now() >= (m.noPlotUntil ?? 0)) {
    // Go to the camp first: from far away its ground isn't loaded, so nothing would look buildable.
    const pos = bot.entity.position
    const far = Math.hypot(pos.x - centre.x, pos.z - centre.z)
    if (far > 40) {
      // In 40-block legs: the pathfinder can't plan past the chunks the client has loaded.
      m.doing = `heading to the camp (${Math.round(far)} blocks away)`
      const leg = Math.min(40, far - 20) / far
      await goto(bot, new goals.GoalNearXZ(pos.x + (centre.x - pos.x) * leg, pos.z + (centre.z - pos.z) * leg, 4), 60e3)
      return
    }
    m.plot = findPlot(bot, centre, ctx.index(m), ctx.takenPlots(m))
    if (m.plot) ctx.savePlot(m)
    else m.noPlotUntil = Date.now() + 5 * 60e3 // no room right now: look again in a while
  }
  // Planks under any column a block lower than the rest, then the hut itself.
  const fills = []
  if (m.plot) for (let x = 0; x < HUT; x++) for (let z = 0; z < HUT; z++) fills.push(new Vec3(m.plot.x + x, m.plot.y - 1, m.plot.z + z))
  const cells = m.plot ? [...fills.filter(c => free(bot.blockAt(c)) || needsClearing(bot.blockAt(c))), ...hutCells(m.plot)] : []
  // Away from the plot its chunks aren't loaded: go and look before deciding anything about the hut.
  if (m.plot && cells.some(c => !bot.blockAt(c))) {
    m.doing = 'going to my plot'
    await goto(bot, new goals.GoalNearXZ(m.plot.x + 2, m.plot.z - 2, 3), 60e3)
    return
  }
  const left = cells.filter(c => free(bot.blockAt(c)) || needsClearing(bot.blockAt(c)))
  // Progress, counted only from near the plot (far away, its chunks aren't loaded and it would look done).
  if (m.plot && bot.entity.position.distanceTo(new Vec3(m.plot.x + 2, m.plot.y, m.plot.z + 2)) < 48) {
    m.hutLeft = left.length
    m.hutSize = cells.length
  }

  if (night && m.plot && !left.length) {
    // Home for the night: inside the hut.
    const inside = new Vec3(m.plot.x + 2, m.plot.y, m.plot.z + 2)
    m.doing = 'home in my hut for the night'
    if (bot.entity.position.distanceTo(inside) > 1.5) await goto(bot, new goals.GoalBlock(inside.x, inside.y, inside.z), 30e3)
    await sleep(5000)
    return
  }

  if (m.plot && left.length) {
    // Clear the plot first: trees standing where the hut goes (the logs are wood for the hut).
    const tree = left.find(c => needsClearing(bot.blockAt(c)))
    if (tree) return clearBlock(m, bot, tree)
    const planks = count(bot, isPlanks)
    if (planks === 0) {
      // Gather a few logs per trip (4 logs = 16 planks), or whatever it has once the trees run out.
      const logs = count(bot, n => n.endsWith('_log'))
      const want = Math.ceil(left.length / 4)
      if (logs > 0 && (logs >= Math.min(4, want) || m.noTrees)) return craftPlanks(m, bot)
      return chop(m, bot, centre, want)
    }
    return build(m, bot, left, cells.length)
  }

  // Hut done (or no room for one): its job.
  if (!m.hutAnnounced && m.plot) {
    m.hutAnnounced = true
    ctx.say(m, ctx.ai ? null : likes(m.personality, 'build') ? 'my hut is done, and it looks great' : 'finally, a roof over my head')
  }
  const job = settlerJob(m.personality)
  m.job = job
  if (job === 'scout') return scout(m, bot, centre, ctx)
  if (job === 'keeper') return stroll(m, bot, centre, 6, 'keeping the camp')
  if (job === 'entertainer') {
    if (Math.random() < 0.15) ctx.joke(m)
    return stroll(m, bot, centre, 8, 'hanging out by the camp')
  }
  return stroll(m, bot, centre, 20, 'looking after the settlement')
}

/**
 * Break a block, giving up after a few seconds: when the server refuses (spawn protection, a claims plugin) it
 * just puts the block back and mineflayer would wait for it to break forever. False if it didn't break.
 */
async function dig (bot, block, ms = 8000) {
  let timer
  try {
    await Promise.race([bot.dig(block, true), new Promise((resolve, reject) => { timer = setTimeout(() => reject(new Error('dig timed out')), ms) })])
  } catch {
    try { bot.stopDigging() } catch {}
  } finally {
    clearTimeout(timer)
  }
  await sleep(300)
  const now = bot.blockAt(block.position)
  if (now && now.name === block.name) {
    refused.add(key(block.position))
    return false
  }
  return true
}

// SETTLE_DEBUG=1 prints what each settler decides, for working out why one isn't getting anywhere.
const debug = (bot, ...a) => { if (process.env.SETTLE_DEBUG) console.log('[settle]', bot.username, ...a) }

async function goto (bot, goal, timeoutMs = 45e3) {
  debug(bot, 'goto', goal.x ?? '', goal.y ?? '', goal.z ?? '', 'from', bot.entity.position.floored().toString())
  const start = bot.entity.position.clone()
  let timer
  try {
    await Promise.race([
      bot.pathfinder.goto(goal),
      new Promise((resolve, reject) => { timer = setTimeout(() => reject(new Error('took too long to get there')), timeoutMs) })
    ])
  } catch (e) {
    // Hardly moved: probably down a hole. Cut a step out toward the goal, like a player would.
    if (bot.entity.position.distanceTo(start) < 2 && goal.x != null && goal.z != null) await digStep(bot, goal.x, goal.z)
    throw e
  } finally {
    clearTimeout(timer)
    bot.pathfinder.setGoal(null)
  }
}

/** Face (x, z), clear the two blocks in front at head height (soft ground only) and jump up onto the step. */
async function digStep (bot, x, z) {
  const pos = bot.entity.position
  const dx = Math.sign(Math.round(x - pos.x))
  const dz = dx ? 0 : Math.sign(Math.round(z - pos.z))
  if (!dx && !dz) return
  const base = pos.floored()
  for (const off of [new Vec3(dx, 1, dz), new Vec3(dx, 2, dz), new Vec3(0, 2, 0)]) {
    const b = bot.blockAt(base.plus(off))
    if (b && b.boundingBox === 'block' && ['dirt', 'grass_block', 'coarse_dirt', 'sand', 'gravel', 'podzol'].includes(b.name)) await dig(bot, b, 4000)
  }
  await bot.lookAt(base.offset(dx + 0.5, 1.5, dz + 0.5), true).catch(() => {})
  bot.setControlState('forward', true)
  bot.setControlState('jump', true)
  await sleep(700)
  bot.clearControlStates()
  debug(bot, 'dug a step toward', x, z, 'now at', bot.entity.position.floored().toString())
}

/** Punch the nearest reachable log near the camp and pick it up. */
async function chop (m, bot, centre, want) {
  const have = count(bot, n => n.endsWith('_log'))
  m.doing = `chopping wood for my hut (${have}/${want} logs)`
  const logIds = Object.values(bot.registry.blocksByName).filter(b => b.name.endsWith('_log') && !b.name.startsWith('stripped_')).map(b => b.id)
  // The trees near the camp run out as the settlement grows: look further out, up to about 100 blocks.
  const radius = SETTLE_RADIUS + (m.woodReach ?? 0)
  const spots = bot.findBlocks({ matching: logIds, maxDistance: 64, count: 120 })
    .filter(p => Math.hypot(p.x - centre.x, p.z - centre.z) <= radius && p.y <= bot.entity.position.y + 4 && p.y >= bot.entity.position.y - 6)
    .filter(p => !protectedSpot(bot, p) && !refused.has(key(p)))
    .sort((a, b) => a.distanceTo(bot.entity.position) - b.distanceTo(bot.entity.position))
  m.noTrees = !spots.length
  if (!spots.length) {
    // No trees in reach: walk out further each time and look again.
    m.woodReach = Math.min((m.woodReach ?? 0) + 16, 56)
    const a = rand(0, Math.PI * 2)
    const d = rand(radius * 0.5, radius)
    await goto(bot, new goals.GoalNearXZ(centre.x + Math.cos(a) * d, centre.z + Math.sin(a) * d, 3), 45e3).catch(() => {})
    m.doing = 'looking for trees near the camp'
    return
  }
  const target = spots[0]
  debug(bot, 'chop target', target.toString(), 'of', spots.length, 'candidates; refused so far', refused.size)
  try {
    await goto(bot, new goals.GoalNear(target.x, target.y, target.z, 2), 30e3)
  } catch (e) {
    refused.add(key(target)) // can't get to that tree: try others
    throw e
  }
  const block = bot.blockAt(target)
  if (!isLog(block) || !bot.canDigBlock(block)) {
    refused.add(key(target)) // out of reach from anywhere it can stand: try other trees
    return
  }
  if (!await dig(bot, block)) { // the server put it back (a protected area): try other trees
    debug(bot, 'dig refused at', target.toString())
    return
  }
  debug(bot, 'dug', target.toString())
  // The log drops at the stump: walk onto it to pick it up.
  await sleep(100)
  const drop = bot.nearestEntity(e => e.name === 'item' && e.position.distanceTo(target) < 4)
  if (drop) await goto(bot, new goals.GoalNear(drop.position.x, drop.position.y, drop.position.z, 0), 8e3).catch(() => {})
  await sleep(300)
  debug(bot, 'logs in inventory:', count(bot, n => n.endsWith('_log')))
}

/** Dig out a log or leaves standing where the hut goes. */
async function clearBlock (m, bot, pos) {
  m.doing = 'clearing my plot'
  const eye = bot.entity.position.offset(0, bot.entity.height * 0.9, 0)
  if (eye.distanceTo(pos.offset(0.5, 0.5, 0.5)) > REACH) {
    await goto(bot, new goals.GoalNear(pos.x, pos.y, pos.z, 3), 20e3)
    return
  }
  const b = bot.blockAt(pos)
  if (!needsClearing(b) || !bot.canDigBlock(b)) return
  if (!await dig(bot, b)) return
  if (isLog(b)) {
    await sleep(400)
    const drop = bot.nearestEntity(e => e.name === 'item' && e.position.distanceTo(pos) < 4)
    if (drop) await goto(bot, new goals.GoalNear(drop.position.x, drop.position.y, drop.position.z, 0), 8e3).catch(() => {})
  }
}

/** Turn logs into planks in the 2x2 inventory grid (no crafting table needed). */
async function craftPlanks (m, bot) {
  m.doing = 'crafting planks'
  for (const item of bot.inventory.items().filter(i => i.name.endsWith('_log'))) {
    const planks = bot.registry.itemsByName[item.name.replace(/_log$/, '_planks')]
    if (!planks) continue
    const recipe = bot.recipesFor(planks.id, null, 1, null)[0]
    if (recipe) await bot.craft(recipe, item.count, null)
  }
}

/** Place the next few hut blocks it can reach (walking closer when needed). */
async function build (m, bot, left, total) {
  m.doing = `building my hut (${total - left.length}/${total})`
  // The first block (in build order) that has something to rest on.
  const cell = left.find(c => FACES.some(f => solid(bot.blockAt(c.plus(f))))) ?? left[0]
  const pos = bot.entity.position
  const eye = pos.offset(0, bot.entity.height * 0.9, 0)
  const centre = cell.offset(0.5, 0.5, 0.5)
  // Not into its own body.
  const inMe = Math.floor(pos.x) === cell.x && Math.floor(pos.z) === cell.z && cell.y >= Math.floor(pos.y) - 0 && cell.y <= Math.floor(pos.y) + 1
  if (eye.distanceTo(centre) > REACH || inMe) {
    // Stand next to it on the outside of the hut, a step back.
    const plotMid = new Vec3(m.plot.x + 2, m.plot.y, m.plot.z + 2)
    const out = cell.minus(plotMid)
    const len = Math.hypot(out.x, out.z) || 1
    const sx = cell.x + (out.x / len) * 2
    const sz = cell.z + (out.z / len) * 2
    await goto(bot, new goals.GoalNearXZ(sx, sz, 1), 20e3).catch(() => goto(bot, new goals.GoalNear(cell.x, cell.y, cell.z, 3), 20e3))
    return
  }
  // Grass or flowers in the way: the server won't place into them from this angle, so break them first.
  const occupant = bot.blockAt(cell)
  if (occupant && occupant.name !== 'air' && occupant.name !== 'cave_air') {
    await dig(bot, occupant, 3000)
    return
  }
  // Something to place it against: the block below, or a neighbour (for the roof, which grows inwards).
  let ref = null
  let face = null
  for (const f of FACES) {
    const b = bot.blockAt(cell.plus(f))
    if (solid(b)) {
      ref = b
      face = f.scaled(-1)
      break
    }
  }
  if (!ref) return
  const planks = bot.inventory.items().find(i => isPlanks(i.name))
  await bot.equip(planks, 'hand')
  let timer
  try {
    await Promise.race([bot.placeBlock(ref, face), new Promise((resolve, reject) => { timer = setTimeout(() => reject(new Error('the server didn\'t confirm the block')), 8000) })])
  } finally {
    clearTimeout(timer)
  }
}

async function scout (m, bot, centre, ctx) {
  const a = rand(0, Math.PI * 2)
  const d = rand(40, 80)
  const dir = ['east', 'south-east', 'south', 'south-west', 'west', 'north-west', 'north', 'north-east'][Math.round(a / (Math.PI / 4)) % 8]
  m.doing = `scouting to the ${dir}`
  await goto(bot, new goals.GoalNearXZ(centre.x + Math.cos(a) * d, centre.z + Math.sin(a) * d, 4), 90e3).catch(() => {})
  const biome = bot.blockAt(bot.entity.position.floored())?.biome?.name?.replace(/_/g, ' ')
  m.doing = 'heading back to camp'
  await goto(bot, new goals.GoalNearXZ(centre.x, centre.z, 6), 90e3).catch(() => {})
  if (biome && Date.now() - (m.lastReport ?? 0) > 180e3) {
    m.lastReport = Date.now()
    ctx.say(m, ctx.ai ? null : `back from the ${dir}: ${biome} out there`)
  }
}

async function stroll (m, bot, centre, r, what) {
  m.doing = what
  await goto(bot, new goals.GoalNearXZ(centre.x + rand(-r, r), centre.z + rand(-r, r), 2), 25e3).catch(() => {})
  await sleep(rand(3000, 9000))
}

export { hutCells, slot }
export const sayLine = (p, kind) => line(p, kind)
