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
const q = {
  active: db.prepare("SELECT * FROM laws WHERE active = 1 ORDER BY id"),
  add: db.prepare("INSERT INTO laws (text, by, day) VALUES (?, ?, ?)"),
  repeal: db.prepare("UPDATE laws SET active = 0 WHERE id = ?"),
};

const S = () => config.society;
export const leader = () => kv("leader");          // { name, since_day } or null
export const election = () => kv("election");      // { started_at, started_day, votes: { voter: candidate } } or null
export const laws = () => q.active.all().map((r) => ({ ...r }));

/** Called by the village every few seconds. `announce(text, wake)` tells every living villager. */
export function tick({ day, aliveNames, announce }) {
  const lead = leader();
  if (lead && !aliveNames.includes(lead.name)) {
    kv("leader", null);
    announce(`The village has no leader now: ${lead.name} is gone.`, "society");
  }
  const el = election();
  const termOver = lead && day !== null && day - lead.since_day >= S().leaderTermDays;
  if (!el && aliveNames.length >= 2 && (!leader() || termOver)) {
    kv("election", { started_at: Date.now(), started_day: day, votes: {} });
    worldlog.write("election", `An election for village leader has started.`, { day });
    announce(`Election! The village is choosing a leader${termOver ? ` (${lead.name}'s term is over)` : ""}. Use the "vote" action with target = the villager you choose (you may vote for yourself).`, "society");
    return;
  }
  if (el) {
    const voters = Object.keys(el.votes).filter((v) => aliveNames.includes(v));
    const timeUp = Date.now() - el.started_at > S().electionMinutes * 60000;
    if (voters.length >= aliveNames.length || (timeUp && voters.length > 0)) closeElection({ day, aliveNames, announce });
    else if (timeUp) { kv("election", { ...el, started_at: Date.now() }); } // nobody voted: keep it open
  }
}

function closeElection({ day, aliveNames, announce }) {
  const el = election();
  const tally = {};
  for (const [voter, cand] of Object.entries(el.votes)) {
    if (aliveNames.includes(voter) && aliveNames.includes(cand)) tally[cand] = (tally[cand] || 0) + 1;
  }
  const best = Math.max(0, ...Object.values(tally));
  const tied = Object.keys(tally).filter((c) => tally[c] === best);
  kv("election", null);
  if (!tied.length) return;
  const winner = tied[Math.floor(Math.random() * tied.length)];
  kv("leader", { name: winner, since_day: day ?? 0 });
  const result = Object.entries(tally).sort((a, b) => b[1] - a[1]).map(([c, n]) => `${c} ${n}`).join(", ");
  worldlog.write("election", `${winner} was elected leader (votes: ${result}).`, { day });
  announce(`${winner} won the election and is now the village leader (votes: ${result}).`, "society");
}

export function vote(voter, candidate, aliveNames) {
  const el = election();
  if (!el) return { ok: false, detail: "there is no election right now" };
  const c = aliveNames.find((n) => n.toLowerCase() === String(candidate).toLowerCase());
  if (!c) return { ok: false, detail: `${candidate} is not a living villager` };
  el.votes[voter] = c;
  kv("election", el);
  return { ok: true, detail: `voted for ${c}` };
}

/** "law" action: add a law, or "repeal N". Leader only. */
export function law(by, text, day) {
  const lead = leader();
  if (!lead || lead.name !== by) return { ok: false, detail: "only the village leader can make or repeal laws (suggest it to the leader instead)" };
  const m = /^repeal\s*#?(\d+)/i.exec(text.trim());
  const active = laws();
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
  q.add.run(clean, by, day ?? null);
  worldlog.write("law", `${by} made a law: "${clean}".`, { day });
  return { ok: true, detail: `made the law "${clean}"`, announce: `New law from leader ${by}: "${clean}"` };
}

/** The lines every villager sees about leader, laws and elections. */
export function text(me) {
  const lead = leader();
  const el = election();
  const lines = [];
  lines.push(lead ? `Leader: ${lead.name === me ? "YOU" : lead.name} (since day ${lead.since_day}).` : "Leader: none.");
  const ls = laws();
  lines.push(ls.length ? `Laws: ${ls.map((l, i) => `${i + 1}) ${l.text}`).join(" ")}` : "Laws: none yet.");
  if (el) lines.push(el.votes[me] ? `Election open: you voted for ${el.votes[me]}.` : "Election open: you have not voted yet (use vote).");
  return lines.join("\n");
}
