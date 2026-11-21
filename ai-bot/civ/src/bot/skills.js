// Plain-code skills: how a bot actually DOES each action. No AI calls in here at all.
//
// Every skill is `async (bot, action, task, ctx) => result`, where result is
//   { ok: true, detail: "got 16" }  or  { ok: false, detail: "no oak_log within 48 blocks" }
// The detail text goes back to the AI next time, so failures should say WHY (that is how it learns
// to craft a pickaxe instead of trying to mine stone with bare hands again).
// `task.cancelled` turns true when the AI picks something else; long loops check it.

import pf from "mineflayer-pathfinder";
import { Vec3 } from "vec3";
import { FOOD_ITEMS, isHostile, isNight, countItem, isProtected } from "./state.js";

const { goals } = pf;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Run a promise but give up after `ms` (and stop walking). */
async function withTimeout(bot, promise, ms, what) {
  let timer;
  const timeout = new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(`${what} took too long`)), ms); });
  try {
    return await Promise.race([promise, timeout]);
  } finally {
    clearTimeout(timer);
    if (bot.pathfinder.isMoving()) bot.pathfinder.stop();
  }
}

async function walkTo(bot, goal, ms = 120000) {
  await withTimeout(bot, bot.pathfinder.goto(goal), ms, "walking");
}

/**
 * Get out of a spot where the bot can't move (the server keeps pushing it back, for example when it
 * is wedged against a block edge in a hole). Plain code, no AI:
 *   1. hop in each of the 4 directions,
 *   2. if still stuck, dig the 2 blocks in front (feet and head) and walk through,
 *   3. if still stuck, dig the block above and jump up (out of a 1-wide hole).
 * Returns true if the bot moved at least 1.5 blocks.
 */
export async function escape(bot) {
  const start = bot.entity.position.clone();
  const moved = () => bot.entity.position.distanceTo(start) >= 1.5;
  try { bot.pathfinder.setGoal(null); } catch { /* not moving */ }
  bot.clearControlStates();
  const dirs = [[1, 0], [-1, 0], [0, 1], [0, -1]].sort(() => Math.random() - 0.5);
  for (const [dx, dz] of dirs) {
    const me = bot.entity.position;
    await bot.lookAt(me.offset(dx * 3, 1.6, dz * 3), true);
    bot.setControlState("forward", true);
    bot.setControlState("jump", true);
    await sleep(900);
    bot.clearControlStates();
    await sleep(200);
    if (moved()) return true;
  }
  for (const [dx, dz] of dirs) {
    const feet = bot.entity.position.floored();
    for (const dy of [0, 1]) {
      const b = bot.blockAt(feet.offset(dx, dy, dz));
      if (b && b.boundingBox === "block" && bot.canDigBlock(b) && !isProtected(bot, b.position) && b.name !== "bedrock") {
        try { await bot.dig(b, true); } catch { /* try the next one */ }
      }
    }
    await bot.lookAt(bot.entity.position.offset(dx * 3, 1.6, dz * 3), true);
    bot.setControlState("forward", true);
    await sleep(900);
    bot.clearControlStates();
    if (moved()) return true;
  }
  const above = bot.blockAt(bot.entity.position.floored().offset(0, 2, 0));
  if (above && above.boundingBox === "block" && bot.canDigBlock(above)) {
    try { await bot.dig(above, true); } catch { /* ignore */ }
  }
  bot.setControlState("jump", true);
  await sleep(600);
  bot.clearControlStates();
  return moved();
}

/** Step a few blocks to a random side: gets the bot out of spots where the pathfinder keeps failing. */
async function unstick(bot) {
  const me = bot.entity.position;
  const angle = Math.random() * Math.PI * 2;
  try {
    await walkTo(bot, new goals.GoalNearXZ(Math.round(me.x + Math.cos(angle) * 5), Math.round(me.z + Math.sin(angle) * 5), 1), 8000);
  } catch {
    bot.setControlState("jump", true);
    await sleep(400);
    bot.setControlState("jump", false);
  }
}

