// Names of every crowd bot this manager runs. The AI bots treat them as friends (fellow villagers), not as
// strangers to be wary of or run from.
const names = new Set()

export function setCrowdNames (list) {
  names.clear()
  for (const n of list) names.add(String(n).toLowerCase())
}

export const isCrowdBot = name => names.has(String(name ?? '').toLowerCase())

export const crowdNames = () => [...names]
