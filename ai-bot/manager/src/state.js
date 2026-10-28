import { HOSTILE, FOOD_ANIMALS, gearScore } from './combat.js'
import { items, bestTier, dimension, isNight } from './util.js'

const SLOT_NAMES = { head: 5, torso: 6, legs: 7, feet: 8, offhand: 45 }
const RAW_MEAT = ['beef', 'porkchop', 'chicken', 'mutton', 'rabbit', 'cod', 'salmon']

// Compact snapshot of the world for the planner (LLM or rules).
export function observe (agent) {
  const { bot, memory } = agent
  const me = bot.entity.position
  const inventory = {}
  for (const i of items(bot)) inventory[i.name] = (inventory[i.name] ?? 0) + i.count
  const foods = bot.registry.foodsByName
  const foodPoints = items(bot).reduce((s, i) => s + (foods[i.name] ? foods[i.name].foodPoints * i.count : 0), 0)

  const equipment = {}
  for (const [k, slot] of Object.entries(SLOT_NAMES)) equipment[k] = bot.inventory.slots[slot]?.name ?? null
  equipment.hand = bot.heldItem?.name ?? null

  const players = []
  const hostiles = []
  const animals = {}
  for (const e of Object.values(bot.entities)) {
    if (e === bot.entity) continue
    const d = Math.round(e.position.distanceTo(me))
    if (d > 48) continue
    if (e.type === 'player') players.push({ name: e.username, dist: d, relation: agent.social?.relation(e.username) ?? 'unknown' })
    else if (HOSTILE.has(e.name) && d < 24) hostiles.push({ name: e.name, dist: d })
    else if (FOOD_ANIMALS.has(e.name)) animals[e.name] = (animals[e.name] ?? 0) + 1
  }

  const blockCounts = {}
  const interesting = b => /_(log|stem)$/.test(b.name) || b.name.endsWith('_ore') ||
    ['crafting_table', 'furnace', 'chest'].includes(b.name) || b.name.endsWith('_bed')
  for (const pos of bot.findBlocks({ matching: interesting, maxDistance: 24, count: 200 })) {
    let n = bot.blockAt(pos)?.name
    if (!n) continue
    if (/_(log|stem)$/.test(n)) n = 'logs'
    if (n.endsWith('_bed')) n = 'bed'
    blockCounts[n] = (blockCounts[n] ?? 0) + 1
  }

  return {
    position: { x: Math.round(me.x), y: Math.round(me.y), z: Math.round(me.z) },
    dimension: dimension(bot),
    distFromSpawn: Math.round(Math.hypot(me.x, me.z)),
    health: Math.round(bot.health),
    food: bot.food,
    isNight: isNight(bot),
    inventory,
    freeSlots: bot.inventory.emptySlotCount(),
    foodPoints,
    rawMeat: RAW_MEAT.filter(m => inventory[m]).map(m => [m, inventory[m]]),
    equipment,
    tools: { pickaxe: bestTier(bot, 'pickaxe'), sword: bestTier(bot, 'sword'), axe: bestTier(bot, 'axe') },
    gearScore: gearScore(bot),
    nearby: {
      players,
      hostiles: hostiles.sort((a, b) => a.dist - b.dist).slice(0, 6),
      animals,
      blocks: blockCounts
    },
    memory: {
      travelTarget: memory.data.travel
        ? { x: Math.round(Math.cos(memory.data.travel.angle) * memory.data.travel.distance), z: Math.round(Math.sin(memory.data.travel.angle) * memory.data.travel.distance), reached: !!memory.data.travel.done }
        : null,
      stash: memory.data.stash,
      spawnBed: memory.data.spawnBed,
      deaths: memory.data.stats.deaths,
      notes: memory.data.notes.slice(-5)
    }
  }
}