// ----------------------------------------------------------------------------------------------
// gather: collect blocks of a type
// ----------------------------------------------------------------------------------------------
const GROUPS = {
  wood: (n) => n.endsWith("_log") && !n.startsWith("stripped_"),
  log: (n) => n.endsWith("_log") && !n.startsWith("stripped_"),
  logs: (n) => n.endsWith("_log") && !n.startsWith("stripped_"),
  stone: (n) => n === "stone",
  cobblestone: (n) => n === "stone" || n === "cobblestone",
  coal: (n) => n === "coal_ore" || n === "deepslate_coal_ore",
  iron: (n) => n === "iron_ore" || n === "deepslate_iron_ore",
  copper: (n) => n === "copper_ore" || n === "deepslate_copper_ore",
  dirt: (n) => n === "dirt" || n === "grass_block",
};
// Item names the AI may use for the block that drops them.
const ITEM_TO_BLOCK = { raw_iron: "iron", coal: "coal", raw_copper: "copper", flint: "gravel", seeds: "short_grass", wheat_seeds: "short_grass" };

function blockMatcher(bot, target) {
  const t = ITEM_TO_BLOCK[target] || target || "wood";
  if (GROUPS[t]) return GROUPS[t];
  if (t.endsWith("_ore")) return (n) => n === t || n === `deepslate_${t}`;
  if (bot.registry.blocksByName[t]) return (n) => n === t;
  return null;
}

// Blocks a bot is on its way to break, so 20 bots don't all run to the same log.
const reserved = new Map(); // "x,y,z" -> { by, until }
const isReserved = (key, me) => { const r = reserved.get(key); return r && r.by !== me && r.until > Date.now(); };

async function gather(bot, a, task) {
  const test = blockMatcher(bot, a.target);
  if (!test) return { ok: false, detail: `"${a.target}" is not a block I can gather` };
  const ids = new Set(bot.registry.blocksArray.filter((b) => test(b.name)).map((b) => b.id));
  const want = a.amount ?? 8;
  const skip = new Set(); // blocks we couldn't reach
  const skipColumns = new Set(); // x,z columns (trees) we gave up on
  let got = 0, failures = 0;
  const deadline = Date.now() + 4 * 60000;

  while (got < want && !task.cancelled && Date.now() < deadline) {
    // Prefer blocks near our own height: the top logs of a tall tree are slow to path to and often unreachable.
    const me = bot.entity.position;
    const score = (p) => p.distanceTo(me) + 2 * Math.max(0, p.y - me.y - 1);
    const pos = bot.findBlocks({ matching: (b) => ids.has(b.type), maxDistance: 48, count: 40 })
      .filter((p) => !skip.has(p.toString()) && !skipColumns.has(`${p.x},${p.z}`) && !isProtected(bot, p) && !isReserved(p.toString(), bot.username)) // can't break blocks in spawn protection
      .sort((a, b) => score(a) - score(b))[0];
    if (!pos) return { ok: got > 0, detail: got ? `got ${got}, no more ${a.target} within 48 blocks` : `no ${a.target} I can break within 48 blocks (blocks near the world spawn are protected; explore to find some)` };
    const block = bot.blockAt(pos);
    // Stone and ores drop nothing without the right tool.
    if (block.harvestTools && !bot.inventory.items().some((it) => block.harvestTools[it.type])) {
      return { ok: got > 0, detail: `${got ? `got ${got}, then ` : ""}need a ${block.name.includes("iron") || block.name.includes("gold") || block.name.includes("diamond") ? "stone or better " : ""}pickaxe to gather ${block.name}` };
    }
    reserved.set(pos.toString(), { by: bot.username, until: Date.now() + 25000 });
    try {
      await withTimeout(bot, bot.collectBlock.collect(block, { ignoreNoPath: false }), 20000, "collecting");
      got++;
      failures = 0;
    } catch (e) {
      if (task.cancelled) break;
      skip.add(pos.toString());
      // Couldn't reach it: give up on the whole tree (this column and the ones around it), not just one log.
      for (let dx = -1; dx <= 1; dx++) for (let dz = -1; dz <= 1; dz++) skipColumns.add(`${pos.x + dx},${pos.z + dz}`);
      await unstick(bot);
      if (++failures >= 4) return { ok: got > 0, detail: `got ${got}, couldn't reach more ${a.target} (${e.message})` };
    }
  }
  if (task.cancelled) return { ok: true, detail: `stopped after ${got}` };
  return { ok: got > 0, detail: got >= want ? `got ${got}` : `got ${got} of ${want} before time ran out` };
}

