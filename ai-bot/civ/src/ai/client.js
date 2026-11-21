// The ONLY place that asks the AI (Claude, or OpenAI as a stand-in; see src/ai/providers/). Every call goes through ask(), which:
//   1. checks the daily budget (over budget = no call, the bot uses plain-code behaviour instead)
//   2. respects a global pause after API errors (exponential backoff, never a tight retry loop)
//   3. sends the cached system prompt + the small user message
//   4. logs the tokens and cost of the call to the usage ledger
//
// ask() never throws: on any problem it returns { text: null, reason } and the caller falls back.

import { config, options, provider, model } from "../config.js";
import { log } from "../log.js";
import * as ledger from "../usage/ledger.js";
import { mockReply } from "./mock.js";
import * as anthropic from "./providers/anthropic.js";
import * as openai from "./providers/openai.js";

// The provider module that does the actual request (Claude or OpenAI, see config.js).
const api = provider === "openai" ? openai : anthropic;

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
  if (budgetState() === "exhausted") return { text: null, reason: "budget" };
  if (Date.now() < pausedUntil) return { text: null, reason: "paused" };

  if (options.mock) return mockCall({ bot, kind, system, user });

  const started = Date.now();
  try {
    const res = await api.call({ model, system, user, maxTokens, schema: useStructured ? schema : null });
    errorStreak = 0;
    const { t } = res;
    const totalIn = t.input + t.cacheRead + t.cacheWrite;
    if (totalIn > config.ai.warnInputTokens) log.warn(bot, `request used ${totalIn} input tokens (limit to watch: ${config.ai.warnInputTokens})`);
    const cost = ledger.record({ bot, kind, model, t, ms: Date.now() - started, ok: Boolean(res.text) && !res.refused, note: res.note });
    return { text: res.refused ? null : res.text, reason: res.note, tokens: t, cost };
  } catch (err) {
    const { type, status } = api.classify(err);
    ledger.record({ bot, kind, model, t: { input: 0, cacheRead: 0, cacheWrite: 0, output: 0 }, ms: Date.now() - started, ok: false, note: `error ${status ?? err.name}` });

    if (type === "schema" && useStructured) {
      useStructured = false;
      log.warn("api", "structured outputs were rejected; switching them off and validating JSON ourselves");
    } else if (type === "retryable-setting") {
      log.warn("api", "the model rejected the reasoning setting; sending requests without it from now on");
    } else if (type === "auth") {
      backoff(`API key refused (check ${api.KEY_NAME} in .env)`);
    } else if (type === "quota") {
      backoff("the OpenAI account has no credit left (add credit at platform.openai.com > Billing)");
    } else if (type === "rate") {
      backoff("rate limited (429)");
    } else if (type === "overloaded") {
      backoff(`API overloaded or failing (${status})`);
    } else if (type === "connection") {
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
