// Villagers' own programs ("routines") and building designs. They are DATA, never code: a routine is
// JSON interpreted by civbot.runRoutine(), a design is a layered block grid turned into a blueprint.
// Nothing a villager writes can touch files, the network or the server: it can only chain the same
// actions the villager could choose one by one.
//
// Routine: { "steps": [ step, ... ] }, where a step is one of
//   { "do": { "action": "gather", "target": "wood", "amount": 16 } }     one action (most actions allowed)
//   { "repeat": 3, "steps": [ ... ] }                                     up to 10 times
//   { "if": "<condition>", "then": [ ... ], "else": [ ... ] }
//   { "until": "<condition>", "steps": [ ... ] }                          up to 20 rounds
//   any step can add "try": true to carry on when it fails.
// Conditions: health/food/spare < 10, inventory oak_log >= 16, chest cobblestone > 32, night, day,
//   danger, joined with "and"/"or", "not" in front. Example: "inventory oak_log < 32 and not night".
//
// Design: { "size": [w, h, d], "key": { "P": "planks", "L": "log", "S": "stairs:east", ... },
//           "layers": [ ["LPPPL", "P...P", ...], ... ] }  one layer per height (y = 0 is the ground),
//   one string per row (z), one character per block (x); "." or " " = nothing.

import { db } from "./db.js";

db.exec(`
  CREATE TABLE IF NOT EXISTS routines (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    kind     TEXT NOT NULL,              -- routine / design
    name     TEXT NOT NULL,
    owner    TEXT NOT NULL,
    faction  TEXT,                       -- shared with the owner's civilisation
    body     TEXT NOT NULL,              -- the JSON
    created  INTEGER NOT NULL,
    updated  INTEGER NOT NULL,
    runs     INTEGER NOT NULL DEFAULT 0,
    fails    INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    UNIQUE (kind, name, faction)
  );
`);
const q = {
  upsert: db.prepare(`INSERT INTO routines (kind, name, owner, faction, body, created, updated) VALUES (?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(kind, name, faction) DO UPDATE SET owner = excluded.owner, body = excluded.body, updated = excluded.updated, last_error = NULL`),
  get: db.prepare("SELECT * FROM routines WHERE kind = ? AND name = ? AND COALESCE(faction, '') = ?"),
  list: db.prepare("SELECT * FROM routines WHERE kind = ? AND COALESCE(faction, '') = ? ORDER BY updated DESC LIMIT ?"),
  all: db.prepare("SELECT * FROM routines WHERE kind = ? ORDER BY updated DESC LIMIT ?"),
  result: db.prepare("UPDATE routines SET runs = runs + 1, fails = fails + ?, last_error = ? WHERE id = ?"),
};

// Actions a routine may use. Not: kill, exile, law, vote, diplomacy, found/join/leave, program, run, design.
export const ROUTINE_ACTIONS = new Set(["gather", "craft", "eat", "goto", "follow", "explore", "attack", "flee", "sleep", "hide",
  "deposit", "withdraw", "give", "build", "sign", "read", "chat", "whisper", "post", "claim", "wait"]);

const LIMITS = { steps: 30, depth: 3, repeat: 10, until: 20 };
const cleanName = (n) => String(n || "").toLowerCase().replace(/[^a-z0-9_-]/g, "_").replace(/_+/g, "_").slice(0, 24);

// --- conditions --------------------------------------------------------------------------------------
const ATOM = /^(not\s+)?(?:(health|food|spare)\s*(<=|>=|<|>|==)\s*(\d+)|(inventory|chest)\s+([a-z0-9_]+)\s*(<=|>=|<|>|==)\s*(\d+)|(night|day|danger))$/;
/** Check a condition's syntax. Returns an error message or null. */
export function checkCondition(c) {
  const s = String(c || "").trim().toLowerCase();
  if (!s) return "empty condition";
  for (const part of s.split(/\s+(?:and|or)\s+/)) if (!ATOM.test(part.trim())) return `can't understand "${part.trim()}"`;
  return null;
}
/** Evaluate a condition against facts: { health, food, spare, night, danger, inv(name), chest(name) }. */
export function evalCondition(c, f) {
  const s = String(c).trim().toLowerCase();
  const cmp = (a, op, b) => ({ "<": a < b, "<=": a <= b, ">": a > b, ">=": a >= b, "==": a === b })[op];
  const atom = (part) => {
    const m = ATOM.exec(part.trim());
    if (!m) return false;
    let v;
    if (m[2]) v = cmp(f[m[2]], m[3], Number(m[4]));
    else if (m[5]) v = cmp(m[5] === "inventory" ? f.inv(m[6]) : f.chest(m[6]), m[7], Number(m[8]));
    else v = m[9] === "night" ? f.night : m[9] === "day" ? !f.night : f.danger;
    return m[1] ? !v : v;
  };
  // "a and b or c": evaluate left to right, and before or (split on or first).
  return s.split(/\s+or\s+/).some((orPart) => orPart.split(/\s+and\s+/).every(atom));
}

