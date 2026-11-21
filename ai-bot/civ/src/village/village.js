// The village: one object that knows every bot and runs the plain-code "world rules" every few seconds.
// No AI calls in here. It:
//   - keeps the Minecraft day count (from any bot's world time),
//   - runs elections (society.js), notices missing villagers and found remains (crime.js),
//   - remembers who saw whom where (for clues),
//   - handles deaths: witnesses are told, the dead villager leaves forever, a newcomer arrives later,
//   - handles exiles and announces when both imposters are gone (the villagers win),
//   - assigns the two secret imposters once there are enough villagers.

import { Vec3 } from "vec3";
import { config } from "../config.js";
import { log } from "../log.js";
import { kv } from "./db.js";
import * as roster from "./roster.js";
import * as society from "./society.js";
import * as crime from "./crime.js";
import * as worldlog from "./worldlog.js";
import * as board from "./board.js";

export const village = {
  pos: null,            // the village centre (Vec3), from config or the world spawn
  bots: new Map(),      // name -> CivBot (online or connecting)
  day: null,            // Minecraft day number
  sightings: new Map(), // name -> [{ who, x, y, z, at }] the last things this villager saw
  makeBot: null,        // set by index.js: (def) => CivBot, so newcomers can be started from here
  stopping: false,
};

const c = config.village.centre;
if (c) village.pos = new Vec3(c.x, c.y, c.z);

/** Tell every living villager something (each bot decides itself whether to react). */
export function announce(text, wake = "news") {
  for (const b of village.bots.values()) if (b.ready && roster.isAlive(b.def.name)) b.brain.notify(text, wake);
}
function tell(name, text, wake) {
  const b = village.bots.get(name);
  if (b?.ready) b.brain.notify(text, wake);
}

/** Where everyone is right now: villagers and outside players, { name: Vec3 }. */
export function positions() {
  const out = {};
  for (const b of village.bots.values()) {
    if (!b.ready || !b.bot?.entity) continue;
    out[b.def.name] = b.bot.entity.position.clone();
    for (const p of Object.values(b.bot.players)) if (p.entity && !out[p.username]) out[p.username] = p.entity.position.clone();
  }
  return out;
}

const aliveOnline = () => [...village.bots.values()].filter((b) => b.ready && roster.isAlive(b.def.name));

// --- imposters --------------------------------------------------------------------------------------
function assignImposters() {
  if (kv("impostersAssigned")) return;
  const alive = roster.alive();
  if (alive.length < config.imposters.minVillagers) return;
  const chosen = [...alive].sort(() => Math.random() - 0.5).slice(0, config.imposters.count);
  roster.setImposters(chosen.map((v) => v.name));
  kv("impostersAssigned", true);
  kv("impostersAssignedDay", village.day ?? 0);
  worldlog.write("secret", `The imposters are ${chosen.map((v) => v.name).join(" and ")}.`, { day: village.day, isPublic: false });
  announce(`Warning to all villagers: ${chosen.length} imposters are hidden among you. They look like everyone else, and they will kill when nobody is watching.`, "society");
}

// --- the 5-second world tick ------------------------------------------------------------------------------
let lastSighting = 0;
function tick() {
  if (village.stopping) return;
  const any = aliveOnline()[0];
  if (any?.bot?.time?.day !== undefined) {
    const d = Number(any.bot.time.day);
    if (village.day !== null && d > village.day) worldlog.write("day", `Day ${d} begins.`, { day: d });
    village.day = d;
  }
  assignImposters();
  const aliveNames = aliveOnline().map((b) => b.def.name);
  // Give everyone time to log in before the first election (20 bots take ~80 s).
  if (Date.now() - startedAt > config.server.loginDelayMs * config.botCount + 30000) society.tick({ day: village.day, aliveNames, announce });

  // Who sees whom (every 10 s): each villager remembers the last 60 sightings, for clues later.
  if (Date.now() - lastSighting > 10000) {
    lastSighting = Date.now();
    const where = positions();
    for (const b of aliveOnline()) {
      const me = where[b.def.name];
      if (!me) continue;
      const list = village.sightings.get(b.def.name) || [];
      for (const [who, p] of Object.entries(where)) {
        if (who !== b.def.name && p.distanceTo(me) <= 32) list.push({ who, x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z), at: Date.now() });
      }
      village.sightings.set(b.def.name, list.slice(-60));
    }
  }

  // Remains found? (a villager walks within 8 blocks of where someone died)
  for (const b of aliveOnline()) {
    const pos = b.bot.entity?.position;
    if (!pos) continue;
    const f = crime.lookForClues(b.def.name, pos, village.day);
    if (!f) continue;
    const memories = (village.sightings.get(b.def.name) || [])
      .filter((s) => s.who !== f.victim && s.at <= f.at + 10000 && s.at >= f.at - 180000 && Math.hypot(s.x - f.x, s.z - f.z) < 40)
      .map((s) => s.who);
    const seen = [...new Set(memories)];
    tell(b.def.name, `You found ${f.victim}'s dropped things at ${f.x},${f.z}: ${f.victim} is dead (about ${Math.max(1, Math.round((Date.now() - f.at) / 60000))} min ago).` +
      (seen.length ? ` You remember seeing ${seen.join(", ")} near there shortly before.` : " You don't remember seeing anyone near there."), "clue");
    announce(`${b.def.name} found ${f.victim}'s remains near ${f.x},${f.z}. ${f.victim} is dead.`, "news");
  }
  for (const d of crime.missing(village.day)) announce(`${d.victim} is missing: nobody has seen them since day ${d.day}.`, "news");
}

