// How the village governs itself: a leader chosen by vote, and laws the leader writes.
// Plain code; the bots only decide WHO to vote for and WHAT the laws say.
//
// - Election: starts when there is no leader (first day, the leader died or was exiled) or the leader's
//   term (society.leaderTermDays Minecraft days) is over. Every living villager is told and votes with
//   the "vote" action. It closes when everyone voted or after society.electionMinutes real minutes.
//   Most votes wins; a tie is decided at random among the tied.
// - Laws: only the leader can add a law ("law" action, target = the law) or repeal one
//   (target = "repeal 2"). At most society.maxLaws laws. Every villager sees the laws in its prompt.
// - Exile: only the leader can exile a villager ("exile" action). What counts as a reason is up to the
//   laws the villagers made. An exiled villager leaves forever; whether they were an imposter is then
//   revealed to everyone (that is how the village can find out it has won).

import { config } from "../config.js";
import { db, kv } from "./db.js";
import * as roster from "./roster.js";
import * as factions from "./factions.js";
import * as worldlog from "./worldlog.js";

db.exec(`
  CREATE TABLE IF NOT EXISTS laws (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    text    TEXT NOT NULL,
    by      TEXT NOT NULL,
    day     INTEGER,
    active  INTEGER NOT NULL DEFAULT 1
  );
`);
// Each civilisation has its own leader, election and laws (faction = its name; one village = "Village").
if (!db.prepare("PRAGMA table_info(laws)").all().some((c) => c.name === "faction")) db.exec("ALTER TABLE laws ADD COLUMN faction TEXT");
const q = {
  active: db.prepare("SELECT * FROM laws WHERE active = 1 AND COALESCE(faction, ?) = ? ORDER BY id"),
  add: db.prepare("INSERT INTO laws (text, by, day, faction) VALUES (?, ?, ?, ?)"),
  repeal: db.prepare("UPDATE laws SET active = 0 WHERE id = ?"),
};

const S = () => config.society;
const first = () => roster.factionNames()[0];
// Keys of the first civilisation stay "leader"/"election" so an existing village keeps its leader.
// (Free for all: always per civilisation, since civilisations come and go.)
const key = (k, f) => (factions.isFree() ? `${k}:${f}` : !f || f === first() ? k : `${k}:${f}`);
export const leader = (f) => kv(key("leader", f));          // { name, since_day } or null
export const election = (f) => kv(key("election", f));      // { started_at, started_day, votes } or null
export const laws = (f = first()) => q.active.all(first(), f).map((r) => ({ ...r }));

