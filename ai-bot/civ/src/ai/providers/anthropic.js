// Claude (Anthropic API). Used when provider is "anthropic", or "auto" with ANTHROPIC_API_KEY set.

import Anthropic from "@anthropic-ai/sdk";
import { config } from "../../config.js";

export const KEY_NAME = "ANTHROPIC_API_KEY";

let client = null;
function getClient() {
  // maxRetries 0: we handle retries ourselves with a long backoff, so calls can never pile up.
  if (!client) client = new Anthropic({ maxRetries: 0, timeout: config.ai.requestTimeoutMs });
  return client;
}

/**
 * One request. system = array of system blocks (the big one carries cache_control).
 * Returns { text, refused, note, t } where t = { input, cacheRead, cacheWrite, output }.
 */
export async function call({ model, system, user, maxTokens, schema }) {
  const request = {
    model,
    max_tokens: maxTokens,
    // Haiku 5.5 thinks by default. Thinking tokens are billed as output and would eat the small
    // max_tokens, so it is turned off: picking the next goal doesn't need it.
    thinking: { type: "disabled" },
    output_config: { effort: config.ai.effort },
    system,
    messages: [{ role: "user", content: user }],
  };
  if (schema) request.output_config.format = { type: "json_schema", schema };

  const res = await getClient().messages.create(request);
  const u = res.usage;
  const refused = res.stop_reason === "refusal";
  return {
    text: res.content.filter((b) => b.type === "text").map((b) => b.text).join("").trim(),
    refused,
    note: refused ? `refusal ${res.stop_details?.category || ""}`.trim() : res.stop_reason,
    t: {
      input: u.input_tokens || 0,
      cacheRead: u.cache_read_input_tokens || 0,
      cacheWrite: u.cache_creation_input_tokens || 0,
      output: u.output_tokens || 0,
    },
  };
}

/** Sort an error into: "schema" | "auth" | "rate" | "overloaded" | "connection" | "other". */
export function classify(err) {
  const status = err instanceof Anthropic.APIError ? err.status : undefined;
  let type = "other";
  if (err instanceof Anthropic.BadRequestError && /output_config|format|schema/i.test(err.message)) type = "schema";
  else if (err instanceof Anthropic.AuthenticationError || err instanceof Anthropic.PermissionDeniedError) type = "auth";
  else if (err instanceof Anthropic.RateLimitError) type = "rate";
  else if (err instanceof Anthropic.OverloadedError || err instanceof Anthropic.InternalServerError) type = "overloaded";
  else if (err instanceof Anthropic.APIConnectionError) type = "connection";
  return { type, status };
}