// ----------------------------------------------------------------------------------------------
// craft
// ----------------------------------------------------------------------------------------------
function resolveItemName(bot, name) {
  const logs = bot.inventory.items().filter((it) => it.name.endsWith("_log")).sort((a, b) => b.count - a.count);
  const planksFromLogs = logs.length ? logs[0].name.replace(/^stripped_/, "").replace("_log", "_planks") : "oak_planks";
  if (["planks", "wood_planks", "wooden_planks"].includes(name)) return planksFromLogs;
  // Asked for oak_planks but only carrying dark oak logs? Make the planks we can make.
  if (name.endsWith("_planks") && logs.length && !logs.some((l) => l.name.replace(/^stripped_/, "") === name.replace("_planks", "_log"))) return planksFromLogs;
  if (bot.registry.itemsByName[name]) return name;
  if (name === "sticks") return "stick";
  if (name === "table" || name === "workbench") return "crafting_table";
  return null;
}

/** Find a crafting table nearby (walk to it), or place one from the inventory. */
/** The inventory updates a moment after crafting or picking up: wait up to `ms` for an item to show. */
export async function waitForItem(bot, name, ms = 2000) {
  for (let t = 0; t <= ms; t += 200) {
    const it = bot.inventory.items().find((i) => i.name === name);
    if (it) return it;
    await sleep(200);
  }
  return null;
}

async function ensureTable(bot) {
  const tableId = bot.registry.blocksByName.crafting_table.id;
  const found = bot.findBlock({ matching: tableId, maxDistance: 32 });
  if (found) {
    await walkTo(bot, new goals.GoalNear(found.position.x, found.position.y, found.position.z, 2), 60000);
    return bot.blockAt(found.position);
  }
  const item = await waitForItem(bot, "crafting_table", 2000);
  if (!item) return null;
  // Placing is not allowed in spawn protection: walk out of it first.
  if (isProtected(bot, bot.entity.position)) {
    const s = bot.civWorldSpawn, me = bot.entity.position;
    const out = me.minus(s); out.y = 0;
    const dir = out.norm() > 0.1 ? out.normalize() : new Vec3(1, 0, 0);
    const r = bot.civSpawnProtection + 4;
    try { await walkTo(bot, new goals.GoalNearXZ(Math.round(s.x + dir.x * r), Math.round(s.z + dir.z * r), 2), 60000); } catch { /* try anyway */ }
  }
  const base = bot.entity.position.floored();
  for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [-1, -1]]) {
    const spot = base.offset(dx, 0, dz);
    const below = bot.blockAt(spot.offset(0, -1, 0));
    const here = bot.blockAt(spot);
    if (below && below.boundingBox === "block" && here && here.name === "air") {
      await bot.equip(item, "hand");
      await bot.placeBlock(below, new Vec3(0, 1, 0));
      return bot.blockAt(spot);
    }
  }
  return null;
}

