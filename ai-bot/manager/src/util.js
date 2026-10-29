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

export const hdist = (a, b) => Math.hypot(a.x - b.x, a.z - b.z)

export const TIERS = { wooden: 1, golden: 2, stone: 2, iron: 3, diamond: 4, netherite: 5 }
export function tierOf (name) {
  const m = /^(wooden|golden|stone|iron|diamond|netherite)_/.exec(name)
  return m ? TIERS[m[1]] : 0
}

export const isNightTime = t => t >= 12542 && t <= 23460

// 2b2t-style highways run along the axes and diagonals; walk on a bearing 15-30 degrees off them.
export function pickBearing () {
  const sector = Math.floor(Math.random() * 8)
  return (sector * 45 + 15 + Math.random() * 15) * Math.PI / 180
}

/** Nudge a bearing that has drifted within 10 degrees of a highway back off it. */
export function offHighway (a) {
  const d = ((a * 180 / Math.PI) % 45 + 45) % 45
  if (d < 10) return a + (10 - d) * Math.PI / 180
  if (d > 35) return a - (d - 35) * Math.PI / 180
  return a
}
