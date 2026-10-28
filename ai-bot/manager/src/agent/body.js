// The bot's body: friendly wrappers over the bridge mod (state, Baritone, inventory, containers).
import { check, wait, isAbort, tierOf } from '../util.js'

const FOODS = {
  enchanted_golden_apple: 12, golden_apple: 9.6, golden_carrot: 20.4, cooked_beef: 20.8, cooked_porkchop: 20.8,
  cooked_mutton: 15.6, cooked_salmon: 15.6, cooked_chicken: 13.2, cooked_rabbit: 11, baked_potato: 11, cooked_cod: 11,
  bread: 11, mushroom_stew: 13.2, rabbit_stew: 22, beetroot_soup: 13.2, pumpkin_pie: 12.8, apple: 6.4, carrot: 6.6,
  melon_slice: 3.2, sweet_berries: 2.4, glow_berries: 2.4, cookie: 2.4, beef: 4.8, porkchop: 4.8, mutton: 3.2,
  chicken: 3.2, rabbit: 4.8, cod: 2.4, salmon: 2.4, potato: 1.6, beetroot: 2.2, dried_kelp: 1.6, rotten_flesh: 4.8
}

const GIVE_UP = /unable to do it|no locations|no known locations|nothing to (mine|do)|can't find|cannot path/i

const match = pred => typeof pred === 'function' ? pred : pred instanceof RegExp ? i => pred.test(i.item) : i => i.item === pred

export class Body {
  constructor (bridge) {
    this.b = bridge
    this.baritoneLog = [] // recent Baritone chat messages: {t, text}
    bridge.on('baritone', m => {
      this.baritoneLog.push({ t: Date.now(), text: m.text })
      if (this.baritoneLog.length > 50) this.baritoneLog.shift()
    })
  }

  // ------------------------------------------------------------------ state

  get s () { return this.b.state }
  get inWorld () { return !!(this.s?.inWorld && this.s.player) }
  get p () { return this.s?.player }
  get pos () { return this.p ? { x: this.p.x, y: this.p.y, z: this.p.z } : null }
  get dimension () { return this.p?.dimension ?? 'overworld' }
  get health () { return this.p?.health ?? 20 }
  get food () { return this.p?.food ?? 20 }
  get name () { return this.p?.name }
  get baritoneState () { return this.s?.baritone ?? {} }

  items () { return this.s?.inventory ?? [] }
  inventory () {
    const inv = {}
    for (const i of this.items()) inv[i.item] = (inv[i.item] ?? 0) + i.count
    return inv
  }
  count (pred) { return this.items().filter(match(pred)).reduce((n, i) => n + i.count, 0) }
  find (pred) { return this.items().find(match(pred)) }
  equipment () { return this.s?.equipment ?? {} }
  entities () { return this.s?.entities ?? [] }
  players () { return this.entities().filter(e => e.type === 'player') }
  bestTier (kind) { return Math.max(0, ...this.items().filter(i => i.item.endsWith('_' + kind)).map(i => tierOf(i.item))) }
  freeSlots () { return 36 - this.items().length }

  dist (x, y, z) {
    const p = this.pos
    return p ? Math.hypot(p.x - x, (y ?? p.y) - p.y, p.z - z) : Infinity
  }

  // ------------------------------------------------------------------ Baritone

  async baritone (command) {
    const ok = await this.b.baritone(command)
    if (ok === false) throw new Error(`Baritone didn't accept "#${command}"`)
  }

  async stop () { await this.b.request('stop').catch(() => {}) }

  /**
   * Wait until Baritone has nothing left to do (or `until()` is true). Aborting or timing out
   * cancels Baritone so the bot doesn't keep walking.
   */
  async waitBaritone (signal, { timeoutMs = 120000, until, stuckMs = 45000 } = {}) {
    const t0 = Date.now() - 500
    let idle = 0
    let lastPos = this.pos
    let lastMove = Date.now()
    try {
      await wait(1000, signal)
      while (Date.now() - t0 < timeoutMs) {
        check(signal)
        if (until?.()) return 'done'
        // Baritone said it gave up ("Unable to do it", "No locations known"...): stop waiting.
        const said = this.baritoneLog.filter(m => m.t >= t0)
        const giveUp = said.findIndex(m => GIVE_UP.test(m.text))
        if (giveUp >= 0) {
          const detail = said.slice(Math.max(0, giveUp - 6), giveUp + 1).map(m => m.text.replace(/^\[Baritone\]\s*/, '')).join(' ')
          throw new Error(`Baritone: ${detail.slice(0, 400)}`)
        }
        const b = this.baritoneState
        idle = (!b.pathing && !b.calculating && !b.processActive && !b.building && !b.mining && !b.following) ? idle + 1 : 0
        if (idle >= 3) return 'idle'
        const p = this.pos
        if (p && lastPos && Math.hypot(p.x - lastPos.x, p.y - lastPos.y, p.z - lastPos.z) > 1) { lastPos = p; lastMove = Date.now() }
        if (stuckMs && Date.now() - lastMove > stuckMs && !b.building) throw new Error('stuck: no progress')
        await wait(500, signal)
      }
      throw new Error('timed out')
    } catch (e) {
      await this.stop()
      throw e
    }
  }

