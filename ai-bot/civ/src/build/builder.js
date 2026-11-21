// The builder: turns a blueprint into real blocks, in survival, as plain code (no AI calls).
//
//   1. Pick a plot: the nearest flat-enough spot near the village that doesn't overlap other buildings.
//   2. Get materials: from the bot's inventory, then the village chests. If still short, it posts
//      "gather" jobs on the task board and stops, saying what is missing.
//   3. Clear the plot (dig what is in the way) and fill holes in the floor with dirt.
//   4. Place blocks bottom layer first. A block can only be placed against a block that is already
//      there, so each pass places every block that has a neighbour, until nothing more can be placed.
//   5. Progress is saved: anyone can continue a half-built building ("build" target = "#3").
// One call works for up to 6 minutes; then it reports progress and the bot can carry on.

import pf from "mineflayer-pathfinder";
import { Vec3 } from "vec3";
import { db } from "../village/db.js";
import * as board from "../village/board.js";
import * as chests from "../village/chests.js";
import * as worldlog from "../village/worldlog.js";
import { isProtected } from "../bot/state.js";
import { walkTo, sleep, craftSafely } from "../bot/skills.js";
import * as blueprints from "./blueprints.js";

const { goals } = pf;

db.exec(`
  CREATE TABLE IF NOT EXISTS buildings (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    blueprint TEXT NOT NULL,
    x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,   -- plot corner; y = floor level
    sx INTEGER NOT NULL, sy INTEGER NOT NULL, sz INTEGER NOT NULL,
    status    TEXT NOT NULL DEFAULT 'building',   -- building / done
    builder   TEXT,
    placed    INTEGER NOT NULL DEFAULT 0,
    total     INTEGER NOT NULL,
    created   INTEGER NOT NULL,
    finished  INTEGER
  );
`);
const q = {
  all: db.prepare("SELECT * FROM buildings ORDER BY id"),
  get: db.prepare("SELECT * FROM buildings WHERE id = ?"),
  add: db.prepare("INSERT INTO buildings (blueprint, x, y, z, sx, sy, sz, builder, total, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"),
  progress: db.prepare("UPDATE buildings SET placed = ?, builder = ? WHERE id = ?"),
  done: db.prepare("UPDATE buildings SET status = 'done', placed = total, finished = ? WHERE id = ?"),
  unfinished: db.prepare("SELECT * FROM buildings WHERE status = 'building' ORDER BY id"),
};
export const all = () => q.all.all().map((r) => ({ ...r }));

/** True if this block position is part of a building (so the pathfinder never digs through walls). */
let cache = { at: 0, list: [] };
export function insideBuilding(pos) {
  if (Date.now() - cache.at > 10000) cache = { at: Date.now(), list: all() };
  return cache.list.some((b) => pos.x >= b.x && pos.x < b.x + b.sx && pos.z >= b.z && pos.z < b.z + b.sz && pos.y >= b.y && pos.y < b.y + b.sy);
}

