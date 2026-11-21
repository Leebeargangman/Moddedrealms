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
import * as roster from "../village/roster.js";
import { isProtected } from "../bot/state.js";
import { walkTo, sleep, craftSafely, SKILLS } from "../bot/skills.js";
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
if (!db.prepare("PRAGMA table_info(buildings)").all().some((c) => c.name === "faction")) db.exec("ALTER TABLE buildings ADD COLUMN faction TEXT");
const q = {
  setFaction: db.prepare("UPDATE buildings SET faction = ? WHERE id = ?"),
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

const firstF = () => roster.factionNames()[0] ?? "";
const mine = (b, f) => (b.faction || firstF()) === (f || firstF());

/** Prompt lines: this civilisation's buildings, done and in progress. */
export function text(f) {
  const list = all().filter((b) => mine(b, f));
  if (!list.length) return "Buildings: none yet.";
  const done = list.filter((b) => b.status === "done");
  const open = list.filter((b) => b.status !== "done");
  return `Buildings: ${done.length} done (${done.slice(-5).map((b) => `${b.blueprint} at ${b.x},${b.z}`).join(", ") || "-"})` +
    (open.length ? ` · unfinished: ${open.map((b) => `#${b.id} ${b.blueprint} ${b.placed}/${b.total} at ${b.x},${b.z}`).join(", ")}` : "");
}

// --- materials ------------------------------------------------------------------------------------
// Generic materials in blueprints: any wood type will do ("stairs" = oak_stairs, spruce_stairs...).
const WOOD = /^(oak|spruce|birch|jungle|acacia|dark_oak|mangrove|cherry|pale_oak|bamboo|crimson|warped)_/;
const GENERIC = {
  planks: (n) => n.endsWith("_planks"),
  log: (n) => (n.endsWith("_log") || n.endsWith("_stem")) && !n.startsWith("stripped_"),
  stairs: (n) => n.endsWith("_stairs") && WOOD.test(n),
  slab: (n) => n.endsWith("_slab") && WOOD.test(n),
  door: (n) => n.endsWith("_door") && WOOD.test(n),
  fence: (n) => n.endsWith("_fence") && WOOD.test(n),
  torch: (n) => n === "torch" || n === "wall_torch",
};
// Blocks whose item has another name (a torch on a wall is the item "torch").
const ITEM_OF = { wall_torch: "torch", redstone_wall_torch: "redstone_torch", soul_wall_torch: "soul_torch" };
export const itemOf = (block) => ITEM_OF[block] || block;
const matches = (material, name) => material === name || itemOf(material) === itemOf(name) || Boolean(GENERIC[material]?.(name));

/** Wood type the villager can make parts from (the planks or logs it carries), oak by default. */
function woodType(bot) {
  const it = bot.inventory.items().find((i) => (i.name.endsWith("_planks") || i.name.endsWith("_log")) && WOOD.test(i.name));
  return it ? it.name.replace(/_(planks|log)$/, "") : "oak";
}
/** The real item to craft for a material ("stairs" -> "spruce_stairs"), or null if it can't be crafted. */
function craftName(bot, material) {
  const w = woodType(bot);
  const generic = { planks: `${w}_planks`, stairs: `${w}_stairs`, slab: `${w}_slab`, door: `${w}_door`, fence: `${w}_fence`, torch: "torch" }[material];
  const name = generic || itemOf(material);
  return bot.registry.itemsByName[name] && bot.recipesAll?.(bot.registry.itemsByName[name].id, null, true)?.length ? name : null;
}
// Roughly how many planks one of each crafted part costs (to turn enough logs into planks first).
const PLANKS_PER = { planks: 1, stairs: 1.5, slab: 0.5, door: 2, fence: 1.7, ladder: 1.2, chest: 8, crafting_table: 4, oak_trapdoor: 1, torch: 0.25 };
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
  const nearChests = (x, z) => chests.everyChest().some((c) => c.x >= x - 2 && c.x <= x + size.x + 1 && c.z >= z - 2 && c.z <= z + size.z + 1);
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

const DIR = { north: new Vec3(0, 0, -1), south: new Vec3(0, 0, 1), east: new Vec3(1, 0, 0), west: new Vec3(-1, 0, 0) };
const solid = (b) => b && b.boundingBox === "block";

/**
 * Place one blueprint block k ({ block, props }) at pos. Blocks with a direction are placed the way the
 * game decides it: stairs and doors face the way the player looks (so we stand behind them), wall torches
 * and ladders hang on the block behind them, and top slabs/stairs are placed against the block above.
 */
async function placeAt(bot, pos, k) {
  const material = typeof k === "string" ? k : k.block;
  const props = (typeof k === "object" && k.props) || {};
  const item = bot.inventory.items().find((it) => matches(material, it.name));
  if (!item) return "no material";
  const facing = DIR[props.facing];
  const onWall = facing && (/wall_torch$/.test(material) || material === "ladder" || (material === "torch" && props.facing));
  const looksThatWay = facing && !onWall && (/_stairs$|_door$|_trapdoor$|^stairs$|^door$|_gate$/.test(material));
  const eyes = () => bot.entity.position.offset(0, 1.62, 0);
  // Where to stand: behind a block that must face away from us, else anywhere within reach.
  if (looksThatWay) {
    const spot = pos.minus(facing.scaled(2));
    try { await walkTo(bot, new goals.GoalNearXZ(spot.x, spot.z, 0.7), 20000); } catch { /* place from here */ }
    // Couldn't get behind it (a slope, a wall): place it from anywhere close; it may face another way.
    if (eyes().distanceTo(pos.offset(0.5, 0.5, 0.5)) > 4.5) {
      try { await walkTo(bot, new goals.GoalNearXZ(pos.x, pos.z, 2), 20000); } catch { /* try from here */ }
    }
  } else if (eyes().distanceTo(pos.offset(0.5, 0.5, 0.5)) > 4.2) {
    try { await walkTo(bot, new goals.GoalNearXZ(pos.x, pos.z, 2), 30000); } catch { /* try from here */ }
  }
  const feet = bot.entity.position.floored();
  if ((feet.x === pos.x && feet.z === pos.z) && (pos.y === feet.y || pos.y === feet.y + 1)) {
    try { await walkTo(bot, new goals.GoalNearXZ(pos.x + 2, pos.z + 2, 1), 8000); } catch { return "in the way"; }
  }
  if (eyes().distanceTo(pos.offset(0.5, 0.5, 0.5)) > 4.5 && !looksThatWay) {
    // High blocks (a roof ridge): stand right underneath, inside the building, and reach straight up.
    try { await walkTo(bot, new goals.GoalNearXZ(pos.x, pos.z, 0.6), 20000); } catch { /* still try */ }
  }
  if (eyes().distanceTo(pos.offset(0.5, 0.5, 0.5)) > 4.5) return "out of reach";
  // What to place against.
  let ref = null;
  if (onWall) {
    const wall = bot.blockAt(pos.minus(facing));
    if (solid(wall)) ref = { b: wall, f: facing.scaled(-1) };
  } else if (props.type === "top" || props.half === "top") {
    const above = bot.blockAt(pos.offset(0, 1, 0));
    if (solid(above)) ref = { b: above, f: new Vec3(0, 1, 0) };
  }
  if (!ref) ref = FACES.map((f) => ({ f, b: bot.blockAt(pos.plus(f)) })).find(({ b }) => solid(b));
  if (!ref) return "nothing to place against";
  await bot.equip(item, "hand");
  if (looksThatWay) await bot.look(Math.atan2(-facing.x, -facing.z), 0, true); // face the way the block must face
  try {
    await bot.placeBlock(ref.b, ref.f.scaled(-1));
  } catch (e) {
    // placeBlock sometimes times out waiting for the server's confirmation although the block is there.
    const now = bot.blockAt(pos);
    if (!now || !matches(material, now.name)) return e.message;
  }
  return null;
}

/** Make the crafted parts a blueprint needs (stairs, slabs, doors, fences, torches...) from what we carry. */
async function craftParts(bot, need, task, ctx) {
  for (const [m, n] of Object.entries(need)) {
    if (m === "planks" || m === "log" || task.cancelled) continue;
    const missing = n - carried(bot, m);
    if (missing <= 0) continue;
    const name = craftName(bot, m);
    if (!name) continue;
    // A recipe makes a few at a time and the craft skill stops when the planks run out: try a few rounds.
    for (let round = 0; round < 3 && carried(bot, m) < n && !task.cancelled; round++) {
      const before = carried(bot, m);
      if (m !== "torch") await logsToPlanks(bot, Math.ceil((n - before) * (PLANKS_PER[m] ?? 1)) + carried(bot, "planks"), 0);
      try {
        const r = await SKILLS.craft(bot, { action: "craft", target: name, amount: n - before }, task, ctx);
        if (process.env.CIV_DEBUG) console.log("craft part", name, JSON.stringify(r));
      } catch (e) { if (process.env.CIV_DEBUG) console.log("craft part", name, "threw", e.message); }
      await sleep(400);
      if (carried(bot, m) <= before) break; // nothing more can be made
    }
  }
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
    row = q.unfinished.all().find((b) => b.blueprint === want && mine(b, ctx.faction));
  }
  const bp = list[row ? row.blueprint : want];
  if (!bp) return { ok: false, detail: `unknown blueprint "${a.target}": choose one of ${Object.keys(list).join(", ")}` };

  // Materials: carry what we need, taking from the chests first.
  const need = blueprints.materials(bp, { required: true }); // optional blocks (torches...) are skipped if missing
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
  // Planks come from logs: enough for the planks themselves and for the parts made from them.
  const plankNeed = Object.entries(need).reduce((s, [m, n]) => s + (PLANKS_PER[m] ?? (GENERIC[m] ? 0 : 0)) * Math.max(0, n - (m === "planks" ? 0 : carried(bot, m))), 0);
  if (plankNeed) await logsToPlanks(bot, Math.ceil(plankNeed), need.log || 0);
  await craftParts(bot, blueprints.materials(bp), task, ctx); // also optional parts (torches) when we can
  if (process.env.CIV_DEBUG) console.log("build materials", JSON.stringify(need), "carry planks", carried(bot, "planks"), "logs", carried(bot, "log"));
  for (const m of Object.keys(short)) { const left = need[m] - carried(bot, m); if (left <= 0) delete short[m]; else short[m] = left; }
  // Start with what we have if it is at least a good part (walls first), else ask the village for help.
  const totalShort = Object.values(short).reduce((s, n) => s + n, 0);
  if (totalShort > bp.blocks.length * 0.7) {
    for (const [m, n] of Object.entries(short)) {
      // Wooden parts (stairs, doors...) are made from wood: ask for logs instead.
      const wooden = m === "planks" || m === "log" || PLANKS_PER[m] !== undefined || GENERIC[m];
      ctx.post({ action: "gather", target: wooden && m !== "torch" ? "wood" : m === "torch" ? "coal" : m, amount: Math.min(64, wooden ? Math.ceil(n * (PLANKS_PER[m] ?? 1) / 4) + 1 : n) });
    }
    return { ok: false, detail: `not enough materials for a ${row ? row.blueprint : want}: missing ${Object.entries(short).map(([m, n]) => `${n} ${m}`).join(", ")} (4 planks = 1 log). I posted gather jobs.` };
  }

  if (!row) {
    const plot = findPlot(bot, ctx.village || bot.entity.position.floored(), bp.size); // on your own: build where you are
    if (!plot) return { ok: false, detail: "found no flat free plot near the village (try after clearing some trees)" };
    const id = Number(q.add.run(want, plot.x, plot.y, plot.z, bp.size.x, bp.size.y, bp.size.z, ctx.name, bp.blocks.length, Date.now()).lastInsertRowid);
    q.setFaction.run(ctx.faction || firstF(), id);
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
        try { await walkTo(bot, new goals.GoalNear(p.x, p.y, p.z, 2), 20000); } catch { /* reach from here */ }
      }
      try {
        if (bot.canDigBlock(bot.blockAt(p))) await bot.dig(bot.blockAt(p), true);
        else if (process.env.CIV_DEBUG) console.log("clear: can't dig", b.name, "at", p.toString(), "from", bot.entity.position.floored().toString());
      } catch (e) { if (process.env.CIV_DEBUG) console.log("clear: dig failed", b.name, p.toString(), e.message); }
    }
    const floor = origin.offset(dx, 0, dz);
    const found = bp.blocks.find((k) => k.x === dx && k.y === 0 && k.z === dz);
    const fb = bot.blockAt(floor);
    if (found && fb && fb.name !== "air" && !matches(found.block, fb.name) && bot.canDigBlock(fb)) {
      // A foundation block goes here (cobblestone...): take the ground block out first.
      try { await walkTo(bot, new goals.GoalNear(floor.x, floor.y, floor.z, 3), 15000); await bot.dig(fb, true); } catch { /* placed later if possible */ }
    } else if (!found && fb?.boundingBox !== "block" && carried(bot, "dirt")) await placeAt(bot, floor, "dirt");
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
      if (k.opt && !carried(bot, k.block)) continue; // optional (torches...): skip when we have none
      const problem = await placeAt(bot, p, k);
      if (!problem && itemOf(k.block) === "chest") chests.add(p, ctx.faction); // a chest in a building is a village chest
      if (process.env.CIV_DEBUG && problem) console.log("place", p.toString(), k.block, "->", problem, "carry", carried(bot, k.block));
      if (problem) { lastProblem = problem; next.push(k); if (problem === "no material") break; } else placedNow++;
    }
    if (next.length === pending.length) { pending = next; break; } // no progress in a whole pass
    pending = next;
    q.progress.run(bp.blocks.length - pending.length, ctx.name, row.id);
    await sleep(50);
  }
  const placed = bp.blocks.filter((k) => { if (k.opt) return true; const b = bot.blockAt(origin.offset(k.x, k.y, k.z)); return b && matches(k.block, b.name); }).length;
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