// --- routines ------------------------------------------------------------------------------------------
/** Validate a routine. Returns { ok, routine?, detail }. */
export function parseRoutine(text) {
  let r;
  try { r = typeof text === "string" ? JSON.parse(text.slice(text.indexOf("{"), text.lastIndexOf("}") + 1)) : text; } catch (e) { return { ok: false, detail: `the routine is not valid JSON (${e.message})` }; }
  const steps = Array.isArray(r) ? r : r?.steps;
  if (!Array.isArray(steps) || !steps.length) return { ok: false, detail: 'a routine needs "steps": [ ... ]' };
  let count = 0;
  const check = (list, depth, path) => {
    if (depth > LIMITS.depth) return `${path}: nested too deep (max ${LIMITS.depth})`;
    for (const [i, st] of list.entries()) {
      const here = `${path}${i + 1}`;
      if (++count > LIMITS.steps) return `too many steps (max ${LIMITS.steps})`;
      if (!st || typeof st !== "object") return `step ${here} is not an object`;
      if (st.do) {
        const a = String(st.do.action || "").toLowerCase();
        if (!ROUTINE_ACTIONS.has(a)) return `step ${here}: "${st.do.action}" can't be used in a routine (allowed: ${[...ROUTINE_ACTIONS].join(", ")})`;
      } else if (st.repeat !== undefined) {
        if (!(Number(st.repeat) >= 1 && Number(st.repeat) <= LIMITS.repeat)) return `step ${here}: repeat must be 1-${LIMITS.repeat}`;
        const e = check(st.steps || [], depth + 1, `${here}.`); if (e) return e;
      } else if (st.if !== undefined) {
        const ce = checkCondition(st.if); if (ce) return `step ${here}: ${ce}`;
        const e1 = check(st.then || [], depth + 1, `${here}.then.`); if (e1) return e1;
        const e2 = check(st.else || [], depth + 1, `${here}.else.`); if (e2) return e2;
      } else if (st.until !== undefined) {
        const ce = checkCondition(st.until); if (ce) return `step ${here}: ${ce}`;
        const e = check(st.steps || [], depth + 1, `${here}.`); if (e) return e;
      } else return `step ${here} needs "do", "repeat", "if" or "until"`;
    }
    return null;
  };
  const err = check(steps, 1, "");
  return err ? { ok: false, detail: err } : { ok: true, routine: { steps }, detail: `${count} steps` };
}

// --- designs ---------------------------------------------------------------------------------------------
const DESIGN_BLOCKS = new Set(["planks", "log", "stairs", "slab", "door", "fence", "torch", "ladder", "chest", "crafting_table",
  "cobblestone", "stone", "stone_bricks", "dirt", "sand", "glass", "oak_leaves", "white_wool", "hay_block", "furnace", "bookshelf", "lantern"]);
/** Validate a design and turn it into a blueprint. Returns { ok, blueprint?, detail }. */
export function parseDesign(text) {
  let d;
  try { d = typeof text === "string" ? JSON.parse(text.slice(text.indexOf("{"), text.lastIndexOf("}") + 1)) : text; } catch (e) { return { ok: false, detail: `the design is not valid JSON (${e.message})` }; }
  if (!d || !Array.isArray(d.layers) || typeof d.key !== "object") return { ok: false, detail: 'a design needs "key" and "layers"' };
  if (d.layers.length > 7) return { ok: false, detail: "too tall: at most 7 layers (villagers can't reach higher)" };
  const blocks = [];
  const facings = new Set(["north", "south", "east", "west"]);
  let w = 0, depth = 0;
  for (const [y, layer] of d.layers.entries()) {
    if (!Array.isArray(layer)) return { ok: false, detail: `layer ${y} must be a list of rows` };
    if (layer.length > 9) return { ok: false, detail: "too deep: at most 9 rows" };
    depth = Math.max(depth, layer.length);
    for (const [z, row] of layer.entries()) {
      const chars = [...String(row)];
      if (chars.length > 9) return { ok: false, detail: "too wide: at most 9 blocks per row" };
      w = Math.max(w, chars.length);
      for (const [x, ch] of chars.entries()) {
        if (ch === "." || ch === " ") continue;
        const spec = d.key[ch];
        if (!spec) return { ok: false, detail: `"${ch}" in layer ${y} is not in the key` };
        const [block, facing] = String(spec).toLowerCase().split(":");
        if (!DESIGN_BLOCKS.has(block)) return { ok: false, detail: `"${block}" can't be used (allowed: ${[...DESIGN_BLOCKS].join(", ")})` };
        if (facing && !facings.has(facing) && facing !== "top") return { ok: false, detail: `"${spec}": direction must be north, south, east or west` };
        const props = facing === "top" ? { type: "top" } : facing ? { facing } : undefined;
        blocks.push({ x, y, z, block, ...(props ? { props } : {}), ...(block === "torch" ? { opt: true } : {}) });
      }
    }
  }
  if (blocks.length < 4) return { ok: false, detail: "a design needs at least 4 blocks" };
  return { ok: true, blueprint: { size: { x: w, y: d.layers.length, z: depth }, blocks }, detail: `${blocks.length} blocks, ${w}x${d.layers.length}x${depth}` };
}

// --- storage ---------------------------------------------------------------------------------------------
export function save(kind, name, owner, faction, body) {
  const n = cleanName(name);
  if (n.length < 2) return { ok: false, detail: "give it a name (letters, numbers, _)" };
  q.upsert.run(kind, n, owner, faction || null, JSON.stringify(body), Date.now(), Date.now());
  return { ok: true, name: n };
}
export function get(kind, name, faction) {
  const r = q.get.get(kind, cleanName(name), faction || "");
  return r ? { ...r, body: JSON.parse(r.body) } : null;
}
export function list(kind, faction, n = 8) { return q.list.all(kind, faction || "", n).map((r) => ({ ...r, body: JSON.parse(r.body) })); }
export function allOf(kind, n = 50) { return q.all.all(kind, n).map((r) => ({ ...r, body: JSON.parse(r.body) })); }
export function recordRun(id, ok, error) { q.result.run(ok ? 0 : 1, ok ? null : String(error).slice(0, 200), id); }
export { cleanName };
