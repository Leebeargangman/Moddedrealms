// One chat turn with JSON-schema constrained output, either on an Ollama the manager can reach
// (the VPS) or relayed through the website to a staff member's own computer (ai.relay).
export async function chatJSON (ai, system, user, schema, { temperature = ai.temperature ?? 0.4, timeoutMs = ai.timeoutMs ?? 120000 } = {}) {
  const request = {
    model: ai.model,
    stream: false,
    format: schema,
    keep_alive: '30m',
    options: { temperature, num_ctx: 6144 },
    messages: [{ role: 'system', content: system }, { role: 'user', content: user }]
  }
  let body
  if (ai.relay) {
    body = await ai.relay(request, timeoutMs)
  } else {
    const res = await fetch(`${ai.ollamaUrl}/api/chat`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      signal: AbortSignal.timeout(timeoutMs),
      body: JSON.stringify(request)
    })
    if (!res.ok) throw new Error(`ollama HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`)
    body = await res.json()
  }
  return JSON.parse(body.message?.content ?? '{}')
}
