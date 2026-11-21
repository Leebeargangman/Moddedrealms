// What a villager costs, measured: the average cost per villager per minute over the last real sessions
// (from the session reports in data/reports). The website multiplies it by villagers x minutes x days to
// show what a schedule will cost before you save it.

import fs from "node:fs";
import path from "node:path";
import { DATA_DIR, config } from "../config.js";

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
  const elderPerSession = config.leader?.enabled ? 0.06 : 0; // ~12 bigger-model calls a 3-hour session
  cached = { at: Date.now(), value: { perBotMinute, measuredSessions: sessions, elderPerSession } };
  return cached.value;
}
