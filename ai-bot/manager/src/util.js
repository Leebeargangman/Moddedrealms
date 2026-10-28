export class AbortErr extends Error {
  constructor (msg = 'aborted') { super(msg); this.name = 'AbortError' }
}

export const isAbort = e => e?.name === 'AbortError'

export function check (signal) {
  if (signal?.aborted) throw new AbortErr(signal.reason?.message || 'aborted')
}

export const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

// Sleep that wakes early (and throws) when the task is aborted.
export async function wait (ms, signal) {
  check(signal)
  await new Promise(resolve => {
    const t = setTimeout(done, ms)
    function done () { clearTimeout(t); signal?.removeEventListener('abort', done); resolve() }
    signal?.addEventListener('abort', done, { once: true })
  })
  check(signal)
}

export function withTimeout (promise, ms, msg = 'timed out') {
  let t
  return Promise.race([
    promise,
    new Promise((_resolve, reject) => { t = setTimeout(() => reject(new Error(msg)), ms) })
  ]).finally(() => clearTimeout(t))
}

const matcher = pred => typeof pred === 'function'
  ? pred
  : pred instanceof RegExp ? i => pred.test(i.name) : i => i.name === pred

export const items = bot => bot.inventory.items()
export const count = (bot, pred) => items(bot).filter(matcher(pred)).reduce((n, i) => n + i.count, 0)
export const find = (bot, pred) => items(bot).find(matcher(pred))

export const hdist = (a, b) => Math.hypot(a.x - b.x, a.z - b.z)

export const TIERS = { wooden: 1, golden: 2, stone: 2, iron: 3, diamond: 4, netherite: 5 }
export function tierOf (name) {
  const m = /^(wooden|golden|stone|iron|diamond|netherite)_/.exec(name)
  return m ? TIERS[m[1]] : 0
}

// Highest tier of a tool kind (pickaxe, sword, axe...) we carry.
export function bestTier (bot, kind) {
  return Math.max(0, ...items(bot).filter(i => i.name.endsWith('_' + kind)).map(i => tierOf(i.name)))
}

export function dimension (bot) {
  return String(bot.game?.dimension ?? 'overworld').replace('minecraft:', '')
}

export function isNight (bot) {
  const t = bot.time?.timeOfDay ?? 6000
  return t >= 12542 && t <= 23460
}
