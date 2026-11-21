// Who lives in the village: names, roles, personalities, who is alive, dead or exiled, and who the
// two secret imposters are. Plain code, stored in data/civ.db.
//
// Permadeath: a villager who dies or is exiled never comes back. A completely new villager (new name,
// new personality, new role) arrives instead, so at most config.village.maxAlive (20) are alive.
// Newcomers are always normal villagers, so once both imposters are caught the village is safe.
// (The imposters are picked once, when the village is big enough: see village.js.)

import { config } from "../config.js";
import { log } from "../log.js";
import { db } from "./db.js";
import * as settings from "../settings.js";
import * as factions from "./factions.js";

db.exec(`
  CREATE TABLE IF NOT EXISTS villagers (
    name        TEXT PRIMARY KEY,   -- also the Minecraft username (offline mode), max 16 characters
    role        TEXT NOT NULL,
    personality TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'alive',   -- alive / dead / exiled
    imposter    INTEGER NOT NULL DEFAULT 0,      -- secret: never shown to normal villagers
    joined_at   INTEGER NOT NULL,
    joined_day  INTEGER,
    left_at     INTEGER,
    left_day    INTEGER,
    cause       TEXT                              -- how they died / why exiled (true cause, for the world log)
  );
`);

// Added for several civilisations: which one a villager belongs to.
if (!db.prepare("PRAGMA table_info(villagers)").all().some((c) => c.name === "faction")) db.exec("ALTER TABLE villagers ADD COLUMN faction TEXT");

const q = {
  setFaction: db.prepare("UPDATE villagers SET faction = ? WHERE name = ?"),
  count: db.prepare("SELECT COUNT(*) AS n FROM villagers"),
  insert: db.prepare("INSERT INTO villagers (name, role, personality, imposter, joined_at, joined_day) VALUES (?, ?, ?, ?, ?, ?)"),
  alive: db.prepare("SELECT * FROM villagers WHERE status = 'alive' ORDER BY joined_at"),
  get: db.prepare("SELECT * FROM villagers WHERE name = ? COLLATE NOCASE"),
  gone: db.prepare("UPDATE villagers SET status = ?, left_at = ?, left_day = ?, cause = ? WHERE name = ?"),
  names: db.prepare("SELECT name FROM villagers"),
  all: db.prepare("SELECT * FROM villagers ORDER BY joined_at"),
};

const ROLES = ["farmer", "miner", "builder", "lumberjack", "guard", "trader", "explorer"];

// Newcomers are made from these pools (no AI call needed, so it costs nothing).
const NAMES = ["Ash", "Bram", "Cora", "Dain", "Elsie", "Finn", "Greta", "Hugo", "Isla", "Jory", "Kit", "Lark", "Moss", "Nell",
  "Oren", "Pippa", "Quinn", "Reed", "Sage", "Tobin", "Una", "Vale", "Wren", "Yara", "Zeke", "Arlo", "Bea", "Cyrus", "Della",
  "Emrys", "Flint", "Gwen", "Hale", "Ines", "Jasper", "Kaia", "Leif", "Maren", "Noor", "Otto", "Perrin", "Rhea", "Silas",
  "Thea", "Ulric", "Vera", "Wade", "Xan", "Ysolde", "Zora", "Alba", "Basil", "Clem", "Dorian", "Edda", "Fern", "Gideon",
  "Hollis", "Idris", "Juno", "Knox", "Lena", "Milo", "Nora", "Osric", "Petra", "Rafe", "Signe", "Tamar", "Ugo", "Wilma"];
const TRAITS = ["cheerful", "grumpy", "curious", "cautious", "brave", "shy", "proud", "generous", "stubborn", "patient",
  "restless", "witty", "serious", "dreamy", "practical", "nosy", "loyal", "sly", "gentle", "bossy", "anxious", "calm"];
const QUIRKS = ["hums while working", "collects flowers", "distrusts strangers", "loves a good argument", "keeps a diary of everything",
  "wants to be leader one day", "afraid of the dark", "tells long stories", "counts everything twice", "talks to animals",
  "always hungry", "dreams of a stone castle", "quick to forgive", "remembers every slight", "loves sunsets", "hates waste"];

const pick = (list) => list[Math.floor(Math.random() * list.length)];

/** First start: the villagers from config.json (as many as botCount). Imposters are chosen later, by the
 *  village, once at least config.imposters.minVillagers are alive (so small tests have none). */
