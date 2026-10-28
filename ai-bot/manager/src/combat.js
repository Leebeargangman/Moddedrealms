import { goals, goto } from './nav.js'
import { check, wait, items, find, tierOf } from './util.js'

export const HOSTILE = new Set([
  'zombie', 'husk', 'drowned', 'zombie_villager', 'skeleton', 'stray', 'bogged', 'spider', 'cave_spider',
  'creeper', 'witch', 'slime', 'magma_cube', 'pillager', 'vindicator', 'evoker', 'vex', 'ravager',
  'phantom', 'blaze', 'wither_skeleton', 'ghast', 'hoglin', 'zoglin', 'silverfish', 'endermite',
  'piglin_brute', 'breeze', 'warden', 'guardian', 'elder_guardian', 'shulker'
])
// Mobs we never try to melee.
export const FLEE_ONLY = new Set(['creeper', 'warden', 'ghast', 'ravager', 'elder_guardian', 'evoker'])
export const RANGED = new Set(['skeleton', 'stray', 'bogged', 'pillager', 'blaze', 'witch', 'ghast', 'breeze'])
export const FOOD_ANIMALS = new Set(['cow', 'pig', 'sheep', 'chicken', 'rabbit', 'mooshroom'])

const ARMOR_RANK = { leather: 1, golden: 2, chainmail: 3, turtle: 3, iron: 4, diamond: 5, netherite: 6 }
const ARMOR_SLOTS = { helmet: 'head', chestplate: 'torso', leggings: 'legs', boots: 'feet' }

function armorInfo (item) {
  const m = /^(\w+?)_(helmet|chestplate|leggings|boots)$/.exec(item?.name ?? '')
  if (!m || !(m[1] in ARMOR_RANK)) return null
  const ench = (item.enchants?.length ?? 0) * 0.3
  return { dest: ARMOR_SLOTS[m[2]], rank: ARMOR_RANK[m[1]] + ench }
}

export function weaponScore (item) {
  if (!item) return 0
  const t = tierOf(item.name)
  if (item.name.endsWith('_sword')) return t * 2 + 1
  if (item.name.endsWith('_axe')) return t * 2
  return 0
}

export async function equipBestArmor (bot) {
  for (const dest of Object.values(ARMOR_SLOTS)) {
    const current = bot.inventory.slots[bot.getEquipmentDestSlot(dest)]
    const curRank = armorInfo(current)?.rank ?? 0
    const best = items(bot).map(i => ({ i, a: armorInfo(i) }))
      .filter(x => x.a?.dest === dest && x.a.rank > curRank)
      .sort((x, y) => y.a.rank - x.a.rank)[0]
    if (best) await bot.equip(best.i, dest)
  }
}

export async function equipBestWeapon (bot) {
  const best = items(bot).sort((a, b) => weaponScore(b) - weaponScore(a))[0]
  if (best && weaponScore(best) > weaponScore(bot.heldItem)) await bot.equip(best, 'hand')
}

export async function ensureTotem (bot) {
  const off = bot.inventory.slots[45]
  if (off?.name === 'totem_of_undying') return
  const totem = find(bot, 'totem_of_undying')
  if (totem) await bot.equip(totem, 'off-hand')
  else if (!off) {
    const shield = find(bot, 'shield')
    if (shield) await bot.equip(shield, 'off-hand')
  }
}

// Rough "how much would we lose if we died" score, used for panic-logging.
export function gearScore (bot) {
  let s = 0
  for (const dest of Object.values(ARMOR_SLOTS)) s += (armorInfo(bot.inventory.slots[bot.getEquipmentDestSlot(dest)])?.rank ?? 0) * 2
  for (const i of items(bot)) {
    if (/diamond|netherite/.test(i.name)) s += 3 * Math.min(i.count, 8)
    if (i.name === 'totem_of_undying') s += 10 * i.count
    if (i.name === 'enchanted_golden_apple') s += 10 * i.count
    if (i.name.endsWith('shulker_box')) s += 15
    if (i.enchants?.length) s += 2
  }
  return Math.round(s)
}

function attackCooldownMs (held) {
  const n = held?.name ?? ''
  if (n.endsWith('_sword')) return 650
  if (n.endsWith('_axe')) return 1000
  return 300
}

// Melee an entity until it dies, escapes, or we run out of time.
export async function fight (bot, entity, signal, timeoutMs = 25000) {
  await equipBestWeapon(bot)
  const t0 = Date.now()
  let lastHit = 0
  try {
    while (Date.now() - t0 < timeoutMs) {
      check(signal)
      if (!bot.entities[entity.id] || !entity.isValid) return 'target gone'
      const d = bot.entity.position.distanceTo(entity.position)
      if (d > 24) return 'target escaped'
      if (d > 3) {
        if (!bot.pathfinder.goal) bot.pathfinder.setGoal(new goals.GoalFollow(entity, 2), true)
      } else {
        if (bot.pathfinder.goal) bot.pathfinder.setGoal(null)
        await bot.lookAt(entity.position.offset(0, entity.height * 0.8, 0), true)
        if (Date.now() - lastHit >= attackCooldownMs(bot.heldItem)) {
          bot.attack(entity)
          lastHit = Date.now()
        }
      }
      await wait(100, signal)
    }
    return 'fight timed out'
  } finally {
    bot.pathfinder.setGoal(null)
  }
}

// Run away from an entity until we are `distance` blocks away.
// Throws 'cornered' if we can't gain any distance and the threat is on top of us.
export async function flee (bot, entity, distance, signal, timeoutMs = 30000) {
  const t0 = Date.now()
  bot.pathfinder.setGoal(new goals.GoalInvert(new goals.GoalFollow(entity, distance)), true)
  let best = bot.entity.position.distanceTo(entity.position)
  let lastProgress = Date.now()
  let stalls = 0
  try {
    while (Date.now() - t0 < timeoutMs) {
      check(signal)
      if (!bot.entities[entity.id]) return 'threat out of sight'
      const d = bot.entity.position.distanceTo(entity.position)
      if (d >= distance) return 'escaped'
      if (d > best + 1) { best = d; lastProgress = Date.now() }
      if (Date.now() - lastProgress > 4000) {
        // Not getting anywhere: run for a fixed point directly away from them instead.
        if (++stalls >= 3 && d < 4) throw new Error('cornered')
        const away = bot.entity.position.minus(entity.position)
        const ang = Math.atan2(away.z, away.x) + (stalls - 1) * 0.8 * (stalls % 2 ? 1 : -1)
        const p = bot.entity.position
        bot.pathfinder.setGoal(new goals.GoalNearXZ(p.x + Math.cos(ang) * (distance + 10), p.z + Math.sin(ang) * (distance + 10), 3))
        best = d
        lastProgress = Date.now()
      }
      await wait(250, signal)
    }
    return 'still fleeing after timeout'
  } finally {
    bot.pathfinder.setGoal(null)
  }
}

// Walk over dropped items near us.
export async function collectDrops (bot, signal, radius = 8, timeoutMs = 6000) {
  const t0 = Date.now()
  let picked = 0
  while (Date.now() - t0 < timeoutMs) {
    check(signal)
    const drop = Object.values(bot.entities)
      .filter(e => e.name === 'item' && e.position.distanceTo(bot.entity.position) <= radius)
      .sort((a, b) => a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position))[0]
    if (!drop) break
    try {
      await goto(bot, new goals.GoalNear(drop.position.x, drop.position.y, drop.position.z, 0.8), signal, 5000)
      await wait(250, signal)
      picked++
    } catch (e) {
      check(signal)
      break
    }
  }
  return picked
}
