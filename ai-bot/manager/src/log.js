import fs from 'node:fs'
import path from 'node:path'
import { ROOT } from './config.js'

const LOG_DIR = path.join(ROOT, 'logs')
const LOG_FILE = path.join(LOG_DIR, 'bot.log')
let stream = null
let webhook = ''

export function initLog (cfg) {
  fs.mkdirSync(LOG_DIR, { recursive: true })
  try {
    if (fs.statSync(LOG_FILE).size > 20 * 1024 * 1024) fs.renameSync(LOG_FILE, LOG_FILE + '.1')
  } catch {}
  stream = fs.createWriteStream(LOG_FILE, { flags: 'a' })
  webhook = cfg.notify?.discordWebhook || ''
}

export function log (...parts) {
  const line = `[${new Date().toISOString()}] ${parts.map(p => typeof p === 'string' ? p : JSON.stringify(p)).join(' ')}`
  console.log(line)
  stream?.write(line + '\n')
}

// Log + optional Discord webhook. Never throws.
export async function notify (msg) {
  log('[notify]', msg)
  if (!webhook) return
  try {
    await fetch(webhook, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ content: String(msg).slice(0, 1900) })
    })
  } catch (e) {
    log('[notify] webhook failed:', e.message)
  }
}