/** What a recipe needs, e.g. "3 cobblestone, 2 stick". */
function ingredientsText(bot, itemId) {
  const recipe = bot.recipesAll(itemId, null, true)[0];
  if (!recipe) return "an unknown recipe";
  // Any wood type works for planks/logs recipes, so don't name one (it confuses the AI).
  const generic = (n) => n.endsWith("_planks") ? "planks (any wood)" : n.endsWith("_log") ? "logs (any wood)" : n;
  return recipe.delta.filter((d) => d.count < 0).map((d) => `${-d.count} ${generic(bot.registry.items[d.id]?.name ?? String(d.id))}`).join(", ");
}

/** Turn logs into planks (and planks into sticks) when a recipe needs them. Returns true if it made any. */
async function prepareWood(bot, needs) {
  const count = (test) => bot.inventory.items().filter((it) => test(it.name)).reduce((n, it) => n + it.count, 0);
  let made = false;
  if ((/planks/.test(needs) || /stick/.test(needs)) && count((n) => n.endsWith("_planks")) < 8) {
    const log = bot.inventory.items().find((it) => it.name.endsWith("_log") && !it.name.startsWith("stripped_"));
    const planks = log && bot.registry.itemsByName[log.name.replace(/_log$/, "_planks")];
    const r = planks && bot.recipesFor(planks.id, null, 1, null)[0];
    if (r) made = (await craftSafely(bot, r, Math.min(2, log.count))) > 0; // 2 logs = 8 planks
  }
  if (/stick/.test(needs) && count((n) => n === "stick") < 4) {
    const r = bot.recipesFor(bot.registry.itemsByName.stick.id, null, 1, null)[0];
    if (r) made = (await craftSafely(bot, r, 1)) > 0 || made;
  }
  return made;
}

/** Craft a crafting table from the wood we carry (turning a log into planks if needed). */
async function craftTableFromWood(bot) {
  const tableItem = bot.registry.itemsByName.crafting_table;
  const planksCount = () => bot.inventory.items().filter((it) => it.name.endsWith("_planks")).reduce((n, it) => n + it.count, 0);
  if (planksCount() < 4) {
    const log = bot.inventory.items().find((it) => it.name.endsWith("_log") && !it.name.startsWith("stripped_"));
    const planks = log && bot.registry.itemsByName[log.name.replace(/_log$/, "_planks")];
    const r = planks && bot.recipesFor(planks.id, null, 1, null)[0];
    if (r) await craftSafely(bot, r, 1);
  }
  const r = bot.recipesFor(tableItem.id, null, 1, null)[0];
  if (!r) return false;
  return (await craftSafely(bot, r, 1)) > 0;
}

/**
 * Craft `times` times, ONE at a time with a pause after each. On 1.21.11, crafting several at once
 * (or quickly one after another) makes mineflayer's inventory fall behind the server: logs vanished
 * into the crafting grid and stray oak buttons appeared. One by one with 350 ms pauses is reliable.
 * Returns how many crafts worked.
 */
export async function craftSafely(bot, recipe, times, table = null) {
  // A chest window that is still closing would receive the items instead of the crafting grid
  // (we saw withdrawn logs go straight back into the chest), so wait until no window is open.
  for (let i = 0; i < 10 && bot.currentWindow; i++) { try { bot.closeWindow(bot.currentWindow); } catch { /* closed */ } await sleep(300); }
  let done = 0;
  for (let i = 0; i < times; i++) {
    if (!bot.recipesFor(recipe.result.id, null, 1, table).length) break;
    try { await bot.craft(recipe, 1, table); done++; } catch { break; }
    await sleep(350);
  }
  return done;
}

/** How many times we can craft this recipe with what we carry. */
function maxTimes(bot, recipe) {
  let n = Infinity;
  for (const d of recipe.delta.filter((x) => x.count < 0)) {
    const have = bot.inventory.items().filter((it) => it.type === d.id).reduce((s, it) => s + it.count, 0);
    n = Math.min(n, Math.floor(have / -d.count));
  }
  return Number.isFinite(n) ? n : 1;
}

