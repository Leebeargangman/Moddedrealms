// Entry point: `node src/index.js [--bots N] [--minutes M] [--mock]`
//
// Starts the bots one after another (a few seconds apart so the server isn't flooded), prints a
// token & cost summary every few minutes, and on stop (Ctrl+C, or after --minutes) logs the bots off
// cleanly, saves their memories and writes the session report.

import { config, options, provider, model } from "./config.js";
import { KEY_NAME as ANTHROPIC_KEY } from "./ai/providers/anthropic.js";
import { KEY_NAME as OPENAI_KEY } from "./ai/providers/openai.js";
import { log } from "./log.js";
import { CivBot } from "./bot/civbot.js";
import { summaryText, writeSessionReport } from "./usage/report.js";
import * as board from "./village/board.js";
import * as roster from "./village/roster.js";
import * as blueprints from "./build/blueprints.js";
import { village, start as startVillage } from "./village/village.js";

const bots = []; // every bot started this session (for the report)

const keyName = provider === "openai" ? OPENAI_KEY : ANTHROPIC_KEY;
if (!options.mock && !process.env[keyName]) {
  log.error("civ", `${keyName} is missing. Put ANTHROPIC_API_KEY (Claude) or OPENAI_API_KEY in ai-bot/civ/.env, or run with --mock to test for free.`);
  process.exit(1);
}

log.info("civ", `starting ${config.botCount} bot(s) on ${config.server.host}:${config.server.port}` +
  ` · model ${options.mock ? "MOCK (no API calls)" : `${model} (${provider})`} · budget $${config.budget.dailyUsd}/day` +
  (options.minutes ? ` · stopping after ${options.minutes} min` : ""));

board.seed(); // starter jobs for a brand-new village (only if the board has never had any)
startVillage(); // creates the villagers the first time, then runs the world rules every 5 s
village.blueprintText = await blueprints.text();

/** Start one villager's bot (also used for newcomers who arrive later). */
village.makeBot = (def) => {
  if (stopping) return null;
  const b = new CivBot(def, village);
  village.bots.set(def.name, b);
  bots.push(b);
  b.start();
  return b;
};

// The living villagers log in one after another; top up to botCount if some died last time.
const living = roster.alive().slice(0, config.botCount);
while (living.length < config.botCount) living.push(roster.nextVillager(null));
living.forEach((def, i) => setTimeout(() => village.makeBot(def), i * config.server.loginDelayMs));

const online = () => [...village.bots.values()].filter((b) => b.ready).length || 1;
// How late the Node thread runs its work: high numbers mean the bots move jerkily ("lag").
import { monitorEventLoopDelay } from "node:perf_hooks";
const loopDelay = monitorEventLoopDelay({ resolution: 20 });
loopDelay.enable();
const lagText = () => `Event loop lag: p50 ${(loopDelay.percentile(50) / 1e6).toFixed(0)} ms · p99 ${(loopDelay.percentile(99) / 1e6).toFixed(0)} ms · max ${(loopDelay.max / 1e6).toFixed(0)} ms (under ~20 ms is smooth)`;
const statsTimer = setInterval(() => { console.log("\n" + summaryText(online()) + "\n" + lagText() + "\n"); loopDelay.reset(); }, config.stats.printEveryMinutes * 60000);
setInterval(() => { if (loopDelay.percentile(99) / 1e6 > 200) log.warn("civ", lagText()); }, 60000);

let stopping = false;
async function shutdown(why) {
  if (stopping) return;
  stopping = true;
  village.stopping = true;
  log.info("civ", `stopping (${why}): logging bots off`);
  clearInterval(statsTimer);
  for (const b of village.bots.values()) b.quit("session over");
  await new Promise((r) => setTimeout(r, 1500)); // let the quit packets go out
  const n = Math.max(1, Math.min(config.botCount, bots.length));
  console.log("\n" + summaryText(n) + "\n");
  const file = writeSessionReport(n, { reason: why, mock: options.mock, villagersStarted: bots.length });
  log.info("civ", `session report written to ${file}`);
  process.exit(0);
}

process.on("SIGINT", () => shutdown("Ctrl+C"));
process.on("SIGTERM", () => shutdown("stop signal"));
if (options.minutes) setTimeout(() => shutdown(`${options.minutes} minute test finished`), options.minutes * 60000);
