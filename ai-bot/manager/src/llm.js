// Minimal Ollama client: one chat turn, JSON-schema constrained output.
export async function chatJSON (ai, system, user, schema, { temperature = ai.temperature, timeoutMs = ai.timeoutMs } = {}) {
  const res = await fetch(`${ai.ollamaUrl}/api/chat`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    signal: AbortSignal.timeout(timeoutMs),
    body: JSON.stringify({
      model: ai.model,
      stream: false,
      format: schema,
      keep_alive: '30m',
      options: { temperature, num_ctx: 6144 },
      messages: [{ role: 'system', content: system }, { role: 'user', content: user }]
    })
  })
  if (!res.ok) throw new Error(`ollama HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`)
  const body = await res.json()
  return JSON.parse(body.message?.content ?? '{}')
}