async function craft(bot, a) {
  const name = resolveItemName(bot, a.target || "");
  if (!name) return { ok: false, detail: `"${a.target}" is not an item` };
  const item = bot.registry.itemsByName[name];
  let table = null;
  let recipes = bot.recipesFor(item.id, null, 1, null);
  // Missing planks or sticks but carrying logs? Make them first (saves an AI call for each step).
  if (!recipes.length && name !== "stick" && !name.endsWith("_planks") && await prepareWood(bot, ingredientsText(bot, item.id))) {
    recipes = bot.recipesFor(item.id, null, 1, null);
  }
  if (!recipes.length) {
    // Maybe it needs a crafting table (3x3 recipes).
    if (bot.recipesAll(item.id, null, true).length && !bot.recipesAll(item.id, null, null).length) {
      table = await ensureTable(bot);
      if (!table) {
        // No table near and none carried: make one first if we can (planks, or logs turned into planks).
        const made = await craftTableFromWood(bot);
        if (made) table = await ensureTable(bot);
      }
      if (!table) return { ok: false, detail: `${name} needs a crafting table (craft one from 4 planks)` };
      recipes = bot.recipesFor(item.id, null, 1, table);
      // Making the table may have used the planks the recipe needs: make more from logs and look again.
      if (!recipes.length && await prepareWood(bot, ingredientsText(bot, item.id))) recipes = bot.recipesFor(item.id, null, 1, table);
    }
    if (!recipes.length) return { ok: false, detail: `missing ingredients for ${name}: needs ${ingredientsText(bot, item.id)}` };
  }
  const recipe = recipes[0];
  // As many as we can make, up to the amount asked for, one at a time (see craftSafely).
  const possible = bot.recipesFor(item.id, null, 1, table).length ? Math.max(1, maxTimes(bot, recipe)) : 0;
  const times = Math.min(Math.ceil((a.amount ?? 1) / recipe.result.count), possible);
  let made = 0;
  if (times > 0) made = (await craftSafely(bot, recipe, times, table)) * recipe.result.count;
  return made ? { ok: true, detail: `made ${made} ${name}` } : { ok: false, detail: `ran out of ingredients for ${name}` };
}

// ----------------------------------------------------------------------------------------------
// eat, sleep
// ----------------------------------------------------------------------------------------------
export async function eat(bot) {
  const food = FOOD_ITEMS.map((n) => bot.inventory.items().find((it) => it.name === n)).find(Boolean);
  if (!food) return { ok: false, detail: "no food in inventory (hunt animals or farm)" };
  if (bot.food >= 20) return { ok: false, detail: "not hungry" };
  await bot.equip(food, "hand");
  await bot.consume();
  return { ok: true, detail: `ate ${food.name}, food ${bot.food}/20` };
}

async function sleepInBed(bot, _a, task) {
  if (!isNight(bot)) return { ok: false, detail: "can only sleep at night" };
  const bed = bot.findBlock({ matching: (b) => b.name.endsWith("_bed"), maxDistance: 32 });
  if (!bed) return { ok: false, detail: "no bed within 32 blocks" };
  await walkTo(bot, new goals.GoalNear(bed.position.x, bed.position.y, bed.position.z, 1), 60000);
  await bot.sleep(bot.blockAt(bed.position));
  while (bot.isSleeping && !task.cancelled) await sleep(2000);
  return { ok: true, detail: "slept until morning" };
}

// ----------------------------------------------------------------------------------------------
// movement: goto, follow, explore, flee
// ----------------------------------------------------------------------------------------------
function parsePlace(bot, target, ctx) {
  if (!target || target === "village" || target === "home") {
    return ctx.village ? { goal: new goals.GoalNear(ctx.village.x, ctx.village.y, ctx.village.z, 3), label: "the village" } : null;
  }
  const nums = target.split(/[ ,]+/).map(Number);
  if (nums.length === 2 && nums.every(Number.isFinite)) return { goal: new goals.GoalNearXZ(nums[0], nums[1], 2), label: `${nums[0]},${nums[1]}` };
  if (nums.length === 3 && nums.every(Number.isFinite)) return { goal: new goals.GoalNear(nums[0], nums[1], nums[2], 2), label: nums.join(",") };
  const player = Object.values(bot.players).find((p) => p.username.toLowerCase() === target.toLowerCase());
  if (player?.entity) {
    const p = player.entity.position;
    return { goal: new goals.GoalNear(p.x, p.y, p.z, 2), label: player.username };
  }
  return null;
}

