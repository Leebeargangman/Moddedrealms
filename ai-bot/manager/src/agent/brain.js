import { SKILLS, ARGS_SCHEMA } from './skills.js'
import { chatJSON } from '../llm.js'

const SYSTEM = `You are the strategic brain of a Minecraft survival bot. Usually it plays on 2b2t-style anarchy servers.
There are no rules: hacked clients, griefing and spawn-killing are normal. The area within several thousand blocks
of spawn (0,0) is a barren, griefed wasteland full of hostile players. Highways run along the X/Z axes and the
diagonals and are patrolled by killers. Bases and stashes near spawn or highways get found and destroyed.

Long-term goal: survive as long as possible and become as powerful as possible: full diamond then netherite gear,
enchantments, totems, golden apples, and large hidden stashes far from spawn. Never reveal your location.

Low-level survival is automatic (eating, fighting/fleeing mobs and players, totems, armor). You only choose the next
high-level ACTION. Strategy:
1. While closer to spawn than the settle distance, the top priority is getting away with travel_far. Only stop to
   gather_wood or hunt when trees/animals are right there and you lack tools/food.
2. Progression: logs -> wooden_pickaxe -> cobblestone -> stone_pickaxe + stone_sword -> food (hunt, cook meat via smelt)
   -> bed (sleep sets your respawn point far from spawn, extremely valuable) -> strip_mine for iron -> smelt raw_iron
   -> iron pickaxe/sword/armor -> strip_mine for diamonds -> diamond gear -> stash surplus.
3. At night in the overworld: sleep if you have a bed, otherwise hide or work underground (strip_mine).
4. Keep food. Stay away from players unless they are marked trusted/owner. Use exact Minecraft item ids (e.g. "stone_pickaxe", "raw_iron").
5. Do not repeat an action that just failed with the same arguments; fix the cause or pick something else.
6. Long trips go through the nether automatically (goto/travel_far) when you carry 10 obsidian and flint and steel
   or know a portal; keep those for long journeys.
7. A rule-based suggestion is provided; follow it unless you have a clear reason not to.
8. Anarchy know-how: never build or place anything visible near spawn, highways or the nether roof; work
   underground where you can; don't sleep or stash where other players were seen; keep a spare totem and food;
   an ender chest keeps valuables safe even if you die; when hurt with players around, get away first, heal later.

Reply with ONLY JSON: {"thought": "<one short sentence>", "action": "<action>", "args": {...}}

Actions:
${Object.entries(SKILLS).map(([k, v]) => `- ${k} ${v.args}: ${v.desc}`).join('\n')}`

const SCHEMA = {
  type: 'object',
  properties: {
    thought: { type: 'string', maxLength: 200 },
    action: { type: 'string', enum: Object.keys(SKILLS) },
    args: ARGS_SCHEMA
  },
  required: ['thought', 'action', 'args']
}

// Deterministic fallback planner. Also fed to the LLM as a hint.
export function ruleCandidates (o, cfg) {
  const c = []
  const add = (thought, action, args = {}) => c.push({ thought, action, args })
  const inv = o.inventory
  const n = k => inv[k] ?? 0
  const sum = re => Object.entries(inv).filter(([k]) => re.test(k)).reduce((s, [, v]) => s + v, 0)
  const wood = sum(/_(log|stem)$/) * 4 + sum(/_planks$/)
  const cobble = n('cobblestone') + n('cobbled_deepslate')
  const hasBed = sum(/_bed$/) > 0 || (o.nearby.blocks.bed ?? 0) > 0
  const animals = Object.values(o.nearby.animals).reduce((a, b) => a + b, 0)
  const { pickaxe, sword } = o.tools
  const overworld = o.dimension === 'overworld'
  const settled = o.distFromSpawn >= cfg.behavior.settleDistance * 0.8 || o.memory.travelTarget?.reached

  if (o.isNight && overworld && hasBed) add('night: sleep and set respawn point', 'sleep')
  if (o.foodPoints < 12 && animals > 0) add('low on food and animals are close', 'hunt', { count: 3 })
  const [raw] = o.rawMeat.sort((a, b) => b[1] - a[1])
  if (raw && raw[1] >= 3 && (cobble >= 8 || o.nearby.blocks.furnace || n('furnace')) && (wood >= 4 || n('coal') || n('charcoal'))) {
    add('cook raw food', 'smelt', { item: raw[0], count: Math.min(raw[1], 8) })
  }
  if (o.isNight && overworld && !hasBed && o.nearby.hostiles.length >= 2) add('dangerous night, hide', 'hide', { seconds: 300, untilDay: true })

  if (!settled) {
    if (!pickaxe && wood < 12 && (o.nearby.blocks.logs ?? 0) > 0) add('grab wood for tools on the way', 'gather_wood', { count: 6 })
    if (!pickaxe && wood >= 12) add('make a pickaxe', 'craft', { item: 'wooden_pickaxe', count: 1 })
    add('still too close to spawn', 'travel_far')
    return c
  }

  if (!pickaxe) {
    if (wood >= 12) add('need a pickaxe', 'craft', { item: 'wooden_pickaxe', count: 1 })
    else if ((o.nearby.blocks.logs ?? 0) > 0) add('need wood', 'gather_wood', { count: 8 })
    else add('no trees here, keep exploring', 'travel_far')
  }
  if (pickaxe < 2) {
    if (cobble >= 3) add('upgrade to stone pickaxe', 'craft', { item: 'stone_pickaxe', count: 1 })
    else if (pickaxe) add('need cobblestone', 'mine', { block: 'stone', count: 8 })
  }
  if (sword < 2 && cobble >= 2 && wood >= 2) add('need a weapon', 'craft', { item: 'stone_sword', count: 1 })
  if (pickaxe && cobble < 10) add('stockpile cobblestone for furnace/building', 'mine', { block: 'stone', count: 16 })
  if (n('torch') < 8 && (n('coal') || n('charcoal')) && wood >= 2) add('torches for mining', 'craft', { item: 'torch', count: 8 })
  if (!hasBed && n('white_wool') >= 3 && wood >= 3) add('craft a bed to set respawn point', 'craft', { item: 'white_bed', count: 1 })
  if (n('raw_iron') >= 3) add('smelt iron', 'smelt', { item: 'raw_iron', count: Math.min(n('raw_iron'), 16) })
  const ingots = n('iron_ingot')
  if (pickaxe < 3 && ingots >= 3) add('iron pickaxe', 'craft', { item: 'iron_pickaxe', count: 1 })
  if (sword < 3 && ingots >= 2) add('iron sword', 'craft', { item: 'iron_sword', count: 1 })
  const pieces = [['chestplate', 'chest', 8], ['leggings', 'legs', 7], ['helmet', 'head', 5], ['boots', 'feet', 4]]
  for (const mat of [['iron', 3, ingots], ['diamond', 4, n('diamond')]]) {
    for (const [piece, slot, cost] of pieces) {
      const wearing = o.equipment[slot] ?? ''
      const tier = /^(iron|chainmail)/.test(wearing) ? 3 : /^diamond/.test(wearing) ? 4 : /^netherite/.test(wearing) ? 5 : 0
      if (tier < mat[1] && mat[2] >= cost) add(`${mat[0]} ${piece}`, 'craft', { item: `${mat[0]}_${piece}`, count: 1 })
    }
  }
  if (pickaxe === 3 && n('diamond') >= 3) add('diamond pickaxe', 'craft', { item: 'diamond_pickaxe', count: 1 })
  if (sword < 4 && pickaxe >= 3 && n('diamond') >= 2) add('diamond sword', 'craft', { item: 'diamond_sword', count: 1 })
  if (o.freeSlots < 6) add('inventory almost full', 'stash')
  if (pickaxe) add('mine for iron and diamonds', 'strip_mine', { minutes: 8 })
  add('explore further', 'travel_far')
  return c
}

