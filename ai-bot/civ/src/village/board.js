// The village task board: a shared list of jobs, plain code (no AI). Stored in data/civ.db (SQLite),
// so it survives restarts.
//
// - Any villager can POST a job ("gather oak_log x32"), and the village posts starter jobs itself.
// - A villager CLAIMS a job; doing it runs the normal action, and when that action finishes the job
//   is marked done (or goes back to open if it failed).
// - Claimed jobs nobody finished within 15 minutes go back to open, so a dead or stuck bot can't block one.
//
// Each bot sees a few lines of the board in its prompt, so they can split the work without long chats.

import { log } from "../log.js";
import { db } from "./db.js";

const CLAIM_EXPIRES_MS = 15 * 60000;
const MAX_OPEN = 12;          // keep the board short: it is part of every prompt
const BOARD_ACTIONS = new Set(["gather", "craft", "goto", "explore", "attack"]); // jobs a bot can actually do

db.exec(`
  CREATE TABLE IF NOT EXISTS tasks (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    action     TEXT NOT NULL,    -- gather / craft / goto / explore / attack
    target     TEXT,             -- e.g. oak_log
    amount     INTEGER,
    posted_by  TEXT NOT NULL,    -- a villager's name, or "village" for starter jobs
    posted_at  INTEGER NOT NULL,
    status     TEXT NOT NULL DEFAULT 'open',   -- open / claimed / done
    claimed_by TEXT,
    claimed_at INTEGER,
    done_at    INTEGER,
    result     TEXT
  );
  CREATE INDEX IF NOT EXISTS tasks_status ON tasks(status);
`);

const q = {
  insert: db.prepare("INSERT INTO tasks (action, target, amount, posted_by, posted_at) VALUES (?, ?, ?, ?, ?)"),
  get: db.prepare("SELECT * FROM tasks WHERE id = ?"),
  sameOpen: db.prepare("SELECT id FROM tasks WHERE status != 'done' AND action = ? AND COALESCE(target,'') = COALESCE(?,'')"),
  countOpen: db.prepare("SELECT COUNT(*) AS n FROM tasks WHERE status != 'done'"),
  open: db.prepare("SELECT * FROM tasks WHERE status != 'done' ORDER BY status = 'claimed', id LIMIT ?"),
  claim: db.prepare("UPDATE tasks SET status = 'claimed', claimed_by = ?, claimed_at = ? WHERE id = ? AND status = 'open'"),
  finish: db.prepare("UPDATE tasks SET status = 'done', done_at = ?, result = ? WHERE id = ?"),
  reopen: db.prepare("UPDATE tasks SET status = 'open', claimed_by = NULL, claimed_at = NULL, result = ? WHERE id = ?"),
  expire: db.prepare("UPDATE tasks SET status = 'open', claimed_by = NULL, claimed_at = NULL WHERE status = 'claimed' AND claimed_at < ?"),
  mine: db.prepare("SELECT * FROM tasks WHERE status = 'claimed' AND claimed_by = ?"),
  releaseAll: db.prepare("UPDATE tasks SET status = 'open', claimed_by = NULL, claimed_at = NULL WHERE status = 'claimed' AND claimed_by = ?"),
  total: db.prepare("SELECT COUNT(*) AS n FROM tasks"),
  doneSince: db.prepare("SELECT COUNT(*) AS n FROM tasks WHERE status = 'done' AND done_at >= ?"),
};

const label = (t) => [t.action, t.target, t.amount ? `x${t.amount}` : ""].filter(Boolean).join(" ");

/** Post a job. Returns { ok, id?, detail }. Duplicates of an unfinished job are refused. */
export function post({ by, action, target = null, amount = null }) {
  if (!BOARD_ACTIONS.has(action)) return { ok: false, detail: `the board only takes ${[...BOARD_ACTIONS].join("/")} jobs` };
  if (q.sameOpen.get(action, target)) return { ok: false, detail: `"${label({ action, target, amount })}" is already on the board` };
  if (q.countOpen.get().n >= MAX_OPEN) return { ok: false, detail: "the board is full: finish some jobs first" };
  const id = Number(q.insert.run(action, target, amount, by, Date.now()).lastInsertRowid);
  log.info("board", `#${id} ${label({ action, target, amount })} posted by ${by}`);
  return { ok: true, id, detail: `posted job #${id}` };
}

/** Claim an open job. Returns { ok, task?, detail }. A bot holds at most one job. */
export function claim(id, bot) {
  q.expire.run(Date.now() - CLAIM_EXPIRES_MS);
  const t = q.get.get(id);
  if (!t) return { ok: false, detail: `there is no job #${id}` };
  if (t.status === "done") return { ok: false, detail: `job #${id} is already done` };
  if (t.status === "claimed" && t.claimed_by !== bot) return { ok: false, detail: `job #${id} is taken by ${t.claimed_by}` };
  q.releaseAll.run(bot); // drop any other job this bot held
  q.claim.run(bot, Date.now(), id);
  log.info("board", `#${id} claimed by ${bot}`);
  return { ok: true, task: { ...q.get.get(id) }, detail: `took job #${id}` };
}

/** The job finished: done if it worked, back to open if not. */
export function finish(id, ok, result) {
  if (ok) q.finish.run(Date.now(), String(result).slice(0, 120), id);
  else q.reopen.run(String(result).slice(0, 120), id);
  log.info("board", `#${id} ${ok ? "done" : "back to open"}: ${result}`);
}

/** Free a bot's job (it logged off or died). */
export const release = (bot) => q.releaseAll.run(bot);

/** The few lines of the board this bot sees in its prompt. */
export function text(bot) {
  q.expire.run(Date.now() - CLAIM_EXPIRES_MS);
  const rows = q.open.all(6);
  if (!rows.length) return "Task board: empty (post jobs the village needs).";
  // Show why the last try failed, so nobody claims an impossible job again without fixing the reason.
  return "Task board:\n" + rows.map((t) => `- #${t.id} ${label(t)} · ${t.status === "open" ? "open" : t.claimed_by === bot ? "taken by you" : `taken by ${t.claimed_by}`} · from ${t.posted_by}` +
    (t.status === "open" && t.result ? ` · last try failed: ${t.result.slice(0, 60)}` : "")).join("\n");
}

/** Starter jobs so a new village has something to split up. Only posted on an empty board. */
export function seed() {
  if (q.total.get().n > 0) return;
  for (const job of [
    { action: "gather", target: "wood", amount: 32 },
    { action: "explore" },
    { action: "craft", target: "wooden_pickaxe", amount: 1 },
    { action: "gather", target: "stone", amount: 24 },
  ]) post({ by: "village", ...job });
}

export const stats = (since) => ({ open: q.countOpen.get().n, doneSince: q.doneSince.get(since).n });
export { label };
