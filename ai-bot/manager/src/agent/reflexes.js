import { HOSTILE, FLEE_ONLY, RANGED, fight, flee, equipBestArmor, ensureTotem, gearScore } from './combat.js'
import { wait } from '../util.js'

// Things that blow up, and how close is too close. End crystals and TNT minecarts are how people kill on anarchy servers.
const EXPLOSIVE = { tnt: 9, tnt_minecart: 9, end_crystal: 10, wither_skull: 10, wither: 40 }
// Held items that mean a player came to fight.
const ARMED = /_sword$|_axe$|^bow$|^crossbow$|^trident$|^mace$|^end_crystal$|^respawn_anchor$|^tnt$|^flint_and_steel$/

// Servers kick players who stand still (9b9t after 10 minutes); hop and look around well before that.
const AFK_MS = 4 * 60e3

// Task priorities: higher interrupts lower.
export const PRI = { brain: 1, owner: 1.5, eat: 2, mob: 3, player: 4, hazard: 5 }

// Fast, code-driven survival behaviour, run 4x a second from the bridge's state feed.
export class Reflexes {
  constructor (agent) {
    this.agent = agent
    this.body = agent.body
    this.cfg = agent.cfg.behavior
    this.lastArmor = 0
    this.lastSprint = null
    this.fledFrom = new Map() // name -> time
    this.ignoreMob = new Map() // entity id -> until (mobs we couldn't reach)
  }

  async tick () {
    const { agent, body } = this
    const p = body.p
    if (!p || p.dead) return
    const cur = agent.task?.priority ?? 0

    // Auto log-off: very low health while still taking damage. (Low but safe: stay, eat and heal.)
    // Rejoins after a few minutes or when staff say so.
    const beingHurt = Date.now() - (agent.lastHurt ?? 0) < 3000
    if (p.health <= (this.cfg.autoLogoffHealth ?? 4) && beingHurt) {
      agent.instance.autoLogoff(`health ${p.health.toFixed(1)}`)
      return
    }

    await ensureTotem(body).catch(() => {})

    // Don't sprint when food is low; starving on 2b2t is a real way to die.
    const sprint = p.food > 8
    if (sprint !== this.lastSprint) {
      this.lastSprint = sprint
      body.baritone(`set allowSprint ${sprint}`).catch(() => {})
    }

    // Drowning: hold jump to swim up. Baritone handles lava and falls.
    const drowning = p.inWater && p.air < 100
    if (drowning !== this.drowning) {
      this.drowning = drowning
      body.b.request('key', { key: 'jump', down: drowning }).catch(() => {})
    }

    this.antiAfk(p)

    const boom = this.explosiveThreat()
    if (boom && cur < PRI.hazard) {
      agent.memory.event(`ran from ${boom.type.replace(/_/g, ' ')} (${Math.round(boom.dist)}m)`)
      agent.runTask(`flee_${boom.type}`, PRI.hazard, s => flee(agent.ctx, boom.id, EXPLOSIVE[boom.type] + 8, s, 15000).catch(err => {
        if (err.message === 'cornered') return 'cornered by an explosive'
        throw err
      }))
      return
    }

    const player = this.playerThreat()
    if (player && cur < PRI.player) {
      this.fledFrom.set(player.name, Date.now())
      const rel = agent.social?.relation(player.name) ?? 'unknown'
      agent.memory.event(`backing off from ${rel} player ${player.name} (${Math.round(player.dist)}m)`)
      agent.runTask('flee_player', PRI.player, async s => {
        try {
          return await flee(agent.ctx, player.id, player.fleeTo, s, 45000)
        } catch (err) {
          if (err.message !== 'cornered') throw err
          // Anarchy: someone who corners us usually wants our stuff. With gear worth taking, leave the server.
          if (this.anarchy && (gearScore(body) >= 6 || player.armed || rel === 'hostile')) {
            agent.instance.autoLogoff(`cornered by ${player.name}`)
            return 'logged off to get away'
          }
          // Never throw the first punch at a stranger; fight back once they attacked us.
          if (rel === 'hostile') return fight(agent.ctx, player.id, s, 15000)
          return 'cornered by a stranger, holding still'
        }
      })
      return
    }

    const mob = this.mobThreat()
    const hidden = agent.task?.name === 'hide' && Date.now() - (agent.lastHurt ?? 0) > 3000
    if (mob && cur < PRI.mob && !hidden) {
      const canFight = !FLEE_ONLY.has(mob.type) && p.health >= this.cfg.fightMobsMinHealth
      agent.runTask(canFight ? `fight_${mob.type}` : `flee_${mob.type}`, PRI.mob, async s => {
        if (!canFight) return flee(agent.ctx, mob.id, mob.type === 'creeper' ? 12 : 20, s, 20000)
        const r = await fight(agent.ctx, mob.id, s, 15000)
        if (r !== 'killed') this.ignoreMob.set(mob.id, Date.now() + 60000)
        return r
      })
      return
    }

    const hungry = p.food <= 14 || (p.health < 14 && p.food < 20)
    if (hungry && cur < PRI.eat && body.bestFood()) {
      agent.runTask('eat', PRI.eat, s => body.eat(s))
      return
    }

    if (cur < PRI.mob && Date.now() - this.lastArmor > 5000) {
      this.lastArmor = Date.now()
      await equipBestArmor(body).catch(() => {})
    }
  }

