// What a villager costs, measured: the average cost per villager per minute over the last real sessions
// (from the session reports in data/reports). The website multiplies it by villagers x minutes x days to
// show what a schedule will cost before you save it.

import fs from "node:fs";
import path from "node:path";
import { DATA_DIR, config, provider, model as currentModel } from "../config.js";
import { DatabaseSync } from "node:sqlite";

// Before any real session has been measured: our 20-villager tests came to about $0.85 per 3 hours.
const FALLBACK_PER_BOT_MINUTE = 0.85 / 20 / 180;

let cached = { at: 0, value: null };

export function costModel() {
  if (Date.now() - cached.at < 5 * 60000 && cached.value) return cached.value;
  const dir = path.join(DATA_DIR, "reports");
  let cost = 0, botMinutes = 0, sessions = 0;
  try {
    const files = fs.readdirSync(dir).filter((f) => f.endsWith(".json")).sort().slice(-10);
    for (const f of files) {
      const r = JSON.parse(fs.readFileSync(path.join(dir, f), "utf8"));
      if (r.mock || !r.totals?.cost || r.minutes < 5 || !r.botsOnline) continue; // skip mock and tiny runs
      cost += r.totals.cost;
      botMinutes += r.minutes * r.botsOnline;
      sessions++;
    }
  } catch {
    // no reports yet
  }
  const perBotMinute = botMinutes > 0 ? cost / botMinutes : FALLBACK_PER_BOT_MINUTE;
  // The average villager call, measured: tokens not cached, read from the cache, and written.
  let avg = { uncached: 700, cached: 2100, out: 55 };
  try {
    const db = new DatabaseSync(path.join(DATA_DIR, "usage.db"), { readOnly: true });
    const r = db.prepare("SELECT AVG(input + cache_write) u, AVG(cache_read) c, AVG(output) o, COUNT(*) n FROM api_calls WHERE kind = 'decide' AND model != 'mock' AND id > (SELECT MAX(id) FROM api_calls) - 5000").get();
    if (r?.n > 50) avg = { uncached: r.u, cached: r.c, out: r.o };
    db.close();
  } catch { /* no data yet */ }
  const price = (m, t) => {
    const p = config.pricing[m];
    return p ? (t.uncached * p.input + t.cached * p.cacheRead + t.out * p.output) / 1e6 : 0;
  };
  const smartModel = provider === "openai" ? config.leader.openaiModel : config.leader.model;
  const normalCall = price(currentModel === "mock" ? config.ai.model : currentModel, avg);
  const smartCall = price(smartModel, avg);
  // An elder consultation: a ~1,500-token summary (not cached) and a ~250-token plan.
  const elderCall = price(smartModel, { uncached: 1500, cached: 0, out: 250 });
  const callsPerBotMinute = normalCall > 0 ? perBotMinute / normalCall : 1.8;
  cached = { at: Date.now(), value: { perBotMinute, measuredSessions: sessions, avg, normalCall, smartModel, smartCall, elderCall, callsPerBotMinute, elderEveryMinutes: config.leader.everyMinutes } };
  return cached.value;
}