const sameCall = (a, b) => a.action === b.action && JSON.stringify(a.args ?? {}) === JSON.stringify(b.args ?? {})

export class Brain {
  constructor (agent) {
    this.agent = agent
    this.cfg = agent.cfg
    this.failures = 0
    this.disabledUntil = 0
  }

  recentlyFailed (call, history) {
    return history.slice(-4).filter(h => !h.ok && sameCall(h, call)).length >= 2
  }

  async decide (obs, history) {
    const candidates = ruleCandidates(obs, this.cfg).filter(c => !this.recentlyFailed(c, history))
    const fallback = candidates[0] ?? { thought: 'nothing else works, explore', action: 'travel_far', args: {} }
    if (!this.agent.ai().enabled || Date.now() < this.disabledUntil) return { ...fallback, source: 'rules' }
    try {
      const d = await this.ask(obs, history, fallback)
      if (!SKILLS[d.action]) throw new Error(`unknown action ${d.action}`)
      if (this.recentlyFailed(d, history)) return { ...fallback, source: 'rules (llm repeated a failure)' }
      this.failures = 0
      return { thought: String(d.thought ?? '').slice(0, 200), action: d.action, args: d.args ?? {}, source: 'llm' }
    } catch (e) {
      this.agent.log(`[brain] LLM error: ${e.message}`)
      if (++this.failures >= 3) {
        this.disabledUntil = Date.now() + 5 * 60e3
        this.agent.log('[brain] LLM disabled for 5 min, using rules')
      }
      return { ...fallback, source: 'rules' }
    }
  }

  async ask (obs, history, suggestion) {
    const directive = this.agent.memory.data.ownerDirective
    const team = this.agent.instance.team
    const user = [
      directive ? `OWNER DIRECTIVE (your owner's standing order; it outranks the strategy above unless it would get you killed): ${directive}` : '',
      team ? `TEAM: you play together with ${team.names().filter(n => n !== this.agent.body.name).join(', ')} (leader ${team.leader()?.session?.name}). Stay near them, never fight them, share with them. Leader's directive: ${this.agent.memory.data.teamDirective ?? 'none yet'}` : '',
      `Settle distance: ${this.cfg.behavior.settleDistance} blocks from spawn.`,
      `STATE: ${JSON.stringify(obs)}`,
      `RECENT ACTIONS:\n${history.slice(-6).map(h => `- ${h.action} ${JSON.stringify(h.args)} -> ${h.ok ? 'OK' : 'FAILED'}: ${h.result}`).join('\n') || '- none'}`,
      `RECENT EVENTS:\n${this.agent.memory.recentEvents(6).map(e => `- ${e}`).join('\n') || '- none'}`,
      `RULE-BASED SUGGESTION: ${JSON.stringify({ action: suggestion.action, args: suggestion.args })} (${suggestion.thought})`,
      'Choose the next action.'
    ].filter(Boolean).join('\n\n')
    return chatJSON(this.agent.ai(), SYSTEM, user, SCHEMA)
  }
}

