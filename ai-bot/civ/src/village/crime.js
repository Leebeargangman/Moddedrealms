// Imposters, kills, witnesses and clues. Plain code; the imposters' AI only chooses WHEN and WHOM.
//
// Kill rules (config.imposters):
//   - A "kill window" opens every killEveryDays Minecraft days (the first firstKillAfterDays after the
//     imposters were chosen). While it
//     is open the imposters may kill up to killsPerWindow villagers in total; then it closes again.
//   - A kill is only allowed when nobody else (no other villager except the partner imposter, and no
//     real player) is within witnessRadius blocks of the imposter or the victim. Otherwise the action
//     is refused with "too risky", so imposters can't be careless by accident.
//   - The imposter then really has to fight the victim. The victim fights back or runs and remembers
//     who attacked it, so a failed attempt is dangerous for the imposter.
//
// Only witnesses see a death: living villagers within seeRadius of it. Everyone else finds out from
// clues: the victim's dropped items at the death site (a villager who walks past finds them), their own
// memories of who they saw near there, or after a day that the villager is missing.

import { config } from "../config.js";
import { db, kv } from "./db.js";
import * as roster from "./roster.js";
import * as worldlog from "./worldlog.js";

db.exec(`
  CREATE TABLE IF NOT EXISTS deaths (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    victim   TEXT NOT NULL,
    x INTEGER, y INTEGER, z INTEGER,
    at       INTEGER NOT NULL,
    day      INTEGER,
    killer   TEXT,              -- secret: the imposter, or null (mob, fall, lava...)
    found_by TEXT,              -- who found the remains (null = not found yet)
    missing_told INTEGER NOT NULL DEFAULT 0
  );
`);
const q = {
  add: db.prepare("INSERT INTO deaths (victim, x, y, z, at, day, killer) VALUES (?, ?, ?, ?, ?, ?, ?)"),
  unfound: db.prepare("SELECT * FROM deaths WHERE found_by IS NULL"),
  found: db.prepare("UPDATE deaths SET found_by = ? WHERE id = ?"),
  told: db.prepare("UPDATE deaths SET missing_told = 1 WHERE id = ?"),
};
const C = () => config.imposters;

// --- kill window ---------------------------------------------------------------------------------
/** { open, killsLeft, opensDay } for the current Minecraft day. */
export function window(day) {
  const w = kv("killWindow") || { day: null, kills: 0 };
  const last = kv("lastKillWindowDay");
  const firstDay = (kv("impostersAssignedDay") ?? 0) + C().firstKillAfterDays;
  const nextDay = last === null ? firstDay : last + C().killEveryDays;
  if (day === null) return { open: false, killsLeft: 0, opensDay: nextDay };
  if (w.day === day && w.kills > 0) return { open: w.kills < C().killsPerWindow, killsLeft: C().killsPerWindow - w.kills, opensDay: nextDay };
  return { open: day >= nextDay, killsLeft: day >= nextDay ? C().killsPerWindow : 0, opensDay: nextDay };
}

/**
 * Can `imposter` kill `victim` right now? `where` = { name: Vec3 } for every villager and player online.
 * Returns { ok, detail }.
 */
export function canKill(imposter, victim, day, where) {
  if (!roster.isImposter(imposter)) return { ok: false, detail: "you can't do that" };
  const v = roster.get(victim);
  if (!v || v.status !== "alive") return { ok: false, detail: `${victim} is not a living villager` };
  if (v.imposter) return { ok: false, detail: `${v.name} is your fellow imposter` };
  const w = window(day);
  if (!w.open) return { ok: false, detail: `not yet: the next chance is on day ${w.opensDay}` };
  const me = where[imposter], them = where[v.name];
  if (!me || !them) return { ok: false, detail: `${v.name} is not nearby` };
  if (me.distanceTo(them) > 24) return { ok: false, detail: `${v.name} is too far away (get closer, quietly)` };
  const partner = roster.livingImposters().map((i) => i.name);
  const r = C().witnessRadius;
  const seen = Object.entries(where)
    .filter(([n]) => n !== imposter && n !== v.name && !partner.includes(n))
    .filter(([, p]) => p.distanceTo(me) <= r || p.distanceTo(them) <= r)
    .map(([n]) => n);
  if (seen.length) return { ok: false, detail: `too risky: ${seen.slice(0, 3).join(", ")} could see you` };
  return { ok: true, detail: "nobody is watching" };
}

/** Who this imposter could kill right now without witnesses (shown in its secret prompt lines). */
export function easyTargets(imposter, day, where) {
  return roster.alive().filter((v) => !v.imposter && canKill(imposter, v.name, day, where).ok).map((v) => v.name);
}

// The last attack by an imposter on each victim, to know who caused a death a few seconds later.
const attempts = new Map(); // victim -> { by, at }
export function attackStarted(imposter, victim) { attempts.set(victim, { by: imposter, at: Date.now() }); }

/**
 * A villager died (any cause). Records it, uses up a kill if it was murder, and returns who saw it:
 * [{ name, text }] events for witnesses. `where` = positions of everyone online.
 */
export function died(victim, pos, day, where, causeHint) {
  const a = attempts.get(victim);
  const killer = a && Date.now() - a.at < 30000 && roster.isImposter(a.by) ? a.by : null;
  attempts.delete(victim);
  q.add.run(victim, Math.round(pos.x), Math.round(pos.y), Math.round(pos.z), Date.now(), day, killer);
  if (killer) {
    const w = kv("killWindow") || {};
    kv("killWindow", { day, kills: (w.day === day ? w.kills : 0) + 1 });
    kv("lastKillWindowDay", day);
  }
  worldlog.write("secret", `${victim} died at ${Math.round(pos.x)},${Math.round(pos.z)}: ${killer ? `killed by imposter ${killer}` : causeHint || "not murder (mob, fall...)"}.`, { day, isPublic: false });
  const r = C().seeRadius;
  return Object.entries(where)
    .filter(([n, p]) => n !== victim && n !== killer && roster.isAlive(n) && p.distanceTo(pos) <= r)
    .map(([n]) => ({
      name: n,
      text: killer ? `You SAW ${killer} kill ${victim} at ${Math.round(pos.x)},${Math.round(pos.z)}!` : `You saw ${victim} die at ${Math.round(pos.x)},${Math.round(pos.z)} (${causeHint || "it looked like an accident or a monster"}).`,
      wake: "witness",
    }));
}

/** A death site is found by `finder` near `pos`? Returns the finding (or null). */
export function lookForClues(finder, pos, day) {
  for (const d of q.unfound.all()) {
    if (d.victim === finder || d.killer === finder) continue; // the killer doesn't "discover" their own victim
    const dx = d.x - pos.x, dz = d.z - pos.z;
    if (dx * dx + dz * dz > 64 || Math.abs(d.y - pos.y) > 6) continue;
    q.found.run(finder, d.id);
    worldlog.write("death", `${finder} found ${d.victim}'s remains at ${d.x},${d.z}. ${d.victim} is dead.`, { day });
    return { victim: d.victim, x: d.x, z: d.z, at: d.at };
  }
  return null;
}

/** Deaths nobody found for a whole Minecraft day: the village notices the villager is missing. */
export function missing(day) {
  const out = [];
  for (const d of q.unfound.all()) {
    if (d.missing_told || day === null || d.day === null || day - d.day < 1) continue;
    q.told.run(d.id);
    worldlog.write("missing", `${d.victim} is missing: nobody has seen them since day ${d.day}.`, { day });
    out.push(d);
  }
  return out;
}