/** Called by the village every few seconds. `announce(text, wake)` tells every living villager. */
export function tick({ day, aliveNames, announce, faction }) {
  const f = faction;
  const lead = leader(f);
  if (lead && (!roster.isAlive(lead.name) || roster.factionOf(lead.name) !== f)) {
    kv(key("leader", f), null);
    announce(`The village has no leader now: ${lead.name} is gone.`, "society");
  }
  const el = election(f);
  const termOver = lead && day !== null && day - lead.since_day >= S().leaderTermDays;
  if (!el && aliveNames.length >= 2 && (!leader(f) || termOver)) {
    kv(key("election", f), { started_at: Date.now(), started_day: day, votes: {} });
    const where = roster.factionNames().length > 1 ? `${f}: ` : "";
    worldlog.write("election", `${where}an election for leader has started.`.replace(/^an/, "An"), { day });
    announce(`Election! The village is choosing a leader${termOver ? ` (${lead.name}'s term is over)` : ""}. Use the "vote" action with target = the villager you choose (you may vote for yourself).`, "society");
    return;
  }
  if (el) {
    const voters = Object.keys(el.votes).filter((v) => aliveNames.includes(v));
    const timeUp = Date.now() - el.started_at > S().electionMinutes * 60000;
    if (voters.length >= aliveNames.length || (timeUp && voters.length > 0)) closeElection({ day, aliveNames, announce, faction: f });
    else if (timeUp) { kv(key("election", f), { ...el, started_at: Date.now() }); } // nobody voted: keep it open
  }
}

function closeElection({ day, aliveNames, announce, faction: f }) {
  const el = election(f);
  const tally = {};
  for (const [voter, cand] of Object.entries(el.votes)) {
    if (aliveNames.includes(voter) && aliveNames.includes(cand)) tally[cand] = (tally[cand] || 0) + 1;
  }
  const best = Math.max(0, ...Object.values(tally));
  const tied = Object.keys(tally).filter((c) => tally[c] === best);
  kv(key("election", f), null);
  if (!tied.length) return;
  const winner = tied[Math.floor(Math.random() * tied.length)];
  kv(key("leader", f), { name: winner, since_day: day ?? 0 });
  const result = Object.entries(tally).sort((a, b) => b[1] - a[1]).map(([c, n]) => `${c} ${n}`).join(", ");
  worldlog.write("election", `${winner} was elected leader${roster.factionNames().length > 1 ? ` of ${f}` : ""} (votes: ${result}).`, { day });
  announce(`${winner} won the election and is now the village leader (votes: ${result}).`, "society");
}

export function vote(voter, candidate, aliveNames, f = roster.factionOf(voter)) {
  if (!f) return { ok: false, detail: "you are on your own: there is no election for you" };
  const el = election(f);
  if (!el) return { ok: false, detail: "there is no election right now" };
  const c = aliveNames.find((n) => n.toLowerCase() === String(candidate).toLowerCase());
  if (!c) return { ok: false, detail: `${candidate} is not a living villager` };
  el.votes[voter] = c;
  kv(key("election", f), el);
  return { ok: true, detail: `voted for ${c}` };
}

/** Make someone the leader right away (the founder of a new civilisation). */
export function setLeader(f, name, day) { kv(key("leader", f), name ? { name, since_day: day ?? 0 } : null); kv(key("election", f), null); }

/** "law" action: add a law, or "repeal N". Leader only. */
export function law(by, text, day, f = roster.factionOf(by)) {
  if (!f) return { ok: false, detail: "you are on your own: found a civilisation to make laws" };
  const lead = leader(f);
  if (!lead || lead.name !== by) return { ok: false, detail: "only the village leader can make or repeal laws (suggest it to the leader instead)" };
  const m = /^repeal\s*#?(\d+)/i.exec(text.trim());
  const active = laws(f);
  if (m) {
    const lawToGo = active[Number(m[1]) - 1];
    if (!lawToGo) return { ok: false, detail: `there is no law ${m[1]}` };
    q.repeal.run(lawToGo.id);
    worldlog.write("law", `${by} repealed the law "${lawToGo.text}".`, { day });
    return { ok: true, detail: `repealed "${lawToGo.text}"`, announce: `Leader ${by} repealed the law: "${lawToGo.text}"` };
  }
  if (active.length >= S().maxLaws) return { ok: false, detail: `there are already ${S().maxLaws} laws: repeal one first` };
  const clean = text.replace(/[\r\n]+/g, " ").trim().slice(0, 120);
  if (clean.length < 5) return { ok: false, detail: "a law needs a few words" };
  q.add.run(clean, by, day ?? null, f);
  worldlog.write("law", `${by} made a law: "${clean}".`, { day });
  return { ok: true, detail: `made the law "${clean}"`, announce: `New law from leader ${by}: "${clean}"` };
}

/** The lines every villager sees about leader, laws and elections. */
export function text(me, f = roster.factionOf(me)) {
  if (!f) return "Civilisation: none, you are on your own (no leader, laws, chests or task board). You can found a civilisation or join one.";
  const lead = leader(f);
  const el = election(f);
  const lines = [];
  lines.push(lead ? `Leader: ${lead.name === me ? "YOU" : lead.name} (since day ${lead.since_day}).` : "Leader: none.");
  const ls = laws(f);
  lines.push(ls.length ? `Laws: ${ls.map((l, i) => `${i + 1}) ${l.text}`).join(" ")}` : "Laws: none yet.");
  if (el) lines.push(el.votes[me] ? `Election open: you voted for ${el.votes[me]}.` : "Election open: you have not voted yet (use vote).");
  return lines.join("\n");
}
