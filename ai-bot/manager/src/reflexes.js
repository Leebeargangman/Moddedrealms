import { HOSTILE, FLEE_ONLY, RANGED, fight, flee, collectDrops, equipBestArmor, ensureTotem, gearScore } from './combat.js'
import { items } from './util.js'
import { notify } from './log.js'

const BAD_FOOD = new Set(['pufferfish', 'poisonous_potato', 'spider_eye', 'suspicious_stew', 'chorus_fruit'])

// Task priorities: higher interrupts lower.
export const PRI = { brain: 1, owner: 1.5, eat: 2, mob: 3, player: 4, hazard: 5 }

export function bestFood (bot) {
  const foods = bot.registry.foodsByName
  return items(bot)
    .filter(i => foods[i.name] && !BAD_FOOD.has(i.name) && (i.name !== 'rotten_flesh' || bot.food <= 6))
    .sort((a, b) => foods[b.name].effectiveQuality - foods[a.name].effectiveQuality)[0]
}

export async function eat (bot, signal) {
  const food = bestFood(bot)
  if (!food) throw new Error('no edible food in inventory')
  await bot.equip(food, 'hand')
  const onAbort = () => { try { bot.deactivateItem() } catch {} }
  signal?.addEventListener('abort', onAbort, { once: true })
  try { await bot.consume() } finally { signal?.removeEventListener('abort', onAbort) }
  return `ate ${food.name}`
}

// Fast, code-driven survival behaviour. The LLM is far too slow for any of this.
export class Reflexes {
  constructor (agent) {
    this.agent = agent
    this.bot = agent.bot
    this.cfg = agent.cfg.behavior
    this.lastHurt = 0
    this.lastArmor = 0
    this.fledFrom = new Map() // username -> time
    this.bot.on('entityHurt', e => { if (e === this.bot.entity) this.lastHurt = Date.now() })
  }

  async tick () {
    const bot = this.bot
    const agent = this.agent
    const cur = agent.task?.priority ?? 0

    await ensureTotem(bot).catch(() => {})
    if (agent.movements) agent.movements.allowSprinting = bot.food > 8

    if (this.panic()) return

    // Hazards: drowning / lava. Just hold jump; pathfinder handles the rest.
    const e = bot.entity
    const drowning = e.isInWater && bot.oxygenLevel != null && bot.oxygenLevel < 8
    if (drowning || e.isInLava) bot.setControlState('jump', true)
    else if (cur < PRI.hazard && bot.controlState?.jump && !bot.pathfinder.isMoving()) bot.setControlState('jump', false)

    const player = this.playerThreat()
    if (player && cur < PRI.player) {
      this.fledFrom.set(player.username, Date.now())
      agent.memory.event(`backing off from ${agent.social?.relation(player.username) ?? 'unknown'} player ${player.username} (${player.position.distanceTo(e.position).toFixed(0)}m)`)
      agent.runTask('flee_player', PRI.player, async s => {
        try {
          return await flee(bot, player, player.fleeTo ?? this.cfg.fleePlayerRadius + 24, s, 45000)
        } catch (err) {
          if (err.message !== 'cornered') throw err
          // Cornered by someone who already attacked us: fight back rather than die doing nothing.
          // Never throw the first punch at a stranger.
          if (agent.social?.relation(player.username) === 'hostile') return fight(bot, player, s, 15000)
          return 'cornered by a stranger, holding still'
        }
      })
      return
    }

    const mob = this.mobThreat()
    // Sealed in a hide hole: mobs above can't reach us unless we're actually taking damage.
    const hidden = agent.task?.name === 'hide' && Date.now() - this.lastHurt > 3000
    if (mob && cur < PRI.mob && !hidden) {
      const canFight = !FLEE_ONLY.has(mob.name) && bot.health >= this.cfg.fightMobsMinHealth
      agent.runTask(canFight ? `fight_${mob.name}` : `flee_${mob.name}`, PRI.mob, async s => {
        if (!canFight) return flee(bot, mob, mob.name === 'creeper' ? 12 : 20, s, 20000)
        const r = await fight(bot, mob, s)
        await collectDrops(bot, s, 6, 3000).catch(() => {})
        return r
      })
      return
    }

    const hungry = bot.food <= 14 || (bot.health < 14 && bot.food < 20)
    if (hungry && cur < PRI.eat && bestFood(bot)) {
      agent.runTask('eat', PRI.eat, s => eat(bot, s))
      return
    }

    if (cur < PRI.mob && Date.now() - this.lastArmor > 5000) {
      this.lastArmor = Date.now()
      await equipBestArmor(bot).catch(() => {})
    }
  }

  // Hostile players: flee at long range. Strangers: keep a careful distance. Trusted/owner/friends: ignore.
  playerThreat () {
    const bot = this.bot
    const social = this.agent.social
    let best = null
    for (const p of Object.values(bot.players)) {
      const ent = p.entity
      if (!ent || p.username === bot.username) continue
      const rel = social?.relation(p.username) ?? 'unknown'
      if (rel === 'owner' || rel === 'friend' || rel === 'trusted') continue
      const radius = rel === 'hostile' ? this.cfg.fleePlayerRadius : this.cfg.carefulRadius
      const d = ent.position.distanceTo(bot.entity.position)
      if (d > radius) continue
      // Don't keep re-fleeing from someone who stays at a distance; do if they close in.
      const recently = Date.now() - (this.fledFrom.get(p.username) ?? 0) < 20000
      if (recently && d > Math.min(10, radius * 0.6)) continue
      if (!best || d < best.d) best = { ent, d, radius }
    }
    if (best) best.ent.fleeTo = best.radius + (best.radius === this.cfg.fleePlayerRadius ? 24 : 10)
    return best?.ent
  }

  mobThreat () {
    const bot = this.bot
    let best = null
    for (const ent of Object.values(bot.entities)) {
      if (!HOSTILE.has(ent.name)) continue
      const d = ent.position.distanceTo(bot.entity.position)
      const range = ent.name === 'creeper' ? 7 : RANGED.has(ent.name) ? 12 : 8
      if (d > range || Math.abs(ent.position.y - bot.entity.position.y) > 6) continue
      if (!best || d < best.d) best = { ent, d }
    }
    return best?.ent
  }

  // Disconnect when about to die with valuable gear and a player on top of us.
  panic () {
    const bot = this.bot
    const c = this.cfg
    if (!c.panicDisconnect || bot.health > c.panicHealth) return false
    const close = Object.values(bot.players).some(p => p.entity && p.username !== bot.username &&
      p.entity.position.distanceTo(bot.entity.position) < 12)
    if (!close) return false
    const score = gearScore(bot)
    if (score < c.panicMinGearScore) return false
    notify(`PANIC: health ${bot.health.toFixed(1)}, player nearby, gear score ${score}. Disconnecting.`)
    this.agent.panicked = true
    bot.quit('panic')
    return true
  }
}
