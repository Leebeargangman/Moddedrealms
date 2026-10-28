import pf from 'mineflayer-pathfinder'
import { check, count } from './util.js'

export const { goals, Movements, pathfinder } = pf

export function setupMovements (bot) {
  const m = new Movements(bot)
  m.canDig = true
  m.allowParkour = true
  m.allowSprinting = true
  m.allow1by1towers = true
  m.maxDropDown = 4
  m.liquidCost = 3
  const avoid = ['magma_block', 'powder_snow', 'sweet_berry_bush', 'wither_rose', 'campfire', 'soul_campfire']
  for (const n of avoid) {
    const b = bot.registry.blocksByName[n]
    if (b) m.blocksToAvoid.add(b.id)
  }
  // Never break containers or beds (ours or anyone's) while pathing.
  for (const b of bot.registry.blocksArray) {
    if (/chest|shulker_box|_bed$|barrel|furnace|crafting_table/.test(b.name)) m.blocksCantBreak.add(b.id)
  }
  updateScaffolding(bot, m)
  bot.pathfinder.setMovements(m)
  bot.pathfinder.thinkTimeout = 10000
  return m
}

// Pillar/bridge with junk blocks only; cobblestone is needed for tools and furnaces until we have plenty.
export function updateScaffolding (bot, m) {
  const plenty = count(bot, i => i.name === 'cobblestone' || i.name === 'cobbled_deepslate') > 32
  m.scafoldingBlocks = ['dirt', 'netherrack', 'andesite', 'diorite', 'granite', 'tuff', ...(plenty ? ['cobblestone', 'cobbled_deepslate'] : [])]
    .map(n => bot.registry.itemsByName[n]?.id).filter(id => id != null)
}

// pathfinder.goto that respects an AbortSignal and a hard timeout.
export async function goto (bot, goal, signal, timeoutMs = 90000) {
  check(signal)
  let timedOut = false
  const stop = () => { try { bot.pathfinder.setGoal(null) } catch {} }
  const onTimeout = () => { timedOut = true; stop() }
  signal?.addEventListener('abort', stop, { once: true })
  const timer = setTimeout(onTimeout, timeoutMs)
  try {
    await bot.pathfinder.goto(goal)
  } catch (e) {
    check(signal)
    if (timedOut) throw new Error('path timed out')
    throw e
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', stop)
  }
  check(signal)
}
