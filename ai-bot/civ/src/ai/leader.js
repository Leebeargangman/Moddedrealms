// Optional "elder" AI (config.leader.enabled, off by default): every config.leader.everyMinutes during a
// session, ONE call to a bigger model reads a short summary of the whole village and answers with a plan
// (shown to every villager in its prompt as "Elders' plan") and up to 3 jobs for the task board.
// The villagers' own elected leader and laws still rule; the elders only advise.

import { config, provider, options } from "../config.js";
import { log } from "../log.js";
import { ask } from "./client.js";
import { kv } from "../village/db.js";
import * as board from "../village/board.js";
import * as roster from "../village/roster.js";
import * as society from "../village/society.js";
import * as chests from "../village/chests.js";
import * as worldlog from "../village/worldlog.js";
import * as builder from "../build/builder.js";
import { village } from "../village/village.js";
import * as settings from "../settings.js";

const SYSTEM = `You are the council of elders of an AI villager civilisation in Minecraft survival. Every 15 minutes you read a summary of the village and give it direction. Reply with ONE JSON object:
{"plan":"<at most 3 short sentences: what the village should focus on next and why>","jobs":[{"action":"gather|craft|explore|attack","target":"<item, block or mob>","amount":<number>}]}
Up to 3 jobs, only things the village really lacks now, in Minecraft names (oak_log, cobblestone, stone_pickaxe, cow...). Follow the progression: wood and tools, then food and shelter (huts), storage, then stone, iron, farms, houses, walls. Keep villagers alive: food and shelter before ambition. Never mention imposters or accuse anyone: you do not know who they are.`;

const SCHEMA = {
  type: "object",
  properties: {
    plan: { type: "string" },
    jobs: { type: "array", items: { type: "object", properties: { action: { type: "string" }, target: { type: "string" }, amount: { type: "integer" } }, required: ["action", "target", "amount"], additionalProperties: false } },
  },
  required: ["plan", "jobs"],
  additionalProperties: false,
};

function summary(f) {
  const alive = roster.alive().filter((v) => (v.faction || roster.factionNames()[0]) === f);
  const roles = {};
  for (const v of alive) roles[v.role] = (roles[v.role] || 0) + 1;
  const bots = [...village.bots.values()].filter((b) => b.ready && roster.factionOf(b.def.name) === f);
  const hungry = bots.filter((b) => b.bot.food <= 10).length;
  const lead = society.leader(f);
  const many = roster.factionNames().length > 1;
  return [
    many ? `You advise the ${f} civilisation only. Others: ${roster.factionNames().filter((x) => x !== f).join(", ")}${settings.get().war ? " (at war)" : ""}.` : "",
    `Day ${village.day ?? "?"}. ${alive.length} villagers alive (${Object.entries(roles).map(([r, n]) => `${n} ${r}`).join(", ")}), ${bots.length} online, ${hungry} hungry.`,
    `Leader: ${lead?.name || "none"}. Laws: ${society.laws(f).map((l) => l.text).join(" | ") || "none"}.`,
    chests.text(f),
    builder.text(f),
    board.text("nobody", f),
    `Recent news: ${worldlog.publicNews(6).map((n) => n.text).join(" ")}`,
    `Last plan: ${kv(planKey(f))?.plan || "none"}`,
  ].filter(Boolean).join("\n");
}

const planKey = (f) => (f === roster.factionNames()[0] ? "elderPlan" : `elderPlan:${f}`);

async function consultAll() {
  for (const f of roster.factionNames()) await consult(f);
}

async function consult(f) {
  const model = options.mock ? null : provider === "openai" ? config.leader.openaiModel : config.leader.model;
  const res = await ask({ bot: "elders", kind: "leader", trigger: "leader", system: [{ type: "text", text: SYSTEM }], user: summary(f), maxTokens: 400, schema: SCHEMA, model });
  if (!res.text) return log.warn("elders", `no plan this time (${res.reason})`);
  let out;
  try { out = JSON.parse(res.text.slice(res.text.indexOf("{"), res.text.lastIndexOf("}") + 1)); } catch { return log.warn("elders", "unusable plan"); }
  const plan = String(out.plan || "").replace(/\s+/g, " ").slice(0, 300);
  if (!plan) return;
  kv(planKey(f), { plan, at: Date.now() });
  for (const j of (out.jobs || []).slice(0, 3)) {
    board.post({ by: "the elders", faction: f, action: String(j.action).toLowerCase(), target: String(j.target || "").toLowerCase().replace(/^minecraft:/, "") || null, amount: Number.isFinite(j.amount) ? Math.max(1, Math.min(64, j.amount)) : null });
  }
  worldlog.write("plan", `${roster.factionNames().length > 1 ? `${f}'s` : "The"} elders advise: ${plan}`, { day: village.day });
  log.info("elders", `${plan}${res.cost ? ` [$${res.cost.toFixed(5)}]` : ""}`);
}

/** Start consulting the elders for this session (returns the interval, or null when off). */
export function startElders() {
  if (!config.leader?.enabled) return null;
  log.info("elders", `elder AI on: every ${config.leader.everyMinutes} min`);
  setTimeout(() => consultAll().catch((e) => log.error("elders", e.message)), 90000); // once the villagers are in
  return setInterval(() => consultAll().catch((e) => log.error("elders", e.message)), config.leader.everyMinutes * 60000);
}

/** The line every villager sees (empty when the elders are off or haven't spoken). */
export function elderLine(name) {
  if (!config.leader?.enabled) return "";
  const p = kv(planKey(roster.factionOf(name)));
  return p ? `Elders' plan: ${p.plan}` : "";
}
