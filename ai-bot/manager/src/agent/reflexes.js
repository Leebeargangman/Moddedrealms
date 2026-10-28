import { HOSTILE, FLEE_ONLY, RANGED, fight, flee, equipBestArmor, ensureTotem } from './combat.js'

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

    // Auto log-off: very low health from anything. Rejoins after a few minutes or when staff say so.
    if (p.health <= (this.cfg.autoLogoffHealth ?? 4) && !agent.killingSelf) {
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

  // Hostile players: flee at long range. Strangers: keep a careful distance. Trusted/staff: ignore.
  playerThreat () {
    const social = this.agent.social
    let best = null
    for (const e of this.body.players()) {
      if (!e.name || e.name === this.body.name) continue
      const rel = social?.relation(e.name) ?? 'unknown'
      if (['owner', 'staff', 'friend', 'trusted', 'team'].includes(rel)) continue
      const radius = rel === 'hostile' ? this.cfg.fleePlayerRadius : this.cfg.carefulRadius
      if (e.dist > radius) continue
      const recently = Date.now() - (this.fledFrom.get(e.name) ?? 0) < 20000
      if (recently && e.dist > Math.min(10, radius * 0.6)) continue
      if (!best || e.dist < best.dist) best = { ...e, fleeTo: radius + (rel === 'hostile' ? 24 : 10) }
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
