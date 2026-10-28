// Fighting, fleeing and gear, on top of the bridge (attacks) and Baritone (movement).
import { check, wait, tierOf } from '../util.js'

export const HOSTILE = new Set([
  'zombie', 'husk', 'drowned', 'zombie_villager', 'skeleton', 'stray', 'bogged', 'spider', 'cave_spider',
  'creeper', 'witch', 'slime', 'magma_cube', 'pillager', 'vindicator', 'evoker', 'vex', 'ravager',
  'phantom', 'blaze', 'wither_skeleton', 'ghast', 'hoglin', 'zoglin', 'silverfish', 'endermite',
  'piglin_brute', 'breeze', 'warden', 'guardian', 'elder_guardian', 'shulker', 'creaking'
])
export const FLEE_ONLY = new Set(['creeper', 'warden', 'ghast', 'ravager', 'elder_guardian', 'evoker', 'creaking'])
export const RANGED = new Set(['skeleton', 'stray', 'bogged', 'pillager', 'blaze', 'witch', 'ghast', 'breeze'])

const ARMOR_RANK = { leather: 1, golden: 2, chainmail: 3, turtle: 3, iron: 4, diamond: 5, netherite: 6 }
const ARMOR_SLOT = { helmet: ['head', 5], chestplate: ['chest', 6], leggings: ['legs', 7], boots: ['feet', 8] }

function armorInfo (stack) {
  const m = /^(\w+?)_(helmet|chestplate|leggings|boots)$/.exec(stack?.item ?? '')
  if (!m || !(m[1] in ARMOR_RANK)) return null
  return { equip: ARMOR_SLOT[m[2]][0], menu: ARMOR_SLOT[m[2]][1], rank: ARMOR_RANK[m[1]] + (stack.enchanted ? 0.5 : 0) }
}

export function weaponScore (stack) {
  if (!stack) return 0
  const t = tierOf(stack.item)
  if (stack.item.endsWith('_sword')) return t * 2 + 1
  if (stack.item.endsWith('_axe')) return t * 2
  return 0
}

const noScreen = body => !body.s?.screen || body.s.screen === 'ChatScreen'

export async function equipBestArmor (body) {
  if (!noScreen(body)) return
  const eq = body.equipment()
  for (const s of body.items()) {
    const a = armorInfo(s)
    if (!a) continue
    const cur = armorInfo(eq[a.equip])
    if (cur && cur.rank >= a.rank) continue
    const from = body.menuSlot(s.slot)
    if (!eq[a.equip]) {
      await body.b.request('click', { slot: from, mode: 'QUICK_MOVE' })
    } else {
      // Swap through the cursor: pick up new piece, put it on, put the old one where the new was.
      await body.b.request('click', { slot: from, mode: 'PICKUP' })
      await body.b.request('click', { slot: a.menu, mode: 'PICKUP' })
      await body.b.request('click', { slot: from, mode: 'PICKUP' })
    }
    return // one change per call; the state refreshes before the next
  }
}

export async function equipBestWeapon (body) {
  const best = body.items().filter(i => weaponScore(i) > 0).sort((a, b) => weaponScore(b) - weaponScore(a))[0]
  const held = body.items().find(i => i.slot === body.p?.selectedSlot)
  if (best && weaponScore(best) > weaponScore(held)) await body.hold(i => i.slot === best.slot)
}

export async function ensureTotem (body) {
  if (!noScreen(body)) return
  if (body.equipment().offhand?.item === 'totem_of_undying') return
  const totem = body.find('totem_of_undying')
  if (totem) await body.b.request('click', { slot: body.menuSlot(totem.slot), button: 40, mode: 'SWAP' })
}

/** Rough "how much would we lose if we died" score. */
export function gearScore (body) {
  let s = 0
  for (const piece of Object.values(body.equipment())) s += (armorInfo(piece)?.rank ?? 0) * 2
  for (const i of body.items()) {
    if (/diamond|netherite/.test(i.item)) s += 3 * Math.min(i.count, 8)
    if (i.item === 'totem_of_undying') s += 10 * i.count
    if (i.item === 'enchanted_golden_apple') s += 10 * i.count
    if (i.item.endsWith('shulker_box')) s += 15
    if (i.enchanted) s += 2
  }
  return Math.round(s)
}

function cooldownMs (body) {
  const held = body.items().find(i => i.slot === body.p?.selectedSlot)?.item ?? ''
  if (held.endsWith('_sword')) return 650
  if (held.endsWith('_axe')) return 1000
  return 300
}

/** Melee an entity until it's gone. Returns 'killed', 'escaped' or 'timeout'. */
export async function fight (ctx, id, signal, timeoutMs = 25000) {
  const { body } = ctx
  await equipBestWeapon(body).catch(() => {})
  const t0 = Date.now()
  let lastHit = 0
  let lastPath = 0
  try {
    while (Date.now() - t0 < timeoutMs) {
      check(signal)
      const e = body.entities().find(x => x.id === id)
      if (!e) return 'killed'
      if (e.dist > 24) return 'escaped'
      if (e.dist > 3.2) {
        if (Date.now() - lastPath > 1500) {
          await body.baritone(`goto ${Math.floor(e.x)} ${Math.floor(e.y)} ${Math.floor(e.z)}`).catch(() => {})
          lastPath = Date.now()
        }
      } else {
        if (lastPath) { await body.stop(); lastPath = 0 }
        if (Date.now() - lastHit >= cooldownMs(body)) {
          await body.attack(id).catch(() => {})
          lastHit = Date.now()
        }
      }
      await wait(100, signal)
    }
    return 'timeout'
  } finally {
    await body.stop()
  }
}

/** Run until `distance` blocks from the entity. Throws 'cornered' if we can't gain ground. */
export async function flee (ctx, id, distance, signal, timeoutMs = 30000) {
  const { body } = ctx
  const t0 = Date.now()
  let best = 0
  let lastProgress = Date.now()
  let tries = 0
  const run = async (angleOffset) => {
    const e = body.entities().find(x => x.id === id)
    if (!e) return
    const p = body.pos
    const a = Math.atan2(p.z - e.z, p.x - e.x) + angleOffset
    await body.baritone(`goto ${Math.round(p.x + Math.cos(a) * (distance + 12))} ${Math.round(p.z + Math.sin(a) * (distance + 12))}`).catch(() => {})
  }
  try {
    await run(0)
    while (Date.now() - t0 < timeoutMs) {
      check(signal)
      const e = body.entities().find(x => x.id === id)
      if (!e) return 'threat out of sight'
      if (e.dist >= distance) return 'escaped'
      if (e.dist > best + 1) { best = e.dist; lastProgress = Date.now() }
      if (Date.now() - lastProgress > 4000) {
        if (++tries >= 3 && e.dist < 4) throw new Error('cornered')
        await run((tries % 2 ? 1 : -1) * 0.8 * tries)
        lastProgress = Date.now()
        best = e.dist
      }
      await wait(250, signal)
    }
    return 'still fleeing after timeout'
  } finally {
    await body.stop()
  }
}
