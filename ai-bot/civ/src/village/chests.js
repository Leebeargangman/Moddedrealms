// Village storage: shared chests near the village centre. Villagers deposit what they gather and
// withdraw what they need (for crafting or building), so the village works as one team.
// The chest positions and a summary of their contents are kept in data/civ.db, so every bot's prompt
// can show "Village chests: oak_log 40, cobblestone 64..." without opening anything.

import { db } from "./db.js";

db.exec(`
  CREATE TABLE IF NOT EXISTS chests (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
    contents TEXT NOT NULL DEFAULT '{}',   -- {"oak_log": 40, ...} as last seen
    updated  INTEGER
  );
`);
const q = {
  all: db.prepare("SELECT * FROM chests ORDER BY id"),
  add: db.prepare("INSERT INTO chests (x, y, z, updated) VALUES (?, ?, ?, ?)"),
  set: db.prepare("UPDATE chests SET contents = ?, updated = ? WHERE id = ?"),
  del: db.prepare("DELETE FROM chests WHERE id = ?"),
};

export const all = () => q.all.all().map((r) => ({ ...r, contents: JSON.parse(r.contents) }));
export function add(pos) { return Number(q.add.run(pos.x, pos.y, pos.z, Date.now()).lastInsertRowid); }
export function remove(id) { q.del.run(id); }
export function saveContents(id, items) {
  const c = {};
  for (const it of items) c[it.name] = (c[it.name] || 0) + it.count;
  q.set.run(JSON.stringify(c), Date.now(), id);
}

/** Everything in all village chests together: { name: count }. */
export function totals() {
  const t = {};
  for (const ch of all()) for (const [n, c] of Object.entries(ch.contents)) t[n] = (t[n] || 0) + c;
  return t;
}

/** One short line for prompts. */
export function text() {
  const list = all();
  if (!list.length) return "Village chests: none yet (deposit needs a chest: craft one from 8 planks, or carry one).";
  const t = Object.entries(totals()).sort((a, b) => b[1] - a[1]);
  return `Village chests (${list.length}): ${t.length ? t.slice(0, 10).map(([n, c]) => `${n} ${c}`).join(", ") + (t.length > 10 ? `, +${t.length - 10} more kinds` : "") : "empty"}`;
}