export function seed(day = 0) {
  if (q.count.get().n > 0) return;
  for (const d of config.bots.slice(0, config.botCount)) q.insert.run(d.name, d.role, d.personality, 0, Date.now(), day);
  log.info("roster", `${Math.min(config.botCount, config.bots.length)} villagers created`);
}

const setImp = db.prepare("UPDATE villagers SET imposter = 1 WHERE name = ?");
/** Secretly make these villagers imposters (done once). */
export function setImposters(names) { for (const n of names) setImp.run(n); }

/** The next villager to arrive: an unused one from config.json first, then a brand-new random one. */
export function nextVillager(day) {
  const used = new Set(q.names.all().map((r) => r.name.toLowerCase()));
  const d = config.bots.find((b) => !used.has(b.name.toLowerCase()));
  if (d) {
    q.insert.run(d.name, d.role, d.personality, 0, Date.now(), day ?? null);
    q.setFaction.run(smallestFaction(), d.name);
    return { name: d.name, role: d.role, personality: d.personality };
  }
  return newcomer(day);
}

/** The civilisation names in use: from the website settings, or (Free for all) the ones villagers founded. */
export const factionNames = () => (factions.isFree() ? factions.active().map((c) => c.name) : settings.get().factions.map((f) => f.name));
/** A villager's civilisation. In Free for all it can be null: on their own. */
export function factionOf(name) {
  const f = get(name)?.faction;
  if (factions.isFree()) return f && factions.get(f) ? factions.get(f).name : null;
  return f || factionNames()[0];
}
/** Join a civilisation, or null to be on your own (Free for all). */
export function setFaction(name, f) { q.setFaction.run(f, name); }

/** The civilisation with the fewest living members (where a newcomer goes). Free for all: nobody's. */
export function smallestFaction() {
  if (factions.isFree()) return null;
  const names = factionNames();
  const count = Object.fromEntries(names.map((n) => [n, 0]));
  for (const v of alive()) if (count[v.faction] !== undefined) count[v.faction]++;
  return names.sort((a, b) => count[a] - count[b])[0];
}

/** Make sure every living villager belongs to an existing civilisation (after the settings changed). */
export function assignFactions() {
  const names = factionNames();
  for (const v of alive()) {
    if (names.includes(v.faction)) continue;
    // Free for all: a villager whose civilisation is gone is on their own again.
    q.setFaction.run(factions.isFree() ? null : smallestFaction(), v.name);
  }
}

export const alive = () => q.alive.all().map((r) => ({ ...r }));
export const get = (name) => { const r = q.get.get(name); return r ? { ...r } : null; };
export const all = () => q.all.all().map((r) => ({ ...r }));
export const isAlive = (name) => get(name)?.status === "alive";
export const isImposter = (name) => Boolean(get(name)?.imposter) && isAlive(name);
export const livingImposters = () => alive().filter((v) => v.imposter);
/** True if this username is (or was) one of our villagers. */
export const isVillagerName = (name) => Boolean(get(name));

/** A villager died or was exiled: they are gone for good. */
export function markGone(name, status, cause, day) {
  q.gone.run(status, Date.now(), day ?? null, String(cause).slice(0, 200), name);
}

/** A brand-new villager: unused name, random personality, the role the village has fewest of. */
export function newcomer(day) {
  const used = new Set(q.names.all().map((r) => r.name.toLowerCase()));
  let name = NAMES.filter((n) => !used.has(n.toLowerCase())).sort(() => Math.random() - 0.5)[0];
  for (let i = 2; !name; i++) name = NAMES.map((n) => `${n}${i}`).find((n) => !used.has(n.toLowerCase()));
  const counts = Object.fromEntries(ROLES.map((r) => [r, 0]));
  for (const v of alive()) counts[v.role] = (counts[v.role] ?? 0) + 1;
  const role = ROLES.sort((a, b) => counts[a] - counts[b] || Math.random() - 0.5)[0];
  const [t1, t2] = [...TRAITS].sort(() => Math.random() - 0.5);
  const personality = `${t1} and ${t2}, ${pick(QUIRKS)}`;
  q.insert.run(name, role, personality, 0, Date.now(), day ?? null); // newcomers are never imposters
  q.setFaction.run(smallestFaction(), name);
  log.info("roster", `newcomer ${name} (${role}): ${personality}`);
  return { name, role, personality };
}
