// The brain decides WHEN to ask the AI, and builds the small message it sends.
//
// - No timer-based thinking: the AI is only asked when notify() reports an event
//   (task finished/failed, attacked, low health/food, chat, night/morning, idle too long).
// - Cooldown: at least ai.cooldownMs (20 s) between calls per bot. Events that arrive during the
//   cooldown, or while a call is running, are batched into the next call.
// - Small context: a memory summary (rewritten every 10 decisions), the last 5 events and a compact
//   state summary. Never a chat history.

import fs from "node:fs";
import path from "node:path";
import { config, DATA_DIR } from "../config.js";
import { log } from "../log.js";
import { ask, cooldownMultiplier } from "../ai/client.js";
import { SYSTEM_PROMPT, MEMORY_SYSTEM, ACTION_LIST } from "../ai/prompts.js";
import { ACTION_SCHEMA, parseAction, fallbackAction, describe } from "../ai/actions.js";

const MEMORY_DIR = path.join(DATA_DIR, "memory");
fs.mkdirSync(MEMORY_DIR, { recursive: true });

// The system prompt as an API "system block" with cache_control: this is what gets cached.
// It is built once and is byte-for-byte identical for every bot and every call (that is what makes
// the cache hit).
const SYSTEM_BLOCKS = [{ type: "text", text: SYSTEM_PROMPT, cache_control: { type: "ephemeral" } }];
const MEMORY_BLOCKS = [{ type: "text", text: MEMORY_SYSTEM }];

const ago = (ms) => (ms < 15000 ? "now" : ms < 90000 ? `${Math.round(ms / 1000)} s ago` : `${Math.round(ms / 60000)} min ago`);

export class Brain {
  /** `body` is the CivBot: it supplies state and runs the chosen action. */
  constructor(body) {
    this.body = body;
    this.name = body.def.name;
    this.events = [];        // recent events: { at, text, seen }
    this.history = [];       // what happened since the last memory update (for the memory summary)
    this.timer = null;       // the scheduled next call
    this.thinking = false;   // a call is in progress
    this.lastCall = 0;
    this.decisions = 0;
    this.memoryFile = path.join(MEMORY_DIR, `${this.name}.json`);
    this.memory = "";
    try {
      this.memory = JSON.parse(fs.readFileSync(this.memoryFile, "utf8")).memory || "";
    } catch {
      // first time this bot plays
    }
  }

  /** Something happened. Remember it and schedule an AI call (respecting the cooldown). */
  notify(text) {
    const ev = { at: Date.now(), text, seen: false };
    this.events.push(ev);
    if (this.events.length > config.ai.recentEvents) this.events.shift();
    this.history.push(text);
    if (this.history.length > 30) this.history.shift();
    this.schedule();
  }

  schedule() {
    if (this.timer || this.thinking || this.stopped) return; // already waiting: this event joins that batch
    const cooldown = config.ai.cooldownMs * cooldownMultiplier();
    const wait = Math.max(0, this.lastCall + cooldown - Date.now());
    this.timer = setTimeout(() => this.think(), wait);
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.timer);
    this.timer = null;
  }

  /** The small, changing part of the prompt. */
  buildMessage(stateText) {
    const d = this.body.def;
    const now = Date.now();
    const events = this.events.map((e) => `- ${ago(now - e.at)}: ${e.text}${e.seen ? "" : " [new]"}`).join("\n") || "- nothing yet";
    const task = this.body.currentTask;
    const taskText = task ? `${describe(task.action)} (for ${Math.round((now - task.started) / 1000)} s)` : "none";
    return [
      `You are ${d.name}, preferred role ${d.role}. Personality: ${d.personality}.`,
      `Memory: ${this.memory || "nothing yet, you just arrived."}`,
      `Current task: ${taskText}`,
      `Recent events (oldest first):\n${events}`,
      `State:\n${stateText}`,
      `Actions: ${ACTION_LIST}. Reply with one JSON object.`,
    ].join("\n");
  }

  async think() {
    this.timer = null;
    if (this.stopped || !this.body.ready) return;
    this.thinking = true;
    this.lastCall = Date.now();
    const batch = this.events.filter((e) => !e.seen);
    try {
      const { text: stateText, facts } = this.body.snapshot();
      const user = this.buildMessage(stateText);
      batch.forEach((e) => { e.seen = true; });

      const res = await ask({ bot: this.name, kind: "decide", system: SYSTEM_BLOCKS, user, maxTokens: config.ai.maxTokens, schema: ACTION_SCHEMA });
      let action;
      if (res.text) {
        const parsed = parseAction(res.text);
        if (parsed.ok) action = parsed.action;
        else log.warn(this.name, `unusable reply (${parsed.error}): ${res.text.slice(0, 120)}`);
      }
      if (!action) {
        action = fallbackAction(facts);
        if (res.reason === "budget" && config.budget.onExhausted === "logoff") {
          log.info(this.name, "daily budget used up: logging off");
          this.body.quit("budget used up");
          return;
        }
        log.info(this.name, `fallback (${res.reason || "invalid reply"}): ${describe(action)}`);
      } else {
        log.info(this.name, `decided ${describe(action)}${res.tokens ? `  [in ${res.tokens.input}+${res.tokens.cacheRead}r+${res.tokens.cacheWrite}w out ${res.tokens.output} $${res.cost.toFixed(6)}]` : ""}`);
        this.history.push(`I decided: ${describe(action)}`);
      }
      this.decisions++;
      this.body.perform(action);

      if (res.text && this.decisions % config.ai.memoryEveryDecisions === 0) this.updateMemory(); // runs alongside, not awaited
    } catch (e) {
      log.error(this.name, `thinking failed: ${e.message}`);
    } finally {
      this.thinking = false;
      // Events that arrived while we were waiting for the AI go into the next call.
      if (this.events.some((e) => !e.seen)) this.schedule();
    }
  }

  /** Rewrite the short memory summary from the old one plus what happened since. */
  async updateMemory() {
    const user = `Old memory: ${this.memory || "(none)"}\n\nSince then:\n${this.history.map((h) => `- ${h}`).join("\n")}`;
    const res = await ask({ bot: this.name, kind: "memory", system: MEMORY_BLOCKS, user, maxTokens: config.ai.memoryMaxTokens });
    if (!res.text) return;
    this.memory = res.text.replace(/\s+/g, " ").split(" ").slice(0, 300).join(" ");
    this.history = [];
    this.saveMemory();
    log.info(this.name, `memory updated (${this.memory.split(" ").length} words)`);
  }

  saveMemory() {
    fs.writeFileSync(this.memoryFile, JSON.stringify({ name: this.name, memory: this.memory, updatedAt: new Date().toISOString() }, null, 2));
  }
}
