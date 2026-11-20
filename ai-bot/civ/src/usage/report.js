// Turns the usage ledger into readable summaries:
//  - summaryText(): the live summary printed every few minutes
//  - writeSessionReport(): the report file written when a session ends (data/reports/)
// Run it on its own (`npm run report`) to see the last sessions.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { config, DATA_DIR } from "../config.js";
import * as ledger from "./ledger.js";

const usd = (n) => `$${n.toFixed(n < 0.1 ? 5 : 3)}`;
const int = (n) => Math.round(n).toLocaleString("en-GB");

/** Share of input tokens that came from the cache (the higher, the cheaper). */
export function cacheHitRate(t) {
  const all = t.input + t.cacheRead + t.cacheWrite;
  return all ? t.cacheRead / all : 0;
}

/**
 * Build the numbers for a session.
 * `botsOnline` = how many bots are running, used to project the cost for 20 bots.
 */
export function sessionStats(botsOnline) {
  const s = ledger.totalsForSession();
  const today = ledger.totalsForDay();
  const minutes = Math.max((Date.now() - ledger.SESSION_STARTED) / 60000, 0.01);
  const sessionMinutes = config.stats.sessionMinutes;
  const perBotMinute = botsOnline ? s.cost / minutes / botsOnline : 0;
  return {
    session: ledger.SESSION,
    minutes,
    botsOnline,
    totals: s,
    today,
    avgPerCall: s.calls ? s.cost / s.calls : 0,
    callsPerBotPerMinute: botsOnline ? s.calls / minutes / botsOnline : 0,
    avgInputPerCall: s.calls ? (s.input + s.cacheRead + s.cacheWrite) / s.calls : 0,
    avgOutputPerCall: s.calls ? s.output / s.calls : 0,
    cacheHitRate: cacheHitRate(s),
    projectedSession: perBotMinute * botsOnline * sessionMinutes,
    projected20Bots: perBotMinute * 20 * sessionMinutes,
    perBot: ledger.perBot(),
    perKind: ledger.perKind(),
    budget: config.budget.dailyUsd,
  };
}

export function summaryText(botsOnline) {
  const s = sessionStats(botsOnline);
  const t = s.totals;
  const hours = (config.stats.sessionMinutes / 60).toFixed(1);
  const lines = [
    `──── Token & cost summary · ${s.minutes.toFixed(1)} min · ${s.botsOnline} bot(s) ────`,
    `Calls: ${t.calls} (${t.failed} fell back to the default action) · ${s.callsPerBotPerMinute.toFixed(2)} calls per bot per minute`,
    `Tokens: input ${int(t.input)} · cache read ${int(t.cacheRead)} · cache write ${int(t.cacheWrite)} · output ${int(t.output)}`,
    `Per call: ${int(s.avgInputPerCall)} input tokens (all kinds) · ${int(s.avgOutputPerCall)} output tokens · ${usd(s.avgPerCall)}`,
    `Cache hit rate: ${(s.cacheHitRate * 100).toFixed(1)}%`,
    `Cost this session: ${usd(t.cost)} · today: ${usd(s.today.cost)} of ${usd(s.budget)} budget`,
    `Projected: ${usd(s.projectedSession)} for ${hours} h with ${s.botsOnline} bot(s) · ${usd(s.projected20Bots)} for ${hours} h with 20 bots`,
  ];
  if (s.perBot.length) {
    lines.push("Per bot: " + s.perBot.map((b) => `${b.bot} ${b.calls} calls ${usd(b.cost)}`).join(" · "));
  }
  return lines.join("\n");
}

/** Write data/reports/session-<id>.md (readable) and .json (for tools). Returns the .md path. */
export function writeSessionReport(botsOnline, extra = {}) {
  const s = sessionStats(botsOnline);
  const dir = path.join(DATA_DIR, "reports");
  fs.mkdirSync(dir, { recursive: true });
  const base = path.join(dir, `session-${s.session}`);
  fs.writeFileSync(`${base}.json`, JSON.stringify({ ...s, ...extra }, null, 2));

  const t = s.totals;
  const md = [
    `# Session report ${s.session}`,
    "",
    `- Length: ${s.minutes.toFixed(1)} minutes, ${s.botsOnline} bot(s), model ${config.ai.model}`,
    `- API calls: ${t.calls} (${t.failed} fell back to the default action)`,
    `- Calls per bot per minute: ${s.callsPerBotPerMinute.toFixed(2)}`,
    `- Input tokens: ${int(t.input)} uncached, ${int(t.cacheRead)} cache read, ${int(t.cacheWrite)} cache write`,
    `- Output tokens: ${int(t.output)}`,
    `- Average per call: ${int(s.avgInputPerCall)} input, ${int(s.avgOutputPerCall)} output, ${usd(s.avgPerCall)}`,
    `- Cache hit rate: ${(s.cacheHitRate * 100).toFixed(1)}%`,
    `- **Cost: ${usd(t.cost)}** (today so far ${usd(s.today.cost)} of the ${usd(s.budget)} daily budget)`,
    `- Projection for a ${(config.stats.sessionMinutes / 60).toFixed(1)} h session: ${usd(s.projectedSession)} with ${s.botsOnline} bot(s), **${usd(s.projected20Bots)} with 20 bots**`,
    "",
    "| Bot | Calls | Input | Cache read | Cache write | Output | Cost |",
    "|---|---:|---:|---:|---:|---:|---:|",
    ...s.perBot.map((b) => `| ${b.bot} | ${b.calls} | ${int(b.input)} | ${int(b.cacheRead)} | ${int(b.cacheWrite)} | ${int(b.output)} | ${usd(b.cost)} |`),
    "",
    "| Kind | Calls | Cost |",
    "|---|---:|---:|",
    ...s.perKind.map((k) => `| ${k.kind} | ${k.calls} | ${usd(k.cost)} |`),
    "",
  ].join("\n");
  fs.writeFileSync(`${base}.md`, md);
  return `${base}.md`;
}

// `npm run report`: list the last sessions straight from the ledger.
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const rows = ledger.recentSessions(15);
  if (!rows.length) console.log("No API calls logged yet.");
  for (const r of rows) {
    console.log(`${r.session}  ${String(r.calls).padStart(5)} calls  in ${int(r.input)} / cache ${int(r.cacheRead)}r ${int(r.cacheWrite)}w / out ${int(r.output)}  ${usd(r.cost)}  hit ${(cacheHitRate(r) * 100).toFixed(0)}%`);
  }
  const today = ledger.totalsForDay();
  console.log(`Today (${ledger.budgetDay()}): ${today.calls} calls, ${usd(today.cost)} of ${usd(config.budget.dailyUsd)}`);
}