/** Prompt lines: buildings done and in progress. */
export function text() {
  const list = all();
  if (!list.length) return "Buildings: none yet.";
  const done = list.filter((b) => b.status === "done");
  const open = list.filter((b) => b.status !== "done");
  return `Buildings: ${done.length} done (${done.slice(-5).map((b) => `${b.blueprint} at ${b.x},${b.z}`).join(", ") || "-"})` +
    (open.length ? ` · unfinished: ${open.map((b) => `#${b.id} ${b.blueprint} ${b.placed}/${b.total} at ${b.x},${b.z}`).join(", ")}` : "");
}

// --- materials ------------------------------------------------------------------------------------
const matches = (material, name) =>
  material === name || (material === "planks" && name.endsWith("_planks")) || (material === "log" && name.endsWith("_log") && !name.startsWith("stripped_"));
const SOFT = (name) => ["air", "cave_air", "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "snow", "dandelion", "poppy",
  "leaf_litter", "bush", "short_dry_grass", "tall_dry_grass"].includes(name) || name.endsWith("_flower") || name.endsWith("_sapling");

function carried(bot, material) {
  return bot.inventory.items().filter((it) => matches(material, it.name)).reduce((n, it) => n + it.count, 0);
}

async function logsToPlanks(bot, wantPlanks, keepLogs) {
  // One at a time with pauses (craftSafely): fast crafting made logs vanish and oak buttons appear.
  let times = Math.min(carried(bot, "log") - keepLogs, Math.ceil((wantPlanks - carried(bot, "planks")) / 4));
  for (const log of bot.inventory.items().filter((it) => it.name.endsWith("_log") && !it.name.startsWith("stripped_"))) {
    if (times <= 0) break;
    const planks = bot.registry.itemsByName[log.name.replace(/_log$/, "_planks")];
    const r = planks && bot.recipesFor(planks.id, null, 1, null)[0];
    if (!r) continue;
    const n = Math.min(log.count, times);
    times -= await craftSafely(bot, r, n);
  }
}

// --- plot -------------------------------------------------------------------------------------------
/** Highest solid ground block in this column (ignores leaves, plants and logs of trees). */
function groundY(bot, x, z, fromY) {
  for (let y = fromY + 12; y > fromY - 12; y--) {
    const b = bot.blockAt(new Vec3(x, y, z));
    if (!b) return null;
    if (b.boundingBox === "block" && !b.name.includes("leaves") && !b.name.endsWith("_log")) return { y, name: b.name };
  }
  return null;
}

function findPlot(bot, centre, size) {
  const others = all();
  const overlaps = (x, z) => others.some((b) => x < b.x + b.sx + 2 && x + size.x + 2 > b.x && z < b.z + b.sz + 2 && z + size.z + 2 > b.z);
  const nearChests = (x, z) => chests.all().some((c) => c.x >= x - 2 && c.x <= x + size.x + 1 && c.z >= z - 2 && c.z <= z + size.z + 1);
  const candidates = [];
  for (let r = 8; r <= 48; r += 3) for (let a = 0; a < 16; a++) {
    const ang = (a / 16) * Math.PI * 2;
    candidates.push([Math.round(centre.x + Math.cos(ang) * r), Math.round(centre.z + Math.sin(ang) * r)]);
  }
  for (const [x, z] of candidates) {
    if (overlaps(x, z) || nearChests(x, z)) continue;
    const heights = [];
    let bad = false;
    for (let dx = 0; dx < size.x && !bad; dx++) for (let dz = 0; dz < size.z && !bad; dz++) {
      const g = groundY(bot, x + dx, z + dz, Math.floor(centre.y));
      if (!g || ["water", "lava", "ice"].includes(g.name) || isProtected(bot, new Vec3(x + dx, g.y, z + dz))) bad = true;
      else heights.push(g.y);
    }
    if (bad || !heights.length) continue;
    const lo = Math.min(...heights), hi = Math.max(...heights);
    if (hi - lo <= 1) return { x, y: hi, z };
  }
  return null;
}

// --- placing ------------------------------------------------------------------------------------------
const FACES = [[0, -1, 0], [0, 1, 0], [1, 0, 0], [-1, 0, 0], [0, 0, 1], [0, 0, -1]].map(([x, y, z]) => new Vec3(x, y, z));

async function placeAt(bot, pos, material) {
  const item = bot.inventory.items().find((it) => matches(material, it.name));
  if (!item) return "no material";
  // Get within reach, but don't stand where the block goes.
  const eyes = () => bot.entity.position.offset(0, 1.62, 0);
  if (eyes().distanceTo(pos.offset(0.5, 0.5, 0.5)) > 4.2) {
    try { await walkTo(bot, new goals.GoalNearXZ(pos.x, pos.z, 2), 30000); } catch { /* try from here */ }
  }
  const feet = bot.entity.position.floored();
  if ((feet.x === pos.x && feet.z === pos.z) && (pos.y === feet.y || pos.y === feet.y + 1)) {
    try { await walkTo(bot, new goals.GoalNearXZ(pos.x + 2, pos.z + 2, 1), 8000); } catch { return "in the way"; }
  }
  if (eyes().distanceTo(pos.offset(0.5, 0.5, 0.5)) > 4.5) return "out of reach";
  const ref = FACES.map((f) => ({ f, b: bot.blockAt(pos.plus(f)) })).find(({ b }) => b && b.boundingBox === "block");
  if (!ref) return "nothing to place against";
  await bot.equip(item, "hand");
  try {
    await bot.placeBlock(ref.b, ref.f.scaled(-1));
  } catch (e) {
    // placeBlock sometimes times out waiting for the server's confirmation although the block is there.
    const now = bot.blockAt(pos);
    if (!now || !matches(material, now.name)) return e.message;
  }
  return null;
}

/**
 * The "build" action. target = a blueprint name ("hut") or an unfinished building ("#3").
 * ctx = { village, bot name, day, post(job) }.
 */
export async function build(bot, a, task, ctx) {
  const list = await blueprints.all();
  const want = String(a.target || "").trim().toLowerCase();
  let row;
  if (/^#?\d+$/.test(want)) {
    row = q.get.get(Number(want.replace("#", "")));
    if (!row) return { ok: false, detail: `there is no building ${want}` };
    if (row.status === "done") return { ok: false, detail: `building #${row.id} is already finished` };
  } else {
    // Continue our own unfinished building of this type instead of starting a second one.
    row = q.unfinished.all().find((b) => b.blueprint === want);
  }
  const bp = list[row ? row.blueprint : want];
  if (!bp) return { ok: false, detail: `unknown blueprint "${a.target}": choose one of ${Object.keys(list).join(", ")}` };

  // Materials: carry what we need, taking from the chests first.
  const need = blueprints.materials(bp);
  const short = {};
  for (const [m, n] of Object.entries(need)) {
    const placedAlready = row ? Math.round((row.placed / row.total) * n) : 0;
    const missing = n - placedAlready - carried(bot, m);
    if (missing > 0) short[m] = missing;
  }
  if (Object.keys(short).length && ctx.withdraw) {
    for (const [m, n] of Object.entries(short)) {
      await ctx.withdraw(m === "log" ? "wood" : m, Math.min(n, 256));
      if (m === "planks" && carried(bot, "planks") < need.planks) await ctx.withdraw("wood", Math.ceil((need.planks - carried(bot, "planks")) / 4));
    }
  }
  // Planks come from logs: turn carried logs into planks (keeping the logs a blueprint itself needs).
  if (need.planks) await logsToPlanks(bot, need.planks, need.log || 0);
  if (process.env.CIV_DEBUG) console.log("build materials", JSON.stringify(need), "carry planks", carried(bot, "planks"), "logs", carried(bot, "log"));
  for (const m of Object.keys(short)) { const left = need[m] - carried(bot, m); if (left <= 0) delete short[m]; else short[m] = left; }
  // Start with what we have if it is at least a good part (walls first), else ask the village for help.
  const totalShort = Object.values(short).reduce((s, n) => s + n, 0);
  if (totalShort > bp.blocks.length * 0.7) {
    for (const [m, n] of Object.entries(short)) ctx.post({ action: "gather", target: m === "planks" || m === "log" ? "wood" : m, amount: Math.min(64, m === "planks" ? Math.ceil(n / 4) : n) });
    return { ok: false, detail: `not enough materials for a ${row ? row.blueprint : want}: missing ${Object.entries(short).map(([m, n]) => `${n} ${m}`).join(", ")} (4 planks = 1 log). I posted gather jobs.` };
  }

  if (!row) {
    const plot = findPlot(bot, ctx.village, bp.size);
    if (!plot) return { ok: false, detail: "found no flat free plot near the village (try after clearing some trees)" };
    const id = Number(q.add.run(want, plot.x, plot.y, plot.z, bp.size.x, bp.size.y, bp.size.z, ctx.name, bp.blocks.length, Date.now()).lastInsertRowid);
    row = q.get.get(id);
    worldlog.write("build", `${ctx.name} started building a ${want} at ${plot.x},${plot.z}.`, { day: ctx.day });
  }
  const origin = new Vec3(row.x, row.y, row.z);
  const deadline = Date.now() + 6 * 60000;

  // Clear the plot: dig anything in the way inside the footprint, fill holes in the floor.
  for (let dx = 0; dx < bp.size.x && !task.cancelled; dx++) for (let dz = 0; dz < bp.size.z && !task.cancelled; dz++) {
    for (let dy = bp.size.y; dy >= 1; dy--) {
      const p = origin.offset(dx, dy, dz);
      const b = bot.blockAt(p);
      const wanted = bp.blocks.find((k) => k.x === dx && k.y === dy && k.z === dz);
      if (!b || b.name === "air" || (wanted && matches(wanted.block, b.name))) continue;
      if (SOFT(b.name) && !wanted) continue; // grass and flowers: placing replaces them anyway
      if (!bot.canDigBlock(b) && b.name !== "water") {
        try { await walkTo(bot, new goals.GoalNear(p.x, p.y, p.z, 3), 20000); } catch { /* reach from here */ }
      }
      try { if (bot.canDigBlock(bot.blockAt(p))) await bot.dig(bot.blockAt(p), true); } catch { /* leave it */ }
    }
    const floor = origin.offset(dx, 0, dz);
    if (bot.blockAt(floor)?.boundingBox !== "block" && carried(bot, "dirt")) await placeAt(bot, floor, "dirt");
  }

  // Place, layer by layer; a block needs a neighbour to be placed against, so loop until no progress.
  let pending = [...bp.blocks].sort((p1, p2) => p1.y - p2.y);
  let placedNow = 0, lastProblem = "";
  for (let pass = 0; pass < 8 && pending.length && !task.cancelled && Date.now() < deadline; pass++) {
    const next = [];
    for (const k of pending) {
      if (task.cancelled || Date.now() > deadline) { next.push(k); continue; }
      const p = origin.offset(k.x, k.y, k.z);
      const b = bot.blockAt(p);
      if (b && matches(k.block, b.name)) continue; // already there
      const problem = await placeAt(bot, p, k.block);
      if (process.env.CIV_DEBUG && problem) console.log("place", p.toString(), k.block, "->", problem, "carry", carried(bot, k.block));
      if (problem) { lastProblem = problem; next.push(k); if (problem === "no material") break; } else placedNow++;
    }
    if (next.length === pending.length) { pending = next; break; } // no progress in a whole pass
    pending = next;
    q.progress.run(bp.blocks.length - pending.length, ctx.name, row.id);
    await sleep(50);
  }
  const placed = bp.blocks.filter((k) => { const b = bot.blockAt(origin.offset(k.x, k.y, k.z)); return b && matches(k.block, b.name); }).length;
  q.progress.run(placed, ctx.name, row.id);
  if (placed >= bp.blocks.length) {
    q.done.run(Date.now(), row.id);
    worldlog.write("build", `${ctx.name} finished a ${row.blueprint} at ${row.x},${row.z}.`, { day: ctx.day });
    ctx.announce?.(`${ctx.name} finished building a ${row.blueprint} at ${row.x},${row.z}.`);
    return { ok: true, detail: `finished the ${row.blueprint} (#${row.id}) at ${row.x},${row.z}` };
  }
  return {
    ok: placedNow > 0,
    detail: `building #${row.id} ${row.blueprint}: ${placed}/${bp.blocks.length} blocks placed` +
      (lastProblem === "no material" ? " (ran out of materials: deposit/withdraw or gather more, then build again)" : lastProblem ? ` (stopped: ${lastProblem}; build #${row.id} again to continue)` : ` (build #${row.id} again to continue)`),
  };
}
