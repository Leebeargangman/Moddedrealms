// The usage ledger: one row per API call in data/usage.db (SQLite, built into Node - no extra package).
//
// Every call is logged with: time, bot, model, input tokens, cache-read tokens, cache-write tokens,
// output tokens and the cost in US dollars (worked out from the pricing table in config.json).
// You can open the file with any SQLite viewer, or run `npm run report` for a summary.

import path from "node:path";
import { DatabaseSync } from "node:sqlite";
import { config, DATA_DIR } from "../config.js";

const db = new DatabaseSync(path.join(DATA_DIR, "usage.db"));
db.exec(`
  CREATE TABLE IF NOT EXISTS api_calls (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    at          TEXT    NOT NULL,   -- ISO time of the call
    day         TEXT    NOT NULL,   -- budget day (YYYY-MM-DD in the budget timezone)
    session     TEXT    NOT NULL,   -- which run of the program
    bot         TEXT    NOT NULL,
    kind        TEXT    NOT NULL,   -- "decide" (pick next goal) or "memory" (update the memory summary)
    model       TEXT    NOT NULL,
    input       INTEGER NOT NULL,   -- uncached input tokens (full price)
    cache_read  INTEGER NOT NULL,   -- input tokens served from the prompt cache (10% price)
    cache_write INTEGER NOT NULL,   -- input tokens written to the prompt cache (125% price)
    output      INTEGER NOT NULL,
    cost        REAL    NOT NULL,   -- US dollars
    ms          INTEGER NOT NULL,   -- how long the call took
    ok          INTEGER NOT NULL,   -- 1 = valid reply, 0 = error or invalid JSON (fallback used)
    note        TEXT
  );
  CREATE INDEX IF NOT EXISTS api_calls_day ON api_calls(day);
  CREATE INDEX IF NOT EXISTS api_calls_session ON api_calls(session);
`);

// Added in Phase 2: what woke the bot up (task done, attacked, chat, night...). Old databases get the column.
if (!db.prepare("PRAGMA table_info(api_calls)").all().some((c) => c.name === "wake")) {
  db.exec("ALTER TABLE api_calls ADD COLUMN wake TEXT");
}

// A session id is just the start time, e.g. "2026-11-20T17-30-00".
export let SESSION = new Date().toISOString().slice(0, 19).replace(/:/g, "-");
export let SESSION_STARTED = Date.now();
/** Start a new session id (the scheduled mode runs one session a day in the same process). */
export function newSession() {
  SESSION = new Date().toISOString().slice(0, 19).replace(/:/g, "-");
  SESSION_STARTED = Date.now();
  return SESSION;
}

// The budget "day" follows UK time (or whatever budget.timezone says), so it resets at midnight there.
export function budgetDay(date = new Date()) {
  return new Intl.DateTimeFormat("en-CA", { timeZone: config.budget.timezone }).format(date);
}

/** Cost in dollars of one call, from config.pricing (prices are per million tokens). */
export function costOf(model, t) {
  const p = config.pricing[model];
  if (!p) return 0; // unknown model (or "mock"): free
  return (t.input * p.input + t.cacheRead * p.cacheRead + t.cacheWrite * p.cacheWrite + t.output * p.output) / 1e6;
}

const insert = db.prepare(`INSERT INTO api_calls
  (at, day, session, bot, kind, wake, model, input, cache_read, cache_write, output, cost, ms, ok, note)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);

/** Save one call. `t` = { input, cacheRead, cacheWrite, output }. Returns its cost. */
export function record({ bot, kind, trigger = null, model, t, ms, ok, note = null }) {
  const cost = costOf(model, t);
  insert.run(new Date().toISOString(), budgetDay(), SESSION, bot, kind, trigger, model,
    t.input, t.cacheRead, t.cacheWrite, t.output, cost, ms, ok ? 1 : 0, note);
  return cost;
}

const SUMS = `COUNT(*) AS calls, COALESCE(SUM(input),0) AS input, COALESCE(SUM(cache_read),0) AS cacheRead,
  COALESCE(SUM(cache_write),0) AS cacheWrite, COALESCE(SUM(output),0) AS output, COALESCE(SUM(cost),0) AS cost,
  COALESCE(SUM(1 - ok),0) AS failed`;
const qDay = db.prepare(`SELECT ${SUMS} FROM api_calls WHERE day = ?`);
const qSession = db.prepare(`SELECT ${SUMS} FROM api_calls WHERE session = ?`);
const qPerBot = db.prepare(`SELECT bot, ${SUMS} FROM api_calls WHERE session = ? GROUP BY bot ORDER BY cost DESC`);
const qPerKind = db.prepare(`SELECT kind, ${SUMS} FROM api_calls WHERE session = ? GROUP BY kind`);
const qPerTrigger = db.prepare(`SELECT COALESCE(wake, kind) AS wake, ${SUMS} FROM api_calls WHERE session = ? GROUP BY 1 ORDER BY calls DESC`);
const qSessions = db.prepare(`SELECT session, MIN(at) AS first, MAX(at) AS last, ${SUMS} FROM api_calls GROUP BY session ORDER BY session DESC LIMIT ?`);

export const totalsForDay = (day = budgetDay()) => ({ ...qDay.get(day) });
export const totalsForSession = (session = SESSION) => ({ ...qSession.get(session) });
export const perBot = (session = SESSION) => qPerBot.all(session).map((r) => ({ ...r }));
export const perKind = (session = SESSION) => qPerKind.all(session).map((r) => ({ ...r }));
export const perTrigger = (session = SESSION) => qPerTrigger.all(session).map((r) => ({ ...r }));
export const recentSessions = (n = 10) => qSessions.all(n).map((r) => ({ ...r }));

/** Spent so far today (budget day), in dollars. */
export const spentToday = () => totalsForDay().cost;
