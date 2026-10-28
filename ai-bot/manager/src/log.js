import fs from 'node:fs'
import path from 'node:path'
import { ROOT } from './config.js'

const LOG_DIR = path.join(ROOT, 'logs')
const LOG_FILE = path.join(LOG_DIR, 'manager.log')
let stream = null

export function initLog () {
  fs.mkdirSync(LOG_DIR, { recursive: true })
  try {
    if (fs.statSync(LOG_FILE).size > 20 * 1024 * 1024) fs.renameSync(LOG_FILE, LOG_FILE + '.1')
  } catch {}
  stream = fs.createWriteStream(LOG_FILE, { flags: 'a' })
}

export function log (...parts) {
  const line = `[${new Date().toISOString()}] ${parts.map(p => typeof p === 'string' ? p : JSON.stringify(p)).join(' ')}`
  console.log(line)
  stream?.write(line + '\n')
}