  async goto (x, z, y, signal, timeoutMs = 10 * 60e3) {
    await this.baritone(y == null ? `goto ${Math.round(x)} ${Math.round(z)}` : `goto ${Math.round(x)} ${Math.round(y)} ${Math.round(z)}`)
    await this.waitBaritone(signal, { timeoutMs })
    const d = y == null ? this.dist(x, null, z) : this.dist(x, y, z)
    if (d > 6) throw new Error(`stopped ${Math.round(d)} blocks short of the goal`)
  }

  // ------------------------------------------------------------------ inventory

  /** Menu slot for an inventory slot: the player inventory, or below an open container. */
  menuSlot (invSlot, containerSlots = 0) {
    if (!containerSlots) return invSlot < 9 ? 36 + invSlot : invSlot
    return invSlot < 9 ? containerSlots + 27 + invSlot : containerSlots + invSlot - 9
  }

  /** Put a matching item in a hotbar slot and select it. */
  async hold (pred, hotbar) {
    const it = this.find(pred)
    if (!it) throw new Error('item not in inventory')
    if (it.slot < 9) {
      await this.b.request('select', { slot: it.slot })
      return it
    }
    const target = hotbar ?? this.p.selectedSlot
    await this.b.request('click', { slot: this.menuSlot(it.slot), button: target, mode: 'SWAP' })
    await this.b.request('select', { slot: target })
    await wait(150)
    return it
  }

  bestFood () {
    return this.items()
      .filter(i => FOODS[i.item] && (i.item !== 'rotten_flesh' || this.food <= 6))
      .sort((a, b) => FOODS[b.item] - FOODS[a.item])[0]
  }

  async eat (signal) {
    const food = this.bestFood()
    if (!food) throw new Error('no edible food in inventory')
    const before = this.food
    await this.hold(i => i.item === food.item)
    await this.b.request('key', { key: 'use', down: true })
    try {
      const t0 = Date.now()
      while (Date.now() - t0 < 3500 && this.food <= before) await wait(200, signal)
    } finally {
      await this.b.request('key', { key: 'use', down: false }).catch(() => {})
    }
    return `ate ${food.item}`
  }

  async container () { return this.b.request('container') }
  async closeContainer () { await this.b.request('closeContainer').catch(() => {}) }

  /** Right-click a block (chest, furnace, crafting table, bed...) and wait for its screen to open. */
  async open (x, y, z, signal) {
    if (this.dist(x + 0.5, y + 0.5, z + 0.5) > 4.2) await this.goto(x, z, y, signal, 90000).catch(e => { if (isAbort(e)) throw e })
    await this.b.request('useOn', { x, y, z, face: 'up' })
    for (let i = 0; i < 20; i++) {
      await wait(150, signal)
      const c = await this.container()
      if (c.type !== 'InventoryMenu') return c
    }
    throw new Error('container did not open')
  }

  /** Fresh state right now, instead of waiting for the next 4-per-second update. */
  async refresh () {
    const s = await this.b.request('state')
    this.b.state = s
    return s
  }

  /**
   * Craft in the open crafting table or the 2x2 inventory grid. Returns { made, needsTable }:
   * `made` is how many items actually arrived in the inventory.
   */
  async craft (item, times = 1) {
    const before = this.count(item)
    let needsTable = false
    for (let n = 0; n < times; n++) {
      const r = await this.b.request('placeRecipe', { item })
      if (!r.placed) { needsTable = r.needsTable; break }
      // The server fills the grid a moment later; then shift-click the result out.
      let ready = false
      for (let i = 0; i < 15 && !ready; i++) {
        await wait(100)
        ready = (await this.container()).slots.some(s => s.slot === 0)
      }
      if (!ready) break
      await this.b.request('click', { slot: 0, mode: 'QUICK_MOVE' })
      await wait(150)
    }
    await this.refresh()
    return { made: this.count(item) - before, needsTable }
  }

  async attack (entityId) { return this.b.request('attack', { entity: entityId }) }
  async chat (text) { return this.b.chat(text) }
}