  get anarchy () { return this.cfg.mode === 'anarchy' }

  // Hiding in a hole, waiting for an order, or stuck: none of it moves the bot, and an AFK kick throws away
  // the spot (and on a queue server the place in the queue).
  antiAfk (p) {
    const now = Date.now()
    const s = this.still
    if (!s || Math.hypot(p.x - s.x, p.y - s.y, p.z - s.z) > 1.5) {
      this.still = { x: p.x, y: p.y, z: p.z, t: now }
      return
    }
    if (now - s.t < AFK_MS || this.drowning) return
    s.t = now
    const { b } = this.body
    const a = Math.random() * Math.PI * 2
    b.request('lookAt', { x: p.x + Math.cos(a) * 6, y: p.y + 1, z: p.z + Math.sin(a) * 6 }).catch(() => {})
    b.request('key', { key: 'jump', down: true })
      .then(() => wait(400))
      .finally(() => b.request('key', { key: 'jump', down: false }).catch(() => {}))
      .catch(() => {})
  }

  explosiveThreat () {
    let best = null
    for (const e of this.body.entities()) {
      const r = EXPLOSIVE[e.type]
      if (r && e.dist <= r && (!best || e.dist < best.dist)) best = e
    }
    return best
  }

  // Hostile players: flee at long range. Strangers: keep a careful distance. Trusted/staff: ignore.
  // On anarchy servers every stranger may be a killer: keep twice as far, and further still from armed or
  // well-geared ones (they're not there to chat).
  playerThreat () {
    const social = this.agent.social
    let best = null
    for (const e of this.body.players()) {
      if (!e.name || e.name === this.body.name) continue
      const rel = social?.relation(e.name) ?? 'unknown'
      if (['owner', 'staff', 'friend', 'trusted', 'team'].includes(rel)) continue
      const armed = ARMED.test(e.held ?? '') || (e.armor ?? []).some(a => /^(diamond|netherite)_/.test(a))
      let radius = rel === 'hostile' ? this.cfg.fleePlayerRadius : this.cfg.carefulRadius
      if (this.anarchy) radius = Math.max(radius * 2, rel === 'hostile' ? 48 : 0) * (armed ? 1.5 : 1)
      if (e.dist > radius) continue
      const recently = Date.now() - (this.fledFrom.get(e.name) ?? 0) < 20000
      if (recently && e.dist > Math.min(10, radius * 0.6)) continue
      if (!best || e.dist < best.dist) best = { ...e, armed, fleeTo: radius + (rel === 'hostile' ? 24 : 10) }
    }
    return best
  }

  mobThreat () {
    const me = this.body.pos
    const ents = this.body.entities()
    // Teammates close to us: we also defend them.
    const team = new Set((this.agent.instance.team?.names() ?? []).map(n => n.toLowerCase()))
    const mates = ents.filter(e => e.type === 'player' && team.has(String(e.name).toLowerCase()) && e.dist < 16)
    let best = null
    for (const e of ents) {
      if (!HOSTILE.has(e.type) || (this.ignoreMob.get(e.id) ?? 0) > Date.now()) continue
      const range = e.type === 'creeper' ? 7 : RANGED.has(e.type) ? 12 : 8
      const nearMate = !FLEE_ONLY.has(e.type) && mates.some(m => Math.hypot(m.x - e.x, m.y - e.y, m.z - e.z) < 6)
      if ((e.dist > range && !nearMate) || Math.abs(e.y - me.y) > 6) continue
      if (!best || e.dist < best.dist) best = e
    }
    return best
  }
}
