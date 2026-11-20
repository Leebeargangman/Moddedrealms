// The ONLY place that talks to the Anthropic API. Every call goes through ask(), which:
//   1. checks the daily budget (over budget = no call, the bot uses plain-code behaviour instead)
//   2. respects a global pause after API errors (exponential backoff, never a tight retry loop)
//   3. sends the cached system prompt + the small user message
//   4. logs the tokens and cost of the call to the usage ledger
//
// ask() never throws: on any problem it returns { text: null, reason } and the caller falls back.

import Anthropic from "@anthropic-ai/sdk";
import { config, options } from "../config.js";
import { log } from "../log.js";
import * as ledger from "../usage/ledger.js";
import { mockReply } from "./mock.js";

let client = null;
function getClient() {
  if (!client) {
    if (!process.env.ANTHROPIC_API_KEY) throw new Error("ANTHROPIC_API_KEY is not set: put it in ai-bot/civ/.env");
    // maxRetries 0: we handle retries ourselves with a long backoff, so calls can never pile up.
    client = new Anthropic({ maxRetries: 0, timeout: config.ai.requestTimeoutMs });
  }
  return client;
}

// --- budget -------------------------------------------------------------------------------------
// "ok": normal · "warn": 80% used, cooldowns doubled · "exhausted": 100% used, no more calls today
let lastBudgetState = "ok";
export function budgetState() {
  const spent = ledger.spentToday();
  const cap = config.budget.dailyUsd;
  const state = spent >= cap ? "exhausted" : spent >= cap * config.budget.warnAtFraction ? "warn" : "ok";
  if (state !== lastBudgetState) {
    if (state === "warn") log.warn("budget", `$${spent.toFixed(3)} of $${cap} used today: doubling every bot's AI cooldown`);
    if (state === "exhausted") log.warn("budget", `daily budget of $${cap} reached: bots switch to simple code behaviour until tomorrow`);
    if (state === "ok") log.info("budget", "new budget day: AI calls back to normal");
    lastBudgetState = state;
  }
  return state;
}
/** 1 normally, 2 once 80% of the daily budget is used. */
export const cooldownMultiplier = () => (budgetState() === "warn" ? 2 : 1);

// --- backoff after API errors -------------------------------------------------------------------
let pausedUntil = 0;
let errorStreak = 0;
function backoff(why) {
  errorStreak++;
  const ms = Math.min(config.ai.backoff.firstMs * 2 ** (errorStreak - 1), config.ai.backoff.maxMs);
  pausedUntil = Date.now() + ms;
  log.warn("api", `${why}: pausing all AI calls for ${Math.round(ms / 1000)} s`);
}

// Structured outputs make the API guarantee our JSON shape. If the API ever rejects the setting
// (for example an older model), we switch it off once and rely on our own validation.
let useStructured = config.ai.structuredOutput;

/**
 * Ask the model. `kind` is "decide" or "memory".
 * system: array of system blocks (the big one carries cache_control) · user: the small message text.
 * Returns { text, reason } where text is null if no answer could be had.
 */
export async function ask({ bot, kind, system, user, maxTokens, schema = null }) {
  const model = config.ai.model;
  if (budgetState() === "exhausted") return { text: null, reason: "budget" };
  if (Date.now() < pausedUntil) return { text: null, reason: "paused" };

  if (options.mock) return mockCall({ bot, kind, system, user });

  const started = Date.now();
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
  if (schema && useStructured) request.output_config.format = { type: "json_schema", schema };

  try {
    const res = await getClient().messages.create(request);
    errorStreak = 0;
    const u = res.usage;
    const t = {
      input: u.input_tokens || 0,
      cacheRead: u.cache_read_input_tokens || 0,
      cacheWrite: u.cache_creation_input_tokens || 0,
      output: u.output_tokens || 0,
    };
    const totalIn = t.input + t.cacheRead + t.cacheWrite;
    if (totalIn > config.ai.warnInputTokens) log.warn(bot, `request used ${totalIn} input tokens (limit to watch: ${config.ai.warnInputTokens})`);

    const text = res.content.filter((b) => b.type === "text").map((b) => b.text).join("").trim();
    let note = res.stop_reason;
    if (res.stop_reason === "refusal") note = `refusal ${res.stop_details?.category || ""}`.trim();
    const cost = ledger.record({ bot, kind, model, t, ms: Date.now() - started, ok: Boolean(text) && res.stop_reason !== "refusal", note });
    return { text: res.stop_reason === "refusal" ? null : text, reason: note, tokens: t, cost };
  } catch (err) {
    const ms = Date.now() - started;
    const status = err instanceof Anthropic.APIError ? err.status : undefined;
    ledger.record({ bot, kind, model, t: { input: 0, cacheRead: 0, cacheWrite: 0, output: 0 }, ms, ok: false, note: `error ${status ?? err.name}` });

    if (err instanceof Anthropic.BadRequestError && useStructured && /output_config|format|schema/i.test(err.message)) {
      useStructured = false;
      log.warn("api", "structured outputs were rejected; switching them off and validating JSON ourselves");
    } else if (err instanceof Anthropic.AuthenticationError || err instanceof Anthropic.PermissionDeniedError) {
      backoff("API key refused (check ANTHROPIC_API_KEY in .env)");
    } else if (err instanceof Anthropic.RateLimitError) {
      backoff("rate limited (429)");
    } else if (err instanceof Anthropic.OverloadedError || err instanceof Anthropic.InternalServerError) {
      backoff(`API overloaded or failing (${status})`);
    } else if (err instanceof Anthropic.APIConnectionError) {
      backoff("can't reach the API");
    } else {
      log.error("api", err.message);
    }
    return { text: null, reason: `error ${status ?? err.name}` };
  }
}

// --- mock mode: no API, no cost; token counts are rough estimates (4 characters per token) ---------
function mockCall({ bot, kind, system, user }) {
  const sysChars = system.reduce((n, b) => n + b.text.length, 0);
  const firstCall = !mockCall.warm;
  mockCall.warm = true;
  const text = mockReply(kind, user);
  const t = {
    input: Math.ceil(user.length / 4),
    cacheRead: firstCall ? 0 : Math.ceil(sysChars / 4),
    cacheWrite: firstCall ? Math.ceil(sysChars / 4) : 0,
    output: Math.ceil(text.length / 4),
  };
  ledger.record({ bot, kind, model: "mock", t, ms: 0, ok: true, note: "mock" });
  return Promise.resolve({ text, reason: "mock", tokens: t, cost: 0 });
}
