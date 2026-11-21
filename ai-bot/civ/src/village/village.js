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
import * as settings from "../settings.js";
import * as factions from "./factions.js";

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

// --- civilisations ---------------------------------------------------------------------------------
/** Where a civilisation's village is: the first at the centre, the others factionDistance blocks away
 *  (east, west, south, then north). One civilisation = the old single village. */
export function centreOfFaction(f) {
  if (!f) return null; // on their own: no village
  if (factions.isFree()) {
    const c = factions.get(f);
    return c ? new Vec3(c.x, c.y, c.z) : null;
  }
  if (!village.pos) return null;
  const s = settings.get();
  const i = Math.max(0, roster.factionNames().indexOf(f));
  const d = s.factionDistance;
  const [dx, dz] = [[0, 0], [d, 0], [-d, 0], [0, d], [0, -d]][i] || [0, 0];
  if (!dx && !dz) return village.pos;
  return village.pos.offset(dx, 0, dz);
}
export const centreFor = (name) => centreOfFaction(roster.factionOf(name));
/**
 * How villager b stands to villager a: "own" (same civilisation), "ally", "enemy" (at war), "other".
 * Set civilisations: enemies when the settings say war. Free for all: war or not, anyone outside your
 * own civilisation (and not an ally) may be attacked; "enemy" is shown for declared wars.
 */
export function standing(a, b) {
  if (!roster.isVillagerName(a) || !roster.isVillagerName(b)) return "other";
  const fa = roster.factionOf(a), fb = roster.factionOf(b);
  if (fa && fa === fb) return "own";
  if (factions.isFree()) {
    const r = fa && fb ? factions.relation(fa, fb) : "neutral";
    return r === "ally" ? "ally" : r === "war" ? "enemy" : "other";
  }
  return settings.get().war ? "enemy" : "other";
}
/** May a attack b (without having been attacked first)? */
export function areEnemies(a, b) {
  const s = standing(a, b);
  if (factions.isFree()) return s === "enemy" || s === "other";
  return s === "enemy";
}
/** Tell only the living villagers of one civilisation. */
export function announceTo(f, text, wake = "news") {
  for (const b of village.bots.values()) if (b.ready && roster.isAlive(b.def.name) && roster.factionOf(b.def.name) === f) b.brain.notify(text, wake);
}

const aliveOnline = () => [...village.bots.values()].filter((b) => b.ready && roster.isAlive(b.def.name));

// --- imposters --------------------------------------------------------------------------------------
function assignImposters() {
  if (kv("impostersAssigned") || config.imposters.count <= 0) return;
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
  // Each civilisation runs its own elections. Give everyone time to log in first (20 bots take ~80 s).
  if (Date.now() - startedAt > config.server.loginDelayMs * config.botCount + 30000) {
    for (const f of roster.factionNames()) {
      const aliveNames = aliveOnline().map((b) => b.def.name).filter((n) => roster.factionOf(n) === f);
      society.tick({ day: village.day, aliveNames, announce: (t, w) => announceTo(f, t, w), faction: f });
    }
  }

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
  // War: if an enemy villager hit them last, that enemy is the killer (witnesses will say so).
  const hitter = /^hurt by (\w+)$/.exec(causeHint || "")?.[1];
  const warKiller = hitter && areEnemies(name, hitter) ? hitter : null;
  for (const w of crime.died(name, pos, village.day, positions(), causeHint, warKiller)) tell(w.name, w.text, w.wake);
  if (warKiller) {
    worldlog.write("war", `${warKiller} (${roster.factionOf(warKiller)}) killed ${name} (${roster.factionOf(name)}).`, { day: village.day });
    tell(warKiller, `You killed ${name} of ${roster.factionOf(name)}.`, "task");
  }
  const civ = roster.factionOf(name);
  roster.markGone(name, "dead", causeHint || "died", village.day);
  if (factions.isFree() && civ && !roster.alive().some((v) => v.faction === civ)) {
    factions.close(civ);
    worldlog.write("civ", `${civ} is no more: its last member, ${name}, died.`, { day: village.day });
  }
  board.release(name);
  civbot.leave("died");
  village.bots.delete(name);
  if (wasImposter) checkWin();
  scheduleNewcomer();
}