async function goto(bot, a, _task, ctx) {
  const place = parsePlace(bot, a.target, ctx);
  if (!place) return { ok: false, detail: `don't know where "${a.target}" is (use x,z, village or a player I can see)` };
  await walkTo(bot, place.goal, 3 * 60000);
  return { ok: true, detail: `arrived at ${place.label}` };
}

async function follow(bot, a, task) {
  const player = Object.values(bot.players).find((p) => p.username.toLowerCase() === (a.target || "").toLowerCase());
  if (!player?.entity) return { ok: false, detail: `can't see ${a.target}` };
  bot.pathfinder.setGoal(new goals.GoalFollow(player.entity, 2), true);
  const until = Date.now() + (a.amount ?? 60) * 1000;
  while (Date.now() < until && !task.cancelled && player.entity) await sleep(1000);
  bot.pathfinder.setGoal(null);
  return { ok: true, detail: `followed ${player.username}` };
}

async function explore(bot, _a, _task, ctx) {
  // Pick a random direction, but lean back towards the village if we are far away.
  const me = bot.entity.position;
  let angle = Math.random() * Math.PI * 2;
  if (ctx.village && me.distanceTo(ctx.village) > 200) angle = Math.atan2(ctx.village.z - me.z, ctx.village.x - me.x) + (Math.random() - 0.5);
  const d = 30 + Math.random() * 30;
  const x = Math.round(me.x + Math.cos(angle) * d), z = Math.round(me.z + Math.sin(angle) * d);
  await walkTo(bot, new goals.GoalNearXZ(x, z, 3), 90000);
  return { ok: true, detail: `explored to ${x},${z}` };
}

export async function flee(bot, a = {}) {
  const me = bot.entity.position;
  const threat = Object.values(bot.entities).filter((e) => isHostile(e) && e.position.distanceTo(me) < 16)
    .sort((a, b) => a.position.distanceTo(me) - b.position.distanceTo(me))[0];
  // The AI asked to flee but nothing is close: running would just waste time. (A reflex flee, after
  // being hurt by something we can't see, still runs.)
  if (!threat && !a.reflex) return { ok: false, detail: "nothing dangerous within 16 m, no need to flee: get back to work" };
  const away = threat ? me.minus(threat.position).normalize() : new Vec3(Math.random() - 0.5, 0, Math.random() - 0.5).normalize();
  const x = Math.round(me.x + away.x * 20), z = Math.round(me.z + away.z * 20);
  bot.setControlState("sprint", true);
  try {
    await walkTo(bot, new goals.GoalNearXZ(x, z, 3), 20000);
  } catch {
    // ran out of time while running: that is fine, we moved away anyway
  } finally {
    bot.setControlState("sprint", false);
  }
  return { ok: true, detail: threat ? `ran away from ${threat.name}` : "moved away" };
}

// ----------------------------------------------------------------------------------------------
// attack
// ----------------------------------------------------------------------------------------------
async function equipWeapon(bot) {
  const order = ["netherite_sword", "diamond_sword", "iron_sword", "stone_sword", "wooden_sword", "iron_axe", "stone_axe", "wooden_axe"];
  const w = order.map((n) => bot.inventory.items().find((it) => it.name === n)).find(Boolean);
  if (w) await bot.equip(w, "hand");
}

