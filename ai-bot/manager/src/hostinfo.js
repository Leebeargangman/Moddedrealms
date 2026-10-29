// What this machine can take: memory, GPU and the AI models its Ollama has. The Bots page uses it to say
// which models fit where, and the manager uses it to refuse a bot that would leave the system short.
import fs from 'node:fs'
import os from 'node:os'
import { execFile } from 'node:child_process'

/** MB of memory the OS says is available (Linux), or free memory elsewhere. */
export function availableMemMb () {
  try {
    const m = /MemAvailable:\s+(\d+)/.exec(fs.readFileSync('/proc/meminfo', 'utf8'))
    if (m) return Math.round(Number(m[1]) / 1024)
  } catch {}
  return Math.round(os.freemem() / 1048576)
}

export const totalMemMb = () => Math.round(os.totalmem() / 1048576)

/** Rough download size of a model from its tag (Q4 weights, ~0.62 GB per billion parameters), for models not pulled yet. */
export function guessModelMb (name) {
  const b = /[:-](\d+(?:\.\d+)?)b\b/i.exec(name)
  return b ? Math.round(Number(b[1]) * 620 + 200) : null
}

/** Memory a loaded model takes: its weights plus the context and runtime (num_ctx 6144 in llm.js). */
export const modelNeedMb = sizeMb => sizeMb + 600

let cache = { gpu: null, ollama: { up: false, models: [], loaded: [] } }

function nvidia () {
  return new Promise(resolve => {
    execFile('nvidia-smi', ['--query-gpu=name,memory.total,memory.free', '--format=csv,noheader,nounits'], { timeout: 5000 }, (err, out) => {
      if (err) return resolve(null)
      const gpus = String(out).trim().split('\n').map(l => l.split(',').map(s => s.trim())).filter(p => p.length === 3)
      if (!gpus.length) return resolve(null)
      // Ollama splits across GPUs, so add them up.
      resolve({ name: gpus.map(g => g[0]).join(' + '), totalMb: gpus.reduce((n, g) => n + Number(g[1]), 0), freeMb: gpus.reduce((n, g) => n + Number(g[2]), 0) })
    })
  })
}

async function ollama (url) {
  try {
    const get = p => fetch(url + p, { signal: AbortSignal.timeout(4000) }).then(r => r.json())
    const [tags, ps] = await Promise.all([get('/api/tags'), get('/api/ps').catch(() => ({}))])
    return {
      up: true,
      models: (tags.models ?? []).map(m => ({ name: m.name, sizeMb: Math.round(m.size / 1048576) })),
      loaded: (ps.models ?? []).map(m => ({ name: m.name, mb: Math.round(m.size / 1048576), vramMb: Math.round((m.size_vram ?? 0) / 1048576) }))
    }
  } catch {
    return { up: false, models: [], loaded: [] }
  }
}

/** Refresh GPU and Ollama info now and every 15 s (cheap: two local HTTP calls and nvidia-smi). */
export function watchHost (ollamaUrl) {
  const refresh = async () => { cache = { gpu: await nvidia(), ollama: await ollama(ollamaUrl) } }
  setInterval(refresh, 15000).unref()
  return refresh()
}

export const hostInfo = () => cache

// VRAM Ollama leaves for the CUDA runtime, and the smallest share of a model worth putting on the GPU.
const GPU_OVERHEAD_MB = 512
const MIN_GPU_SHARE_MB = 1024

/**
 * How a model of `needMb` would sit on this machine: Ollama puts as many layers as fit in free VRAM on the
 * GPU and the rest in RAM. Returns { gpuMb, ramMb } (ramMb includes ~500 MB of runtime either way).
 */
export function splitModel (needMb, gpu) {
  const room = gpu ? gpu.freeMb - GPU_OVERHEAD_MB : 0
  if (room >= needMb) return { gpuMb: needMb, ramMb: 500 }
  if (room >= MIN_GPU_SHARE_MB) return { gpuMb: room, ramMb: needMb - room + 500 }
  return { gpuMb: 0, ramMb: needMb }
}

/**
 * Extra RAM a bot's AI would take on this machine when it starts: 0 if the model is already loaded or
 * the AI isn't local, else the part of the model that doesn't fit in free VRAM.
 */
export function modelRamMb (ai) {
  if (!ai || ai.enabled === false || ai.source === 'connector' || !ai.model) return 0
  const { gpu, ollama } = cache
  if (ollama.loaded.some(m => m.name === ai.model)) return 0
  const size = ollama.models.find(m => m.name === ai.model)?.sizeMb ?? guessModelMb(ai.model) ?? 4700
  return splitModel(modelNeedMb(size), gpu).ramMb
}

/** Pull a model this machine doesn't have yet, in the background. Resolves when it's ready (or failed). */
const pulling = new Map()
export function ensureModel (ollamaUrl, name, log) {
  if (cache.ollama.models.some(m => m.name === name || m.name === `${name}:latest`)) return Promise.resolve(true)
  if (pulling.has(name)) return pulling.get(name)
  log(`[models] downloading ${name} (about ${Math.round((guessModelMb(name) ?? 0) / 100) / 10} GB), the bot uses its built-in rules until it's ready`)
  const p = fetch(`${ollamaUrl}/api/pull`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ model: name, stream: false }), signal: AbortSignal.timeout(3 * 3600e3) })
    .then(async r => {
      const body = await r.json().catch(() => ({}))
      if (!r.ok || body.error) throw new Error(body.error || `HTTP ${r.status}`)
      log(`[models] ${name} downloaded`)
      cache.ollama.models.push({ name, sizeMb: guessModelMb(name) ?? 0 }) // the next refresh has the real size
      return true
    })
    .catch(e => { log(`[models] could not download ${name}: ${e.message}`); return false })
    .finally(() => pulling.delete(name))
  pulling.set(name, p)
  return p
}

export const pullingModels = () => [...pulling.keys()]