// --- deaths, exiles, newcomers ------------------------------------------------------------------------------
/** A villager's bot died. Permadeath: it leaves for good and a newcomer comes later. */
export function onDeath(civbot, pos, causeHint) {
  const name = civbot.def.name;
  if (!roster.isAlive(name)) return;
  const wasImposter = roster.isImposter(name);
  for (const w of crime.died(name, pos, village.day, positions(), causeHint)) tell(w.name, w.text, w.wake);
  roster.markGone(name, "dead", causeHint || "died", village.day);
  board.release(name);
  civbot.leave("died");
  village.bots.delete(name);
  if (wasImposter) checkWin();
  scheduleNewcomer();
}

/** "exile" action by the leader. */
export function exile(leaderName, target, reason) {
  const lead = society.leader();
  if (!lead || lead.name !== leaderName) return { ok: false, detail: "only the village leader can exile someone" };
  const v = roster.get(target);
  if (!v || v.status !== "alive") return { ok: false, detail: `${target} is not a living villager` };
  if (v.name === leaderName) return { ok: false, detail: "you can't exile yourself" };
  const wasImposter = Boolean(v.imposter);
  roster.markGone(v.name, "exiled", `exiled by ${leaderName}${reason ? `: ${reason}` : ""}`, village.day);
  worldlog.write("exile", `${leaderName} exiled ${v.name}${reason ? ` (${reason})` : ""}. ${v.name} ${wasImposter ? "WAS an imposter" : "was NOT an imposter"}.`, { day: village.day });
  const b = village.bots.get(v.name);
  if (b) { board.release(v.name); b.leave("exiled"); village.bots.delete(v.name); }
  announce(`Leader ${leaderName} exiled ${v.name}${reason ? ` (${reason})` : ""}. As ${v.name} left, the truth came out: ${v.name} ${wasImposter ? "WAS an imposter!" : "was NOT an imposter."}`, "news");
  if (wasImposter) checkWin();
  scheduleNewcomer();
  return { ok: true, detail: `exiled ${v.name}` };
}

function checkWin() {
  if (!kv("impostersAssigned") || kv("villageWon")) return;
  if (roster.livingImposters().length > 0) return;
  kv("villageWon", true);
  worldlog.write("win", "Both imposters are gone. The village is safe!", { day: village.day });
  announce("Both imposters are gone: the village is safe now! Keep building.", "news");
}

function scheduleNewcomer() {
  if (village.stopping) return;
  setTimeout(() => {
    if (village.stopping || roster.alive().length >= config.botCount) return;
    const def = roster.nextVillager(village.day);
    worldlog.write("arrival", `${def.name} (${def.role}) arrived in the village: ${def.personality}.`, { day: village.day });
    announce(`A newcomer called ${def.name} has arrived in the village.`, "news");
    village.makeBot(def);
  }, config.village.newcomerDelayMinutes * 60000);
}

let startedAt = Date.now();
let ticker = null;
/** Start the world rules for a session (safe to call again for the next session). */
export function start() {
  startedAt = Date.now();
  village.stopping = false;
  roster.seed();
  clearInterval(ticker);
  ticker = setInterval(tick, 5000);
}
/** End of a session: stop the world rules (bots are logged off by the caller). */
export function stop() {
  village.stopping = true;
  clearInterval(ticker);
  ticker = null;
}
