import fs from 'node:fs'
import path from 'node:path'
import { ROOT } from './config.js'

const DEFAULTS = {
  travel: null, // { angle, distance, done }
  stash: null, // { x, y, z, dimension }
  spawnBed: null, // { x, y, z }
  deaths: [],
  notes: [], // things worth remembering, e.g. "diamonds at x y z, need iron pickaxe"
  events: [],
  stats: { sessions: 0, joins: 0, deaths: 0, playMs: 0, queueMs: 0 },
  lastPos: null
}

// Persistent knowledge that survives restarts and deaths.
export class Memory {
  constructor (file = path.join(ROOT, 'data', 'memory.json')) {
    this.file = file
    fs.mkdirSync(path.dirname(file), { recursive: true })
    let loaded = {}
    try { loaded = JSON.parse(fs.readFileSync(file, 'utf8')) } catch {}
    this.data = { ...structuredClone(DEFAULTS), ...loaded, stats: { ...DEFAULTS.stats, ...loaded.stats } }
  }

  save () {
    const tmp = this.file + '.tmp'
    fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2))
    fs.renameSync(tmp, this.file)
  }

  event (text) {
    this.data.events.push({ t: new Date().toISOString(), text })
    if (this.data.events.length > 60) this.data.events.splice(0, this.data.events.length - 60)
    this.save()
  }

  note (text) {
    if (this.data.notes.includes(text)) return
    this.data.notes.push(text)
    if (this.data.notes.length > 20) this.data.notes.shift()
    this.save()
  }

  recentEvents (n = 8) {
    return this.data.events.slice(-n).map(e => `${e.t.slice(11, 19)} ${e.text}`)
  }
}
