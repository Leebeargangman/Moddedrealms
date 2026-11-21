// Settings staff can change from the website (admins only, because they change the cost):
//   villagers (botCount), schedule (start, end, days), daily budget, civilisations (names, at war or
//   not, distance between their villages), imposters on/off, and custom instructions for every villager.
// They are stored in data/civ.db and override config.json. Most apply at the next session start
// (the website offers "save and restart the session"); the schedule and budget apply at once.

import { config, options } from "./config.js";
import { kv } from "./village/db.js";

const MODES = ["village", "civilisations", "free"];
const base = {
  mode: "village",           // village | civilisations | free (see README "Modes")
  botCount: config.botCount,
  schedule: { ...config.schedule },
  budget: config.budget.dailyUsd,
  factions: [{ name: "Village" }],
  war: false,
  factionDistance: 150,
  imposters: config.imposters.count > 0,
  instructions: "",
};

export function get() {
  const s = { ...structuredClone(base), ...(kv("settings") || {}) };
  s.schedule = { ...base.schedule, ...(s.schedule || {}) };
  if (!MODES.includes(s.mode)) s.mode = s.factions.length > 1 ? "civilisations" : "village";
  return s;
}

/** Copy the settings into the live config. A --bots flag on the command line (tests) still wins. */
export function apply() {
  const s = get();
  if (!options.botsFlag) config.botCount = s.botCount;
  config.schedule = { ...config.schedule, ...s.schedule };
  config.budget.dailyUsd = s.budget;
  // Imposters only make sense inside one village; with several civilisations the rivalry is the game.
  config.imposters.count = s.imposters && s.mode === "village" ? 2 : 0;
  return s;
}

const cleanName = (n) => String(n || "").replace(/[^\w '-]/g, "").trim().slice(0, 20);
const hhmm = (t) => (/^([01]\d|2[0-3]):[0-5]\d$/.test(String(t)) ? String(t) : null);

/** Validate and store new settings from the website. Returns { ok, detail }. */
export function save(input) {
  const cur = get();
  const next = structuredClone(cur);
  const errors = [];
  if (input.botCount !== undefined) {
    const n = Math.round(Number(input.botCount));
    if (!(n >= 1 && n <= Math.max(20, config.bots.length + 40))) errors.push("villagers must be between 1 and 60");
    else next.botCount = n;
  }
  if (input.schedule) {
    const start = hhmm(input.schedule.start ?? cur.schedule.start), end = hhmm(input.schedule.end ?? cur.schedule.end);
    const days = Array.isArray(input.schedule.days) ? [...new Set(input.schedule.days.map(Number).filter((d) => d >= 0 && d <= 6))] : cur.schedule.days;
    if (!start || !end) errors.push("times must look like 17:30");
    else if (start >= end) errors.push("the session must end after it starts (same day)");
    else next.schedule = { ...cur.schedule, start, end, days };
  }
  if (input.budget !== undefined) {
    const b = Number(input.budget);
    if (!(b >= 0 && b <= 100)) errors.push("the daily budget must be between $0 and $100");
    else next.budget = Math.round(b * 100) / 100;
  }
  if (input.factions) {
    const names = input.factions.map((f) => cleanName(f.name ?? f)).filter(Boolean).slice(0, 4);
    if (!names.length) errors.push("give at least one civilisation a name");
    else if (new Set(names.map((n) => n.toLowerCase())).size !== names.length) errors.push("civilisation names must be different");
    else next.factions = names.map((name) => ({ name }));
  }
  if (input.mode !== undefined) {
    if (!MODES.includes(input.mode)) errors.push("unknown mode");
    else next.mode = input.mode;
  }
  if (input.war !== undefined) next.war = Boolean(input.war);
  if (input.imposters !== undefined) next.imposters = Boolean(input.imposters);
  if (input.factionDistance !== undefined) {
    const d = Math.round(Number(input.factionDistance));
    if (!(d >= 60 && d <= 2000)) errors.push("villages must be 60-2000 blocks apart");
    else next.factionDistance = d;
  }
  if (input.instructions !== undefined) next.instructions = String(input.instructions).replace(/\r/g, "").replace(/§./g, "").slice(0, 1500).trim();
  if (errors.length) return { ok: false, detail: errors.join("; ") };
  // Keep the settings consistent with the mode.
  if (next.mode === "village") { next.factions = [{ name: "Village" }]; next.war = false; }
  if (next.mode === "civilisations") {
    if (next.factions.length < 2) return { ok: false, detail: "set civilisations needs at least 2 names" };
    if (next.botCount < next.factions.length * 2) return { ok: false, detail: "each civilisation needs at least 2 villagers" };
  }
  if (next.mode === "free") { next.imposters = false; next.war = false; }
  kv("settings", next);
  apply();
  return { ok: true, detail: "settings saved", settings: next };
}
