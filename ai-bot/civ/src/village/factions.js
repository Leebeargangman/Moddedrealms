// Civilisations in "Free for all" mode: the villagers create them (found), join and leave them, and
// their leaders set relations with other civilisations (war, peace, ally). Plain code; stored in civ.db.
// In the other modes the civilisations come from the website settings instead (see roster.factionNames).

import { db, kv } from "./db.js";
import * as settings from "../settings.js";

db.exec(`
  CREATE TABLE IF NOT EXISTS civs (
    name     TEXT PRIMARY KEY,
    founder  TEXT NOT NULL,
    x INTEGER, y INTEGER, z INTEGER,      -- where it was founded: its village centre
    created  INTEGER NOT NULL,
    day      INTEGER,
    active   INTEGER NOT NULL DEFAULT 1   -- 0 once everyone has left or died
  );
  CREATE TABLE IF NOT EXISTS relations (
    a TEXT NOT NULL, b TEXT NOT NULL,      -- civilisation names, a < b
    state TEXT NOT NULL,                   -- war / peace / ally
    since INTEGER NOT NULL,
    PRIMARY KEY (a, b)
  );
`);
const q = {
  active: db.prepare("SELECT * FROM civs WHERE active = 1 ORDER BY created"),
  get: db.prepare("SELECT * FROM civs WHERE name = ? COLLATE NOCASE"),
  add: db.prepare("INSERT INTO civs (name, founder, x, y, z, created, day) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(name) DO UPDATE SET founder = excluded.founder, x = excluded.x, y = excluded.y, z = excluded.z, created = excluded.created, day = excluded.day, active = 1"),
  close: db.prepare("UPDATE civs SET active = 0 WHERE name = ?"),
  rel: db.prepare("SELECT state FROM relations WHERE a = ? AND b = ?"),
  setRel: db.prepare("INSERT INTO relations (a, b, state, since) VALUES (?, ?, ?, ?) ON CONFLICT(a, b) DO UPDATE SET state = excluded.state, since = excluded.since"),
  relsOf: db.prepare("SELECT * FROM relations WHERE a = ? OR b = ?"),
};

export const isFree = () => settings.get().mode === "free";
export const active = () => q.active.all().map((r) => ({ ...r }));
export const get = (name) => { const r = q.get.get(name); return r && r.active ? { ...r } : null; };

/** A new civilisation founded by `founder` at `pos`. Returns { ok, detail, name }. */
export function found(founder, name, pos, day, isWet = () => false) {
  const clean = String(name || "").replace(/[^\w '-]/g, "").trim().slice(0, 20);
  if (clean.length < 2) return { ok: false, detail: "a civilisation needs a name (2-20 letters)" };
  if (get(clean)) return { ok: false, detail: `${clean} already exists: join it or choose another name` };
  if (active().length >= 8) return { ok: false, detail: "there are already 8 civilisations: join one" };
  if (isWet(pos)) return { ok: false, detail: "this spot is in or right next to water: found your civilisation on dry land (villagers drown, and drowned live in water)" };
  const near = active().find((c) => Math.hypot(c.x - pos.x, c.z - pos.z) < 40);
  if (near) return { ok: false, detail: `too close to ${near.name}'s village: found yours at least 40 blocks away` };
  q.add.run(clean, founder, Math.round(pos.x), Math.round(pos.y), Math.round(pos.z), Date.now(), day ?? null);
  return { ok: true, detail: `founded ${clean} here`, name: clean };
}

export function close(name) { q.close.run(name); }

const pair = (x, y) => (x < y ? [x, y] : [y, x]);
/** "war", "peace" or "ally" between two civilisations ("neutral" if never set). */
export function relation(x, y) {
  if (!x || !y || x === y) return "same";
  const [a, b] = pair(x, y);
  return q.rel.get(a, b)?.state || "neutral";
}
export function setRelation(x, y, state) {
  const [a, b] = pair(x, y);
  q.setRel.run(a, b, state, Date.now());
}
/** e.g. ["at war with Blue", "allied with Gold"] */
export function relationsText(name) {
  return q.relsOf.all(name, name).filter((r) => r.state !== "peace").map((r) => `${r.state === "war" ? "at war with" : "allied with"} ${r.a === name ? r.b : r.a}`);
}

export { kv };
