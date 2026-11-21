// Entry point: `node src/index.js [--bots N] [--minutes M] [--mock]`
//
// Starts the bots one after another (a few seconds apart so the server isn't flooded), prints a
// token & cost summary every few minutes, and on stop (Ctrl+C, or after --minutes) logs the bots off
// cleanly, saves their memories and writes the session report.

import { Vec3 } from "vec3";
import { config, options, provider, model } from "./config.js";
import { KEY_NAME as ANTHROPIC_KEY } from "./ai/providers/anthropic.js";
import { KEY_NAME as OPENAI_KEY } from "./ai/providers/openai.js";
import { log } from "./log.js";
import { CivBot } from "./bot/civbot.js";
import { summaryText, writeSessionReport } from "./usage/report.js";
import * as board from "./village/board.js";

const c = config.village.centre;
const village = { pos: c ? new Vec3(c.x, c.y, c.z) : null }; // shared by all bots
const bots = [];

const keyName = provider === "openai" ? OPENAI_KEY : ANTHROPIC_KEY;
if (!options.mock && !process.env[keyName]) {
  log.error("civ", `${keyName} is missing. Put ANTHROPIC_API_KEY (Claude) or OPENAI_API_KEY in ai-bot/civ/.env, or run with --mock to test for free.`);
  process.exit(1);
}

log.info("civ", `starting ${config.botCount} bot(s) on ${config.server.host}:${config.server.port}` +
  ` · model ${options.mock ? "MOCK (no API calls)" : `${model} (${provider})`} · budget $${config.budget.dailyUsd}/day` +
  (options.minutes ? ` · stopping after ${options.minutes} min` : ""));

board.seed(); // starter jobs for a brand-new village (only if the board has never had any)

config.bots.slice(0, config.botCount).forEach((def, i) => {
  setTimeout(() => {
    if (stopping) return;
    const b = new CivBot(def, village);
    bots.push(b);
    b.start();
  }, i * config.server.loginDelayMs);
});

const statsTimer = setInterval(() => console.log("\n" + summaryText(bots.length) + "\n"), config.stats.printEveryMinutes * 60000);

let stopping = false;
async function shutdown(why) {
  if (stopping) return;
  stopping = true;
  log.info("civ", `stopping (${why}): logging bots off`);
  clearInterval(statsTimer);
  for (const b of bots) b.quit("session over");
  await new Promise((r) => setTimeout(r, 1500)); // let the quit packets go out
  console.log("\n" + summaryText(bots.length) + "\n");
  const file = writeSessionReport(bots.length, { reason: why, mock: options.mock });
  log.info("civ", `session report written to ${file}`);
  process.exit(0);
}

process.on("SIGINT", () => shutdown("Ctrl+C"));
process.on("SIGTERM", () => shutdown("stop signal"));
if (options.minutes) setTimeout(() => shutdown(`${options.minutes} minute test finished`), options.minutes * 60000);
