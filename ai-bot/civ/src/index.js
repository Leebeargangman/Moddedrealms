// Entry point: `node src/index.js [--bots N] [--minutes M] [--mock]`
//
// Starts the bots one after another (a few seconds apart so the server isn't flooded), prints a
// token & cost summary every few minutes, and on stop (Ctrl+C, or after --minutes) logs the bots off
// cleanly, saves their memories and writes the session report.

import { Vec3 } from "vec3";
import { config, options } from "./config.js";
import { log } from "./log.js";
import { CivBot } from "./bot/civbot.js";
import { summaryText, writeSessionReport } from "./usage/report.js";

const c = config.village.centre;
const village = { pos: c ? new Vec3(c.x, c.y, c.z) : null }; // shared by all bots
const bots = [];

if (!options.mock && !process.env.ANTHROPIC_API_KEY) {
  log.error("civ", "ANTHROPIC_API_KEY is missing. Put it in ai-bot/civ/.env, or run with --mock to test for free.");
  process.exit(1);
}

log.info("civ", `starting ${config.botCount} bot(s) on ${config.server.host}:${config.server.port}` +
  ` · model ${options.mock ? "MOCK (no API calls)" : config.ai.model} · budget $${config.budget.dailyUsd}/day` +
  (options.minutes ? ` · stopping after ${options.minutes} min` : ""));

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