/** Fight a player (self-defence): stay close, hit every ~0.55 s, stop when they are gone or we are hurt. */
async function fightPlayer(bot, name, task, seconds = 20) {
  await equipWeapon(bot);
  const until = Date.now() + seconds * 1000;
  let hits = 0;
  while (Date.now() < until && !task.cancelled && bot.health > 7) {
    const e = bot.players[name]?.entity;
    if (!e || e.position.distanceTo(bot.entity.position) > 16) break;
    bot.pathfinder.setGoal(new goals.GoalFollow(e, 1.5), true);
    if (e.position.distanceTo(bot.entity.position) < 3.2) {
      await bot.lookAt(e.position.offset(0, 1.4, 0), true);
      bot.attack(e);
      hits++;
    }
    await sleep(550);
  }
  bot.pathfinder.setGoal(null);
  if (bot.health <= 7) return { ok: false, detail: `I fought ${name} but got badly hurt and stopped` };
  const still = bot.players[name]?.entity;
  return { ok: true, detail: still ? `fought back against ${name} (${hits} hits); they are still around` : `drove ${name} away (${hits} hits)` };
}

export async function attack(bot, a, task, ctx = {}) {
  const me = bot.entity.position;
  // A player or villager: only in self-defence, against someone who hurt us in the last 2 minutes.
  const playerName = a.target && Object.keys(bot.players).find((n) => n.toLowerCase() === a.target.toLowerCase() && n !== bot.username);
  if (playerName) {
    const when = ctx.attackedBy?.[playerName];
    if (!when || Date.now() - when > 120000) return { ok: false, detail: `I only fight people in self-defence: ${playerName} hasn't attacked me recently` };
    return fightPlayer(bot, playerName, task);
  }
  const want = a.target && a.target !== "hostile" ? a.target : null;
  const mob = Object.values(bot.entities)
    .filter((e) => e !== bot.entity && e.position && e.position.distanceTo(me) < 16 && (want ? e.name === want : isHostile(e)))
    .sort((x, y) => x.position.distanceTo(me) - y.position.distanceTo(me))[0];
  if (!mob) return { ok: false, detail: `no ${want || "hostile mob"} nearby` };
  if (mob.type === "player") return { ok: false, detail: "to fight a person, name them (only in self-defence)" };
  await equipWeapon(bot);
  // Our own small fight loop (the mineflayer-pvp plugin broke block collecting): keep close, look at
  // the mob and hit it about every 0.6 s, which is the sword cooldown.
  bot.pathfinder.setGoal(new goals.GoalFollow(mob, 1.5), true);
  const until = Date.now() + 30000;
  while (Date.now() < until && !task.cancelled && bot.entities[mob.id] && bot.health > 6) {
    if (mob.position.distanceTo(bot.entity.position) < 3.2) {
      await bot.lookAt(mob.position.offset(0, mob.height * 0.8, 0), true);
      bot.attack(mob);
    }
    await sleep(600);
  }
  bot.pathfinder.setGoal(null);
  if (bot.health <= 6) return { ok: false, detail: `stopped fighting ${mob.name}: too hurt` };
  if (bot.entities[mob.id]) return { ok: false, detail: `${mob.name} got away` };
  // Walk over the drops (meat, leather, feathers, wool...) so they are picked up.
  const at = mob.position.clone();
  try { await walkTo(bot, new goals.GoalNear(at.x, at.y, at.z, 0.5), 8000); await sleep(800); } catch { /* drops may be lost */ }
  return { ok: true, detail: `killed ${mob.name}` };
}

// ----------------------------------------------------------------------------------------------
async function wait(bot, a, task) {
  const until = Date.now() + (a.amount ?? 30) * 1000;
  while (Date.now() < until && !task.cancelled) await sleep(1000);
  return { ok: true, detail: "rested" };
}

export const SKILLS = { gather, craft, eat, goto, follow, explore, attack, flee, sleep: sleepInBed, wait };
export { countItem, walkTo, withTimeout, sleep, prepareWood, equipWeapon };
