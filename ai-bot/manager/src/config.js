import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

function merge (base, over) {
  if (Array.isArray(base) || typeof base !== 'object' || base === null) return over ?? base
  const out = { ...base }
  for (const [k, v] of Object.entries(over ?? {})) out[k] = merge(base[k], v)
  return out
}

export function loadConfig () {
  const example = JSON.parse(fs.readFileSync(path.join(ROOT, 'config.example.json'), 'utf8'))
  const file = process.env.BOT_CONFIG || path.join(ROOT, 'config.json')
  let user = {}
  if (fs.existsSync(file)) user = JSON.parse(fs.readFileSync(file, 'utf8'))
  else console.warn(`[config] ${file} not found, using config.example.json defaults`)
  const cfg = merge(example, user)
  // Env overrides are handy for testing against a local server.
  if (process.env.MC_HOST) cfg.server.host = process.env.MC_HOST
  if (process.env.MC_PORT) cfg.server.port = Number(process.env.MC_PORT)
  if (process.env.MC_VERSION) cfg.server.version = process.env.MC_VERSION
  if (process.env.MC_AUTH) cfg.account.auth = process.env.MC_AUTH
  if (process.env.MC_USERNAME) cfg.account.username = process.env.MC_USERNAME
  if (process.env.OLLAMA_MODEL) cfg.ai.model = process.env.OLLAMA_MODEL
  if (process.env.SETTLE_DISTANCE) cfg.behavior.settleDistance = Number(process.env.SETTLE_DISTANCE)
  cfg.account.profilesFolder = path.resolve(ROOT, cfg.account.profilesFolder)
  return cfg
}
