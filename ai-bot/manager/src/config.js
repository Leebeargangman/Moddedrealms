import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

export function merge (base, over) {
  if (Array.isArray(base) || typeof base !== 'object' || base === null) return over ?? base
  const out = { ...base }
  for (const [k, v] of Object.entries(over ?? {})) out[k] = merge(base[k], v)
  return out
}

/** Manager settings: manager.json over manager.example.json, then environment overrides. */
export function loadManagerConfig () {
  const example = JSON.parse(fs.readFileSync(path.join(ROOT, 'manager.example.json'), 'utf8'))
  const file = process.env.MANAGER_CONFIG || path.join(ROOT, 'manager.json')
  const user = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : {}
  const cfg = merge(example, user)
  if (process.env.WEBSITE_URL) cfg.website.url = process.env.WEBSITE_URL
  if (process.env.BOT_MANAGER_KEY) cfg.website.key = process.env.BOT_MANAGER_KEY
  if (process.env.OLLAMA_URL) cfg.ollamaUrl = process.env.OLLAMA_URL
  cfg.website.url = cfg.website.url.replace(/\/+$/, '')
  return cfg
}

/** Full settings for one bot: manager defaults, then the bot's own (account, server, model...). */
export function botConfig (manager, bot) {
  const cfg = merge(manager.defaults, bot)
  cfg.ai.ollamaUrl = manager.ollamaUrl
  return cfg
}
