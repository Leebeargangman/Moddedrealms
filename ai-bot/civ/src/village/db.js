// One SQLite file (data/civ.db) for everything about the village that must survive restarts:
// the task board, the villagers (alive, dead, exiled, imposters), the leader and laws, chests,
// buildings and the world log. Built into Node (node:sqlite), no extra package.

import path from "node:path";
import { DatabaseSync } from "node:sqlite";
import { DATA_DIR } from "../config.js";

export const db = new DatabaseSync(path.join(DATA_DIR, "civ.db"));

/** Small key/value store for single values (current leader, last kill day...). */
db.exec("CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT)");
const getKv = db.prepare("SELECT value FROM kv WHERE key = ?");
const setKv = db.prepare("INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value");
export function kv(key, value) {
  if (value === undefined) {
    const row = getKv.get(key);
    return row ? JSON.parse(row.value) : null;
  }
  setKv.run(key, JSON.stringify(value));
  return value;
}
