// Entry point.
//
//   node src/index.js [--bots N] [--minutes M] [--mock]   one session now (Ctrl+C or --minutes ends it)
//   node src/index.js --scheduled                          run 24/7 (pm2): a session every day in the
//                                                          schedule window (17:30-20:30 UK by default)
//
// A session: the villagers log in one after another, play, and at the end log off cleanly, save their
// memories and write the session report. Everything else (village, memories, roster) stays in data/, so
// the next session carries on where this one stopped. Staff can also start/stop a session or pause the
// AI from the website's Bots page (see src/web/link.js).

import { monitorEventLoopDelay } from "node:perf_hooks";
import { config, options, provider, model } from "./config.js";
import { KEY_NAME as ANTHROPIC_KEY } from "./ai/providers/anthropic.js";
import { KEY_NAME as OPENAI_KEY } from "./ai/providers/openai.js";
import { log } from "./log.js";
import { CivBot } from "./bot/civbot.js";
import { summaryText, writeSessionReport } from "./usage/report.js";
import * as ledger from "./usage/ledger.js";
import * as board from "./village/board.js";
import * as roster from "./village/roster.js";
import * as blueprints from "./build/blueprints.js";
import { village, start as startVillage, stop as stopVillage } from "./village/village.js";
import { schedule, inWindow, windowText } from "./schedule.js";
import { connectWebsite } from "./web/link.js";
import { startElders } from "./ai/leader.js";
import * as settings from "./settings.js";

settings.apply(); // the website's saved settings override config.json (schedule, villagers, budget...)
import { startStatusServer } from "./web/status.js";

const keyName = provider === "openai" ? OPENAI_KEY : ANTHROPIC_KEY;
if (!options.mock && !process.env[keyName]) {
  log.error("civ", `${keyName} is missing. Put ANTHROPIC_API_KEY (Claude) or OPENAI_API_KEY in ai-bot/civ/.env, or run with --mock to test for free.`);
  process.exit(1);
}

// How late the Node thread runs its work: high numbers mean the bots move jerkily ("lag").
const loopDelay = monitorEventLoopDelay({ resolution: 20 });
loopDelay.enable();
const lag = () => ({ p50: loopDelay.percentile(50) / 1e6, p99: loopDelay.percentile(99) / 1e6, max: loopDelay.max / 1e6 });
const lagText = () => { const l = lag(); return `Event loop lag: p50 ${l.p50.toFixed(0)} ms · p99 ${l.p99.toFixed(0)} ms · max ${l.max.toFixed(0)} ms (under ~20 ms is smooth)`; };

/** The running session, or null. */
let session = null;
const online = () => [...village.bots.values()].filter((b) => b.ready).length || 1;

/** Start a session now. `reason` is shown on the website; `minutes` = stop by itself after that long. */
async function startSession(reason = "manual", minutes = null) {
  if (session) return { ok: false, detail: "a session is already running" };
  settings.apply(); // villagers, civilisations, budget... as last saved on the website
  const sid = ledger.newSession();
  session = { id: sid, started: Date.now(), reason, endsAt: minutes ? Date.now() + minutes * 60000 : null, bots: [], timers: [] };
  log.info("civ", `session ${sid} starting (${reason}): ${config.botCount} villagers on ${config.server.host}:${config.server.port}` +
    ` · model ${options.mock ? "MOCK (no API calls)" : `${model} (${provider})`} · budget $${config.budget.dailyUsd}/day` +
    (session.endsAt ? ` · until ${new Date(session.endsAt).toTimeString().slice(0, 5)}` : ""));

  startVillage(); // creates the villagers the first time, then runs the world rules every 5 s
  for (const f of roster.factionNames()) board.seed(f); // starter jobs for a brand-new village
  village.blueprintText = await blueprints.text();
  village.makeBot = (def) => {
    if (!session || village.stopping) return null;
    const b = new CivBot(def, village);
    village.bots.set(def.name, b);
    session.bots.push(b);
    b.start();
    return b;
  };
  // The living villagers log in one after another (staggered); top up to botCount if some died.
  const living = roster.alive().slice(0, config.botCount);
  while (living.length < config.botCount) living.push(roster.nextVillager(null));
  living.forEach((def, i) => session.timers.push(setTimeout(() => village.makeBot(def), i * config.server.loginDelayMs)));

  session.timers.push(setInterval(() => { console.log("\n" + summaryText(online()) + "\n" + lagText() + "\n"); loopDelay.reset(); }, config.stats.printEveryMinutes * 60000));
  session.timers.push(setInterval(() => { if (lag().p99 > 200) log.warn("civ", lagText()); }, 60000));
  if (session.endsAt) session.timers.push(setTimeout(() => endSession(`${minutes} minutes are up`), minutes * 60000));
  const elders = startElders(); // optional bigger-model adviser (config.leader.enabled)
  if (elders) session.timers.push(elders);
  return { ok: true, detail: `session started (${living.length} villagers logging in)` };
}

/** End the running session: log everyone off cleanly, save memories, write the report. */
async function endSession(why = "stopped") {
  if (!session) return { ok: false, detail: "no session is running" };
  const s = session;
  session = null;
  log.info("civ", `session ${s.id} ending (${why}): logging villagers off`);
  for (const t of s.timers) { clearTimeout(t); clearInterval(t); }
  stopVillage();
  for (const b of village.bots.values()) b.quit("session over");
  village.bots.clear();
  await new Promise((r) => setTimeout(r, 2000)); // let the quit packets go out
  const n = Math.max(1, Math.min(config.botCount, s.bots.length));
  console.log("\n" + summaryText(n) + "\n" + lagText() + "\n");
  const file = writeSessionReport(n, { reason: why, mock: options.mock, villagersStarted: s.bots.length, sessionReason: s.reason });
  log.info("civ", `session report written to ${file}`);
  return { ok: true, detail: `session ended, report ${file.split("/").pop()}` };
}

/** End the session and start a new one (new settings), as one step: nothing may quit in between. */
let restarting = false;
async function restartSession(why, reason, minutes, between = null) {
  restarting = true;
  try {
    await endSession(why);
    const r = between ? await between() : null; // e.g. wipe the village while nobody is logged in
    const started = await startSession(reason, minutes);
    return r || started;
  } finally {
    restarting = false;
  }
}

// --- run modes --------------------------------------------------------------------------------------
let quitting = false;
async function quit(why) {
  if (quitting) return;
  quitting = true;
  await endSession(why);
  process.exit(0);
}
process.on("SIGINT", () => quit("Ctrl+C"));
process.on("SIGTERM", () => quit("stop signal"));

const control = { startSession, endSession, restartSession, getSession: () => session, lag };
connectWebsite(control);
startStatusServer(control);

if (options.scheduled) {
  // 24/7 under pm2: check the clock every 30 s. Inside the window -> make sure a session runs;
  // after the window -> end the session the schedule started (manual sessions end on their own timer).
  log.info("civ", `scheduled mode: sessions ${windowText()} (${schedule().timezone}); waiting for the window`);
  const check = async () => {
    const inside = inWindow();
    if (inside && !session) await startSession("schedule");
    else if (!inside && session && session.reason === "schedule") await endSession("schedule window over");
  };
  setInterval(() => check().catch((e) => log.error("civ", e.message)), 30000);
  check();
} else {
  await startSession("manual", options.minutes);
  // One-off run: when the session ends (--minutes, or from the website), the program ends too.
  setInterval(() => { if (!session && !quitting && !restarting) quit("session over"); }, 5000);
}
