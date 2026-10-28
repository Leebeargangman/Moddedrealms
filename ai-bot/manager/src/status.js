import fs from 'node:fs'
import path from 'node:path'
import { ROOT } from './config.js'

try {
  const s = JSON.parse(fs.readFileSync(path.join(ROOT, 'data', 'status.json'), 'utf8'))
  const age = Math.round((Date.now() - Date.parse(s.updated)) / 1000)
  console.log(`${s.username ?? '?'}  state=${s.state}  (updated ${age}s ago)`)
  if (s.state === 'queue') console.log(`queue position ${s.queuePosition}${s.queueEtaMin != null ? `, ETA ~${s.queueEtaMin} min` : ''}`)
  if (s.position) console.log(`pos ${s.position.x} ${s.position.y} ${s.position.z}  (${s.distFromSpawn} from spawn)  hp ${s.health}  food ${s.food}`)
  if (s.lastDecision) console.log(`doing: ${s.task ?? '-'}  last plan: ${s.lastDecision.action} ${JSON.stringify(s.lastDecision.args)} [${s.lastDecision.source}] ${s.lastDecision.thought}`)
  for (const h of s.history ?? []) console.log(`  ${h.ok ? 'ok  ' : 'FAIL'} ${h.action} ${h.result}`)
  console.log(`stats: ${JSON.stringify(s.memory?.stats)}`)
  if (s.memory?.stash) console.log(`stash: ${JSON.stringify(s.memory.stash)}`)
} catch {
  console.log('No status yet (is the bot running?)')
}
