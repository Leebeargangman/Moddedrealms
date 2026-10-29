#!/usr/bin/env node
// Moddedrealms AI connector: lets bots on the VPS use the AI model running on your own computer.
// It connects out to the website and answers the bots' prompts with your local Ollama; nothing is opened
// on your computer. Needs Node 18+ and Ollama (https://ollama.com).
//
//   node connector.js --site https://moddedrealms.net --token <token from the Bot tab>
//   options: --ollama http://127.0.0.1:11434   --parallel 2
'use strict'

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : true])
  return acc
}, []))
const SITE = String(args.site || process.env.MR_SITE || '').replace(/\/+$/, '')
const TOKEN = String(args.token || process.env.MR_TOKEN || '')
const OLLAMA = String(args.ollama || process.env.OLLAMA_URL || 'http://127.0.0.1:11434').replace(/\/+$/, '')
const PARALLEL = Math.max(1, Math.min(8, Number(args.parallel) || 2))

if (!SITE || !TOKEN) {
  console.error('Usage: node connector.js --site https://your-website --token <token from the Bot tab>')
  process.exit(1)
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log(new Date().toLocaleTimeString(), ...a)
const auth = { authorization: `Bearer ${TOKEN}` }

async function site (path, body, timeoutMs = 15000) {
  const res = await fetch(SITE + path, {
    method: body ? 'POST' : 'GET',
    headers: body ? { ...auth, 'content-type': 'application/json' } : auth,
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(timeoutMs)
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok || data.ok === false) throw new Error(data.error || `HTTP ${res.status}`)
  return data
}

/** Total and free GPU memory in MB (NVIDIA only), so the Bots page can say which models fit here. */
function gpuInfo () {
  return new Promise(resolve => {
    require('child_process').execFile('nvidia-smi', ['--query-gpu=memory.total,memory.free', '--format=csv,noheader,nounits'], { timeout: 5000 }, (err, out) => {
      if (err) return resolve(null)
      const rows = String(out).trim().split('\n').map(l => l.split(',').map(Number)).filter(r => r.length === 2 && r.every(n => n >= 0))
      resolve(rows.length ? { totalMb: rows.reduce((n, r) => n + r[0], 0), freeMb: rows.reduce((n, r) => n + r[1], 0) } : null)
    })
  })
}

async function hello () {
  const os = require('os')
  const tags = await fetch(`${OLLAMA}/api/tags`).then(r => r.json())
  const models = (tags.models || []).map(m => m.name)
  const mem = { totalMb: Math.round(os.totalmem() / 1048576), freeMb: Math.round(os.freemem() / 1048576) }
  const r = await site('/api/llm/hello', { models, mem, gpu: await gpuInfo() })
  log(`connected as ${r.name}; models: ${models.join(', ') || 'none (run: ollama pull qwen2.5:7b)'}`)
}

let running = 0
async function answer ({ id, request }) {
  running++
  const t0 = Date.now()
  try {
    const res = await fetch(`${OLLAMA}/api/chat`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ ...request, stream: false }),
      signal: AbortSignal.timeout(170000)
    })
    const body = await res.json()
    if (!res.ok) throw new Error(body.error || `Ollama HTTP ${res.status}`)
    await site('/api/llm/result', { id, response: { message: body.message } })
    log(`answered a prompt with ${request.model} in ${((Date.now() - t0) / 1000).toFixed(1)}s`)
  } catch (e) {
    log('prompt failed:', e.message)
    await site('/api/llm/result', { id, error: e.message }).catch(() => {})
  } finally {
    running--
  }
}

async function main () {
  while (true) {
    try {
      await hello()
      break
    } catch (e) {
      log(`can't start yet (${e.message}); is Ollama running and the token right? retrying in 10s`)
      await sleep(10000)
    }
  }
  setInterval(() => hello().catch(() => {}), 5 * 60e3)
  let backoff = 2000
  while (true) {
    try {
      while (running >= PARALLEL) await sleep(200)
      const { items } = await site('/api/llm/poll', null, 40000)
      backoff = 2000
      for (const job of items || []) answer(job)
    } catch (e) {
      log('lost the website connection:', e.message)
      await sleep(backoff)
      backoff = Math.min(backoff * 2, 60000)
    }
  }
}

main()
