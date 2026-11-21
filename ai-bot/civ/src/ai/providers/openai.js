// OpenAI (ChatGPT API). Used when provider is "openai", or "auto" with only OPENAI_API_KEY set.
// It is a stand-in until a Claude key is available: same prompts, same JSON actions, same logging.
//
// Differences from Claude worth knowing:
//  - Prompt caching is automatic (no cache_control), and only for prompts of 1,024+ tokens. OpenAI only
//    caches up to the end of a "developer" or user message, so the fixed rules go in a developer
//    message (as a "system" message they were never read back from the cache, measured Nov 2026).
//  - The JSON schema must be "strict": every field listed as required, optional ones allowed to be null.
//  - gpt-6-luna is a reasoning model. Reasoning tokens are billed as output, so reasoning is set to
//    "none" (config ai.openaiReasoningEffort), like turning thinking off for Claude.

import OpenAI from "openai";
import { config } from "../../config.js";

export const KEY_NAME = "OPENAI_API_KEY";

let client = null;
function getClient() {
  if (!client) client = new OpenAI({ maxRetries: 0, timeout: config.ai.requestTimeoutMs });
  return client;
}

// If the model rejects reasoning_effort (some models don't have it), stop sending it.
let sendEffort = Boolean(config.ai.openaiReasoningEffort);

/** Turn our schema into OpenAI's strict form: all fields required, optional ones nullable. */
function strictSchema(schema) {
  const properties = {};
  for (const [k, v] of Object.entries(schema.properties)) {
    properties[k] = schema.required.includes(k) ? v : { ...v, type: [v.type, "null"] };
  }
  return { ...schema, properties, required: Object.keys(properties), additionalProperties: false };
}

export async function call({ model, system, user, maxTokens, schema }) {
  const request = {
    model,
    max_completion_tokens: maxTokens,
    messages: [
      { role: "developer", content: system.map((b) => b.text).join("\n\n") },
      { role: "user", content: user },
    ],
    prompt_cache_key: "moddedrealms-civ",
  };
  if (sendEffort) request.reasoning_effort = config.ai.openaiReasoningEffort;
  if (schema) request.response_format = { type: "json_schema", json_schema: { name: "action", strict: true, schema: strictSchema(schema) } };

  const res = await getClient().chat.completions.create(request);
  const choice = res.choices?.[0];
  const u = res.usage || {};
  const cached = u.prompt_tokens_details?.cached_tokens || 0;
  // Some OpenAI models also bill cache writes; take the count if the API reports one.
  const written = u.prompt_tokens_details?.cache_write_tokens || u.prompt_tokens_details?.cache_creation_tokens || 0;
  const refused = Boolean(choice?.message?.refusal);
  return {
    text: (choice?.message?.content || "").trim(),
    refused,
    note: refused ? "refusal" : choice?.finish_reason,
    t: {
      // prompt_tokens includes the cached ones; we log them separately like for Claude.
      input: Math.max(0, (u.prompt_tokens || 0) - cached - written),
      cacheRead: cached,
      cacheWrite: written,
      output: u.completion_tokens || 0, // includes any reasoning tokens
    },
  };
}

export function classify(err) {
  const status = err instanceof OpenAI.APIError ? err.status : undefined;
  let type = "other";
  if (err instanceof OpenAI.BadRequestError && /reasoning/i.test(err.message) && sendEffort) {
    sendEffort = false;
    type = "retryable-setting";
  } else if (err instanceof OpenAI.BadRequestError && /response_format|json_schema|schema/i.test(err.message)) type = "schema";
  else if (err instanceof OpenAI.AuthenticationError || err instanceof OpenAI.PermissionDeniedError) type = "auth";
  // 429 "insufficient_quota" = the account has no credit left: pausing won't fix it, but it stops the spam.
  else if (err instanceof OpenAI.RateLimitError) type = /quota/i.test(err.message) ? "quota" : "rate";
  else if (err instanceof OpenAI.InternalServerError) type = "overloaded";
  else if (err instanceof OpenAI.APIConnectionError) type = "connection";
  return { type, status };
}
