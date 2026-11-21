// Resetting the AI's memory, from the website (admins). A backup of data/civ.db and the memory files is
// always made first (data/backups/<time>/), so a reset can be undone by copying them back.
//
//   "memories"   every villager forgets: memory summary, grudges, recent events. They keep their name,
//                role and personality, and the village (leaders, laws, buildings, chests) stays.
//   "everything" a brand-new game: also the villagers (the original ones come back, newcomers go), leaders,
//                laws, civilisations, task board, chest and building records, world log and imposters.
//                The website settings and the cost history (usage.db) are kept. Blocks already built in
//                the Minecraft world stay where they are.

import fs from "node:fs";
import path from "node:path";
import { DATA_DIR } from "../config.js";
import { log } from "../log.js";
import { db } from "./db.js";

const MEMORY_DIR = path.join(DATA_DIR, "memory");

function backup(label) {
  const dir = path.join(DATA_DIR, "backups", `${new Date().toISOString().slice(0, 19).replace(/:/g, "-")}-${label}`);
  fs.mkdirSync(dir, { recursive: true });
  db.exec(`VACUUM INTO '${path.join(dir, "civ.db").replace(/'/g, "''")}'`); // a consistent copy of the open database
  if (fs.existsSync(MEMORY_DIR)) fs.cpSync(MEMORY_DIR, path.join(dir, "memory"), { recursive: true });
  const wl = path.join(DATA_DIR, "world-log.md");
  if (fs.existsSync(wl)) fs.copyFileSync(wl, path.join(dir, "world-log.md"));
  return dir;
}

/** Forget everything each villager learned. `bots` = the running CivBots (their brains are cleared live). */
export function resetMemories(bots) {
  const dir = backup("memories");
  for (const f of fs.existsSync(MEMORY_DIR) ? fs.readdirSync(MEMORY_DIR) : []) if (f.endsWith(".json")) fs.rmSync(path.join(MEMORY_DIR, f));
  for (const b of bots) {
    b.brain.memory = "";
    b.brain.grudges = {};
    b.brain.history = [];
    b.brain.events = [];
    b.brain.lastChoices = [];
  }
  log.info("reset", `all villager memories cleared (backup in ${dir})`);
  return { ok: true, detail: `memories reset (backup saved in data/backups/${path.basename(dir)})` };
}

/** A brand-new game. Call only while no session is running (the caller ends it first). */
export function resetEverything() {
  const dir = backup("everything");
  const tables = ["villagers", "tasks", "laws", "chests", "buildings", "world_log", "deaths", "civs", "relations"];
  const existing = new Set(db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all().map((r) => r.name));
  for (const t of tables) if (existing.has(t)) db.exec(`DELETE FROM ${t}`);
  db.exec("DELETE FROM kv WHERE key != 'settings'"); // leaders, elections, imposters, kill windows, plans...
  for (const f of fs.existsSync(MEMORY_DIR) ? fs.readdirSync(MEMORY_DIR) : []) if (f.endsWith(".json")) fs.rmSync(path.join(MEMORY_DIR, f));
  const wl = path.join(DATA_DIR, "world-log.md");
  if (fs.existsSync(wl)) fs.rmSync(wl);
  log.info("reset", `new game: village data and memories cleared (backup in ${dir})`);
  return { ok: true, detail: `new village started from scratch (backup saved in data/backups/${path.basename(dir)})` };
}
