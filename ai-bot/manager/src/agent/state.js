import { HOSTILE, gearScore } from './combat.js'
import { isNightTime } from '../util.js'

const RAW_MEAT = ['beef', 'porkchop', 'chicken', 'mutton', 'rabbit', 'cod', 'salmon']
const FOOD_ANIMALS = new Set(['cow', 'pig', 'sheep', 'chicken', 'rabbit', 'mooshroom'])
const INTERESTING = ['crafting_table', 'furnace', 'chest', 'nether_portal',
  ...['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry'].map(w => `${w}_log`),
  ...['coal', 'iron', 'diamond'].flatMap(o => [`${o}_ore`, `deepslate_${o}_ore`])]

// Compact snapshot of the world for the planner (LLM or rules).
export async function observe (agent) {
  const { body, memory } = agent
  const p = body.p
  const inventory = body.inventory()
  const foodPoints = body.items().reduce((s, i) => s + (/cooked|bread|apple|carrot|potato|beef|porkchop|chicken|mutton|rabbit|cod|salmon|melon|berries|stew/.test(i.item) ? 4 * i.count : 0), 0)
  const eq = body.equipment()
  const equipment = Object.fromEntries(Object.entries(eq).map(([k, v]) => [k, v?.item ?? null]))
  equipment.hand = body.items().find(i => i.slot === p.selectedSlot)?.item ?? null

  const players = []
  const hostiles = []
  const animals = {}
  for (const e of body.entities()) {
    if (e.type === 'player') players.push({ name: e.name, dist: Math.round(e.dist), relation: agent.social?.relation(e.name) ?? 'unknown' })
    else if (HOSTILE.has(e.type) && e.dist < 24) hostiles.push({ name: e.type, dist: Math.round(e.dist) })
    else if (FOOD_ANIMALS.has(e.type)) animals[e.type] = (animals[e.type] ?? 0) + 1
  }

  const blocks = {}
  try {
    for (const b of await body.b.request('findBlocks', { names: INTERESTING, radius: 20, max: 200 })) {
      const n = b.name.endsWith('_log') ? 'logs' : b.name
      blocks[n] = (blocks[n] ?? 0) + 1
    }
  } catch {}

  const t = memory.data.travel
  return {
    position: { x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) },
    dimension: body.dimension,
    distFromSpawn: Math.round(Math.hypot(p.x, p.z)),
    health: Math.round(p.health),
    food: p.food,
    gameMode: p.gameMode,
    isNight: isNightTime(body.s.time),
    inventory,
    freeSlots: body.freeSlots(),
    foodPoints,
    rawMeat: RAW_MEAT.filter(m => inventory[m]).map(m => [m, inventory[m]]),
    equipment,
    tools: { pickaxe: body.bestTier('pickaxe'), sword: body.bestTier('sword'), axe: body.bestTier('axe') },
    gearScore: gearScore(body),
    nearby: { players, hostiles: hostiles.sort((a, b) => a.dist - b.dist).slice(0, 6), animals, blocks },
    schematics: agent.schematics?.() ?? [],
    memory: {
      travelTarget: t ? { x: Math.round(Math.cos(t.angle) * t.distance), z: Math.round(Math.sin(t.angle) * t.distance), reached: !!t.done } : null,
      stash: memory.data.stash,
      spawnBed: memory.data.spawnBed,
      knownPortals: (memory.data.portals ?? []).length,
      deaths: memory.data.stats.deaths,
      notes: memory.data.notes.slice(-5)
    }
  }
}
