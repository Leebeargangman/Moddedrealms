// The village at a glance, as one JSON object: used by the website's Bots page (sent by link.js) and by a
// small local status page on http://127.0.0.1:3100 (only reachable from the VPS itself).
//
// Secrets (who the imposters are, true causes of death) are included under `secret`: the Bots page is
// staff-only and hides them behind a "Show secrets" switch, so a stream or screenshot doesn't spoil it.

import http from "node:http";
import { config, provider, model, options } from "../config.js";
import { log } from "../log.js";
import * as ledger from "../usage/ledger.js";
import { sessionStats } from "../usage/report.js";
import { budgetState, isPaused } from "../ai/client.js";
import { stats as rateStats } from "../ai/ratelimit.js";
import * as roster from "../village/roster.js";
import * as society from "../village/society.js";
import * as chests from "../village/chests.js";
import * as worldlog from "../village/worldlog.js";
import * as crime from "../village/crime.js";
import * as builder from "../build/builder.js";
import { village } from "../village/village.js";
import { schedule, inWindow, nextStart, windowText } from "../schedule.js";
import { describe } from "../ai/actions.js";
import * as board from "../village/board.js";
import * as settings from "../settings.js";
import { costModel } from "../usage/costmodel.js";
import { centreOfFaction } from "../village/village.js";
import * as factions from "../village/factions.js";


export function snapshot(control) {
  const s = control.getSession();
  const st = s ? sessionStats(Math.max(1, [...village.bots.values()].filter((b) => b.ready).length)) : null;
  const today = ledger.totalsForDay();
  const lead = society.leader();
  const el = society.election();
  const vill = (v) => {
    const b = village.bots.get(v.name);
    const e = b?.ready ? b.bot.entity : null;
    return {
      name: v.name, role: v.role, personality: v.personality, status: v.status,
      online: Boolean(e),
      health: e ? Math.round(b.bot.health) : null, food: e ? b.bot.food : null,
      pos: e ? [Math.round(e.position.x), Math.round(e.position.y), Math.round(e.position.z)] : null,
      task: b?.currentTask ? describe(b.currentTask.action) : null,
      lastSaid: b?.lastSaidText || null,
      leader: Boolean(roster.factionOf(v.name)) && society.leader(roster.factionOf(v.name))?.name === v.name,
      faction: roster.factionOf(v.name), // null = on their own (Free for all)
      joinedDay: v.joined_day, leftDay: v.left_day,
    };
  };
  const all = roster.all();
  return {
    kind: "civ",
    // Empty lists so the rest of the Bots page (which expects them on every host) keeps working.
    bots: [], teams: [], crowds: [], accounts: [],
    civ: {
      session: s ? { id: s.id, running: true, started: s.started, reason: s.reason, endsAt: s.endsAt } : { running: false },
      schedule: { enabled: options.scheduled, window: windowText(), timezone: schedule().timezone, inWindow: inWindow(), nextStart: nextStart()?.getTime() ?? null },
      ai: { provider, model, mock: options.mock, paused: isPaused(), budget: config.budget.dailyUsd, spentToday: today.cost, callsToday: today.calls, state: budgetState() },
      stats: st && {
        minutes: st.minutes, calls: st.totals.calls, cost: st.totals.cost, perCall: st.avgPerCall, callsPerBotPerMinute: st.callsPerBotPerMinute,
        cacheHitRate: st.cacheHitRate, projected20Bots: st.projected20Bots, perWake: st.perWake.slice(0, 8),
      },
      rateLimit: { ...rateStats, perMinute: config.rateLimit.requestsPerMinute },
      lag: control.lag(),
      day: village.day,
      villagers: all.filter((v) => v.status === "alive").map(vill),
      gone: all.filter((v) => v.status !== "alive").slice(-30).map((v) => ({ name: v.name, role: v.role, status: v.status, leftDay: v.left_day })),
      leader: lead, election: el ? { open: true, votes: Object.keys(el.votes).length } : null,
      laws: society.laws().map((l) => l.text),
      buildings: builder.all().slice(-20).map((b) => ({ id: b.id, blueprint: b.blueprint, x: b.x, z: b.z, status: b.status, placed: b.placed, total: b.total, builder: b.builder, faction: b.faction || roster.factionNames()[0] })),
      chests: chests.totals(),
      board: board.openJobs(),
      // One entry per civilisation (a single village has just one).
      factions: roster.factionNames().map((f) => {
        const c = centreOfFaction(f);
        const l = society.leader(f), e = society.election(f);
        return {
          name: f, centre: c ? [Math.round(c.x), Math.round(c.z)] : null,
          alive: all.filter((v) => v.status === "alive" && roster.factionOf(v.name) === f).length,
          relations: factions.isFree() ? factions.relationsText(f)
            : settings.get().war ? [`at war with ${roster.factionNames().filter((x) => x !== f).join(", ")}`] : [],
          leader: l, election: e ? { open: true, votes: Object.keys(e.votes).length } : null,
          laws: society.laws(f).map((x) => x.text), chests: chests.totals(f), board: board.openJobs(f),
        };
      }),
      settings: settings.get(),
      costModel: costModel(),
      news: worldlog.recent(25).map((r) => ({ at: r.at, day: r.day, kind: r.kind, text: r.text })),
      secret: {
        imposters: all.filter((v) => v.imposter).map((v) => ({ name: v.name, status: v.status })),
        killWindow: crime.window(village.day),
        log: worldlog.recent(40, true).filter((r) => !r.public).map((r) => ({ at: r.at, day: r.day, text: r.text })),
      },
    },
  };
}

/** A tiny local page: http://127.0.0.1:3100 (JSON at /api/status). Only listens on localhost. */
export function startStatusServer(control) {
  const port = config.website?.statusPort;
  if (!port) return;
  const server = http.createServer((req, res) => {
    if (req.url.startsWith("/api/status")) {
      res.writeHead(200, { "content-type": "application/json" });
      return res.end(JSON.stringify(snapshot(control)));
    }
    const s = snapshot(control).civ;
    const esc = (t) => String(t ?? "").replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" })[c]);
    res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
    res.end(`<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="10"><title>Civ status</title>
<body style="font:14px system-ui;margin:20px;max-width:900px">
<h1>AI civilisation</h1>
<p>Session: ${s.session.running ? `running since ${new Date(s.session.started).toLocaleTimeString()} (${esc(s.session.reason)})` : "not running"} · schedule ${esc(s.schedule.window)} ${esc(s.schedule.timezone)} · day ${esc(s.day)}</p>
<p>AI: ${esc(s.ai.model)} (${esc(s.ai.provider)})${s.ai.paused ? " · PAUSED" : ""} · today $${s.ai.spentToday.toFixed(4)} of $${s.ai.budget} · lag p99 ${s.lag.p99.toFixed(0)} ms</p>
<p>Leader: ${esc(s.leader?.name || "none")} · Laws: ${s.laws.map(esc).join(" · ") || "none"}</p>
<h2>Villagers (${s.villagers.length})</h2><ul>${s.villagers.map((v) => `<li><b>${esc(v.name)}</b> ${esc(v.role)} ${v.online ? `· hp ${v.health} food ${v.food} · ${esc(v.task || "idle")}` : "· offline"}</li>`).join("")}</ul>
<h2>News</h2><ul>${s.news.slice().reverse().map((n) => `<li>${esc(n.text)}</li>`).join("")}</ul></body>`);
  });
  server.on("error", (e) => log.warn("status", `local status page not started: ${e.message}`));
  server.listen(port, "127.0.0.1", () => log.info("status", `local status page on http://127.0.0.1:${port}`));
}