/** "exile" action by the leader. */
export function exile(leaderName, target, reason) {
  const lead = society.leader(roster.factionOf(leaderName));
  if (!lead || lead.name !== leaderName) return { ok: false, detail: "only the village leader can exile someone" };
  const v = roster.get(target);
  if (!v || v.status !== "alive") return { ok: false, detail: `${target} is not a living villager` };
  if (v.name === leaderName) return { ok: false, detail: "you can't exile yourself" };
  if (roster.factionOf(v.name) !== roster.factionOf(leaderName)) return { ok: false, detail: `${v.name} isn't one of your people` };
  if (factions.isFree()) {
    // Free for all: exile = thrown out of the civilisation (they stay in the world, on their own).
    const f = roster.factionOf(leaderName);
    roster.setFaction(v.name, null);
    worldlog.write("exile", `${leaderName} threw ${v.name} out of ${f}${reason ? ` (${reason})` : ""}.`, { day: village.day });
    announceTo(f, `Leader ${leaderName} threw ${v.name} out of ${f}${reason ? ` (${reason})` : ""}.`, "news");
    tell(v.name, `${leaderName} threw you out of ${f}${reason ? ` (${reason})` : ""}. You are on your own now.`, "news");
    return { ok: true, detail: `threw ${v.name} out of ${f}` };
  }
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

/** Free for all: "found", "join", "leave" and "diplomacy". Returns { ok, detail }. */
export function civAction(me, action, pos, bot = null) {
  if (!factions.isFree()) return { ok: false, detail: "that only works in Free for all mode" };
  const mine = roster.factionOf(me);
  const day = village.day;
  if (action.action === "found") {
    if (mine && society.leader(mine)?.name === me && roster.alive().filter((v) => v.faction === mine).length > 1) {
      return { ok: false, detail: `you lead ${mine}: leave it first` };
    }
    // Water within 4 blocks around or just below = no place for a village.
    const isWet = (p) => {
      if (!bot) return false;
      for (let dx = -4; dx <= 4; dx++) for (let dz = -4; dz <= 4; dz++) for (let dy = -2; dy <= 1; dy++) {
        if (bot.blockAt(p.floored().offset(dx, dy, dz))?.name === "water") return true;
      }
      return false;
    };
    const r = factions.found(me, action.target, pos, day, isWet);
    if (!r.ok) return r;
    if (mine) leaveCiv(me, mine);
    roster.setFaction(me, r.name);
    society.setLeader(r.name, me, day);
    board.seed(r.name);
    worldlog.write("civ", `${me} founded the civilisation ${r.name} at ${Math.round(pos.x)},${Math.round(pos.z)}.`, { day });
    announce(`${me} founded a new civilisation, ${r.name}, at ${Math.round(pos.x)},${Math.round(pos.z)}. Anyone may join it.`, "news");
    return { ok: true, detail: `I founded ${r.name} here and lead it. Its village is here.` };
  }
  if (action.action === "join") {
    const c = factions.get(action.target);
    if (!c) return { ok: false, detail: `there is no civilisation called ${action.target} (${factions.active().map((x) => x.name).join(", ") || "none yet: found one"})` };
    if (mine === c.name) return { ok: false, detail: `you already belong to ${c.name}` };
    if (mine) leaveCiv(me, mine);
    roster.setFaction(me, c.name);
    worldlog.write("civ", `${me} joined ${c.name}.`, { day });
    announceTo(c.name, `${me} has joined ${c.name}.`, "news");
    return { ok: true, detail: `I joined ${c.name}. Its village is at ${c.x},${c.z}.` };
  }
  if (action.action === "leave") {
    if (!mine) return { ok: false, detail: "you are already on your own" };
    leaveCiv(me, mine);
    roster.setFaction(me, null);
    return { ok: true, detail: `I left ${mine} and am on my own now.` };
  }
  // diplomacy: "war Blue", "peace Blue", "ally Blue"
  const m = /^(war|peace|ally|alliance)\s+(?:with\s+)?(.+)$/i.exec(String(action.target || "").trim());
  if (!m) return { ok: false, detail: 'say "war <name>", "peace <name>" or "ally <name>"' };
  if (!mine || society.leader(mine)?.name !== me) return { ok: false, detail: "only a civilisation's leader can do diplomacy" };
  const other = factions.get(m[2]);
  if (!other) return { ok: false, detail: `there is no civilisation called ${m[2]}` };
  if (other.name === mine) return { ok: false, detail: "that's your own civilisation" };
  const state = m[1].toLowerCase() === "alliance" ? "ally" : m[1].toLowerCase();
  factions.setRelation(mine, other.name, state);
  const words = { war: "declared WAR on", peace: "made peace with", ally: "formed an alliance with" }[state];
  worldlog.write("diplomacy", `${mine} (${me}) ${words} ${other.name}.`, { day });
  announceTo(mine, `Your leader ${me} ${words} ${other.name}.`, "news");
  announceTo(other.name, `${mine} (leader ${me}) ${words} your civilisation, ${other.name}.`, "news");
  return { ok: true, detail: `${mine} ${words} ${other.name}.` };
}

/** Someone leaves a civilisation: a leader leaving means a new election; an empty one is dissolved. */
function leaveCiv(me, f) {
  const left = roster.alive().filter((v) => v.faction === f && v.name !== me);
  if (society.leader(f)?.name === me) society.setLeader(f, null, village.day);
  worldlog.write("civ", `${me} left ${f}.`, { day: village.day });
  if (!left.length) {
    factions.close(f);
    worldlog.write("civ", `${f} is no more: nobody is left in it.`, { day: village.day });
  } else announceTo(f, `${me} has left ${f}.`, "news");
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
  roster.assignFactions(); // after the civilisations changed on the website, everyone belongs to one
  clearInterval(ticker);
  ticker = setInterval(tick, 5000);
}
/** End of a session: stop the world rules (bots are logged off by the caller). */
export function stop() {
  village.stopping = true;
  clearInterval(ticker);
  ticker = null;
}
