// The world log: the story of the village, one line per important event (arrivals, deaths, elections,
// laws, buildings, exiles). Stored in data/civ.db and mirrored to data/world-log.md so you can read it.
//
// `public` lines are what the whole village knows. Secret facts (who the imposters are, who really
// killed whom) are logged with public = 0: they are for you (and the website's staff view), never
// shown to the bots.

import fs from "node:fs";
import path from "node:path";
import { DATA_DIR } from "../config.js";
import { log } from "../log.js";
import { db } from "./db.js";

db.exec(`
  CREATE TABLE IF NOT EXISTS world_log (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    at      INTEGER NOT NULL,
    day     INTEGER,
    kind    TEXT NOT NULL,       -- arrival / death / missing / election / law / build / exile / win / secret...
    text    TEXT NOT NULL,
    public  INTEGER NOT NULL DEFAULT 1
  );
`);
const insert = db.prepare("INSERT INTO world_log (at, day, kind, text, public) VALUES (?, ?, ?, ?, ?)");
const recentPublic = db.prepare("SELECT * FROM world_log WHERE public = 1 ORDER BY id DESC LIMIT ?");
const recentAll = db.prepare("SELECT * FROM world_log ORDER BY id DESC LIMIT ?");
const FILE = path.join(DATA_DIR, "world-log.md");

export function write(kind, text, { day = null, isPublic = true } = {}) {
  insert.run(Date.now(), day, kind, text, isPublic ? 1 : 0);
  const line = `- ${new Date().toISOString().slice(0, 16).replace("T", " ")}${day !== null ? ` (day ${day})` : ""} ${isPublic ? "" : "[secret] "}${text}\n`;
  fs.appendFileSync(FILE, line);
  log.info(isPublic ? "world" : "secret", text);
}

/** The last few things everyone knows, newest last (for prompts). */
export const publicNews = (n = 3) => recentPublic.all(n).map((r) => ({ ...r })).reverse();
export const recent = (n = 50, includeSecret = false) =>
  (includeSecret ? recentAll : recentPublic).all(n).map((r) => ({ ...r })).reverse();
