// Plain-code skills for getting ahead (no AI calls in here):
//   gearup  craft and equip the best tools, weapon and armour possible from what you carry and the
//           village chests (wooden -> stone -> iron)
//   mine    dig down to ore depth and tunnel until you find iron (or coal, or another ore)
//   smelt   smelt in a furnace (raw_iron -> iron_ingot, sand -> glass, logs -> charcoal, raw meat -> cooked)
//   hide    survive the night: inside your civilisation's building, dug into the ground, or walled in
// Yesterday's numbers that made these necessary: 1 stone pickaxe in a whole day with 90 cobblestone in
// the chests, no way to get iron at all, and "no ground to dig a shelter" 875 times (114 deaths).

import pf from "mineflayer-pathfinder";
import { Vec3 } from "vec3";
import { SKILLS, walkTo, sleep, equipWeapon, prepareWood } from "./skills.js";
import { isNight, isProtected } from "./state.js";
import * as chests from "../village/chests.js";
import * as builder from "../build/builder.js";

const { goals } = pf;
const count = (bot, test) => bot.inventory.items().filter((it) => (typeof test === "string" ? it.name === test : test(it.name))).reduce((s, it) => s + it.count, 0);

// --- gear up -------------------------------------------------------------------------------------------
const TIERS = ["wooden", "stone", "iron", "diamond", "netherite"];
const tierOf = (name) => TIERS.findIndex((t) => name.startsWith(`${t}_`));
function bestTier(bot, kind) {
  let best = -1;
  for (const it of bot.inventory.items()) if (it.name.endsWith(`_${kind}`)) best = Math.max(best, tierOf(it.name));
  return best;
}
const NEED = { pickaxe: 3, axe: 3, sword: 2, shovel: 1 }; // material per tool (plus sticks)
const ARMOUR = [["helmet", 5, "head"], ["chestplate", 8, "torso"], ["leggings", 7, "legs"], ["boots", 4, "feet"]];

async function gearup(bot, _a, task, ctx) {
  const box = ctx.faction ? chests.totals(ctx.faction) : {};
  const have = (n) => count(bot, n) + (box[n] || 0);
  const take = async (n, amount) => {
    const missing = amount - count(bot, n);
    if (missing > 0 && (box[n] || 0) > 0 && ctx.withdraw) await ctx.withdraw(n, Math.min(missing, box[n]));
  };
  const made = [];
  for (const kind of ["pickaxe", "sword", "axe"]) {
    if (task.cancelled) break;
    const current = bestTier(bot, kind);
    const iron = have("iron_ingot") >= NEED[kind];
    const stone = have("cobblestone") >= NEED[kind];
    const target = iron && current < 2 ? "iron" : stone && current < 1 ? "stone" : current < 0 ? "wooden" : null;
    if (!target) continue;
    if (target === "iron") await take("iron_ingot", NEED[kind]);
    if (target === "stone") await take("cobblestone", NEED[kind]);
    await take("stick", 2);
    let r = await SKILLS.craft(bot, { action: "craft", target: `${target}_${kind}`, amount: 1 }, task, ctx);
    if (!r.ok && !task.cancelled) { await sleep(800); r = await SKILLS.craft(bot, { action: "craft", target: `${target}_${kind}`, amount: 1 }, task, ctx); } // one retry
    if (r.ok) made.push(`${target}_${kind}`);
  }
  // Iron armour, as far as the ingots go.
  for (const [piece, ingots, slot] of ARMOUR) {
    if (task.cancelled) break;
    if (bot.inventory.items().some((it) => it.name.endsWith(`_${piece}`)) || bot.inventory.slots[bot.getEquipmentDestSlot(slot)]) continue;
    if (have("iron_ingot") < ingots) continue;
    await take("iron_ingot", ingots);
    const r = await SKILLS.craft(bot, { action: "craft", target: `iron_${piece}`, amount: 1 }, task, ctx);
    if (r.ok) made.push(`iron_${piece}`);
  }
  await equipArmour(bot);
  await equipWeapon(bot);
  const tools = ["pickaxe", "axe", "sword"].map((k) => { const t = bestTier(bot, k); return t >= 0 ? `${TIERS[t]} ${k}` : null; }).filter(Boolean);
  if (!made.length) return { ok: false, detail: `nothing better to make: I have ${tools.join(", ") || "no tools"}. Stone tools need cobblestone, iron needs iron_ingot (mine iron, then smelt raw_iron)` };
  return { ok: true, detail: `made ${made.join(", ")}; now I have ${tools.join(", ")}` };
}

/** Put on any armour we carry (best first). */
export async function equipArmour(bot) {
  for (const [piece, , slot] of ARMOUR) {
    const worn = bot.inventory.slots[bot.getEquipmentDestSlot(slot)];
    const best = bot.inventory.items().filter((it) => it.name.endsWith(`_${piece}`)).sort((a, b) => tierOf(b.name) - tierOf(a.name))[0];
    if (best && (!worn || tierOf(worn.name) < tierOf(best.name))) { try { await bot.equip(best, slot); } catch { /* fine */ } }
  }
}

// --- mine --------------------------------------------------------------------------------------------------
const ORE_GROUPS = {
  iron: (n) => n === "iron_ore" || n === "deepslate_iron_ore", coal: (n) => n === "coal_ore" || n === "deepslate_coal_ore",
  copper: (n) => n === "copper_ore" || n === "deepslate_copper_ore", gold: (n) => n === "gold_ore" || n === "deepslate_gold_ore",
  diamond: (n) => n === "diamond_ore" || n === "deepslate_diamond_ore", redstone: (n) => n.includes("redstone_ore"), lapis: (n) => n.includes("lapis_ore"),
};
const ORE_DEPTH = { iron: 16, coal: 40, copper: 40, gold: -10, diamond: -54, redstone: -50, lapis: 0 };

/** Equip the best pickaxe; if it broke, make a stone one from the cobblestone dug on the way. */
async function keepPickaxe(bot, need, task, ctx) {
  const equipBest = async () => {
    const pick = bot.inventory.items().filter((it) => it.name.endsWith("_pickaxe")).sort((x, y) => tierOf(y.name) - tierOf(x.name))[0];
    if (pick && bot.heldItem?.type !== pick.type) { try { await bot.equip(pick, "hand"); } catch { /* fine */ } }
  };
  bot.civPickWhy = "";
  if (bestTier(bot, "pickaxe") >= need) { await equipBest(); return true; }
  if (need > 1 || task.cancelled) { bot.civPickWhy = need > 1 ? "an iron pickaxe can't be made down here" : "stopped"; return false; } // iron pickaxes can't be made down here
  const stone = () => count(bot, (n) => ["cobblestone", "cobbled_deepslate", "blackstone"].includes(n));
  const make = async (target) => {
    if (count(bot, "stick") < 2) await prepareWood(bot, "stick");
    if (count(bot, "stick") < 2) { bot.civPickWhy = "no sticks (and no planks to make them)"; return false; }
    bot.civMakingPick = true; // the watcher in mine() must not stop the walk to a crafting table
    let r;
    try { r = await SKILLS.craft(bot, { action: "craft", target, amount: 1 }, task, ctx); } finally { bot.civMakingPick = false; }
    // Take the table back if we placed one (we may need it again further on).
    const table = bot.findBlock({ matching: bot.registry.blocksByName.crafting_table.id, maxDistance: 4 });
    if (table && !count(bot, "crafting_table")) { try { await bot.dig(table, true); await sleep(600); } catch { /* leave it */ } }
    await equipBest();
    if (!r.ok) bot.civPickWhy = r.detail;
    return r.ok;
  };
  // Too little cobblestone for a stone pickaxe: a wooden one from planks first, to dig some.
  if (stone() < 3 && bestTier(bot, "pickaxe") < 0) {
    if (count(bot, (n) => n.endsWith("_planks")) < 5) await prepareWood(bot, "planks");
    if (!(await make("wooden_pickaxe"))) return false;
    if (need === 0) return true;
  }
  if (stone() < 3 && bestTier(bot, "pickaxe") >= 0) {
    const ids = new Set(["stone", "deepslate", "cobblestone", "cobbled_deepslate"].map((n) => bot.registry.blocksByName[n]?.id));
    const near = bot.findBlocks({ matching: (b) => ids.has(b.type), maxDistance: 5, count: 6 });
    for (const p of near) {
      if (stone() >= 3 || task.cancelled) break;
      try { await bot.collectBlock.collect(bot.blockAt(p), { ignoreNoPath: true }); } catch { /* next */ }
    }
  }
  if (stone() < 3) { bot.civPickWhy = "not enough cobblestone (3)"; return bestTier(bot, "pickaxe") >= need; }
  return (await make("stone_pickaxe")) && bestTier(bot, "pickaxe") >= need;
}

/** Pick up what our digging dropped nearby (the pathfinder digs but doesn't collect). */
async function pickUpDrops(bot, task) {
  const drops = Object.values(bot.entities).filter((e) => e.name === "item" && e.position.distanceTo(bot.entity.position) < 6).slice(0, 8);
  if (!drops.length || task.cancelled) return;
  try { await bot.collectBlock.collect(drops, { ignoreNoPath: true }); } catch { /* fine */ }
}

/** The mine skill, with a watcher: the moment the pickaxe breaks, stop the pathfinder (it would carry
 *  on tunnelling the planned path with bare hands); mineInner then makes a new one or gives up. */
async function mine(bot, a, task, ctx) {
  const check = () => {
    if (bestTier(bot, "pickaxe") < 0 && !bot.civMakingPick && bot.pathfinder.isMoving()) bot.pathfinder.stop();
  };
  const onSlot = (_slot, oldItem, newItem) => { if (oldItem?.name?.endsWith("_pickaxe") && !newItem) check(); }; // it just broke
  const watch = setInterval(check, 500);
  bot.inventory.on("updateSlot", onSlot);
  try { return await mineInner(bot, a, task, ctx); } finally { clearInterval(watch); bot.inventory.off("updateSlot", onSlot); }
}

async function mineInner(bot, a, task, ctx) {
  const ore = String(a.target || "iron").replace(/_ore$|^raw_/, "").replace(/^deepslate_/, "");
  const test = ORE_GROUPS[ore];
  if (!test) return { ok: false, detail: `I can mine for: ${Object.keys(ORE_GROUPS).join(", ")}` };
  const need = ["iron", "copper", "lapis"].includes(ore) ? 1 : ["gold", "diamond", "redstone"].includes(ore) ? 2 : 0;
  if (bestTier(bot, "pickaxe") < need) {
    return { ok: false, detail: `I need a better pickaxe for ${ore} (iron/copper/lapis need stone, gold/diamond/redstone need iron): gearup first` };
  }
  // Pickaxes wear out (a wooden one lasts ~60 blocks, stone ~130). Take what's needed to make a new one
  // underground: 2 sticks and a crafting table (the cobblestone comes from the digging itself).
  const box = ctx.faction ? chests.totals(ctx.faction) : {};
  if (count(bot, "stick") < 2) {
    if ((box.stick || 0) >= 2 && ctx.withdraw) await ctx.withdraw("stick", 4);
    if (count(bot, "stick") < 2 && !count(bot, (n) => n.endsWith("_planks") || n.endsWith("_log")) && ctx.withdraw) await ctx.withdraw("wood", 2);
    if (count(bot, "stick") < 2) await prepareWood(bot, "stick"); // logs -> planks -> sticks
  }
  if (!count(bot, "crafting_table")) {
    if ((box.crafting_table || 0) > 0 && ctx.withdraw) await ctx.withdraw("crafting_table", 1);
    if (!count(bot, "crafting_table")) await SKILLS.craft(bot, { action: "craft", target: "crafting_table", amount: 1 }, task, ctx);
  }
  let broke = false;
  const pickOk = async () => {
    if (await keepPickaxe(bot, need, task, ctx)) return true;
    broke = true;
    return false;
  };
  const want = Math.max(1, Math.min(a.amount ?? 6, 32));
  const ids = new Set(bot.registry.blocksArray.filter((b) => test(b.name)).map((b) => b.id));
  const deadline = Date.now() + 8 * 60000;
  // Only what we came for counts (coal found on the way to iron is a bonus, not iron).
  const drop = { iron: "raw_iron", gold: "raw_gold", copper: "raw_copper", coal: "coal", diamond: "diamond", redstone: "redstone", lapis: "lapis_lazuli" }[ore];
  const mined = (n) => n === drop || test(n);
  const got0 = count(bot, mined);
  const collected = () => count(bot, mined) - got0;
  await pickOk();

  const grabVisible = async () => {
    const pos = bot.findBlocks({ matching: (b) => ids.has(b.type), maxDistance: 16, count: want });
    for (const p of pos) {
      if (task.cancelled || Date.now() > deadline || collected() >= want || !(await pickOk())) break;
      try { await bot.collectBlock.collect(bot.blockAt(p), { ignoreNoPath: true }); } catch { /* next */ }
    }
  };
  await grabVisible();
  // Not enough in sight: dig down to ore depth (the pathfinder digs a way), then tunnel and look again.
  const targetY = Math.max(ORE_DEPTH[ore], -58);
  for (let tries = 0; tries < 3 && collected() < want && bot.entity.position.y > targetY + 4 && !task.cancelled && Date.now() < deadline && await pickOk(); tries++) {
    try { await walkTo(bot, new goals.GoalY(targetY), 150000); } catch { /* we got as deep as we could (or the pickaxe broke) */ }
    await pickUpDrops(bot, task);
    await grabVisible();
  }
  const angle = Math.random() * Math.PI * 2;
  for (let leg = 0; leg < 6 && collected() < want && !task.cancelled && Date.now() < deadline && await pickOk(); leg++) {
    const me = bot.entity.position;
    const dir = angle + leg * (Math.PI / 2);
    try { await walkTo(bot, new goals.GoalNearXZ(Math.round(me.x + Math.cos(dir) * 16), Math.round(me.z + Math.sin(dir) * 16), 2), 60000); } catch { /* blocked: next leg */ }
    await pickUpDrops(bot, task);
    await grabVisible();
  }
  const n = collected();
  const depth = Math.round(bot.entity.position.y);
  if (broke) {
    // No pickaxe left and no way to make one: stop rather than dig on with bare hands.
    return { ok: n > 0, detail: `${n ? `mined ${n} ${ore}, then ` : ""}my pickaxe broke at y ${depth} and I couldn't make another: ${bot.civPickWhy || "no materials"}. Stopped mining: goto village, then gearup` };
  }
  if (!n) return { ok: false, detail: `found no ${ore} down at y ${depth} (try another place, or mine coal)` };
  const next = ["iron", "gold", "copper"].includes(ore) ? `, then smelt raw_${ore}` : "";
  return { ok: true, detail: `mined ${n} ${ore} (now at y ${depth}; goto village to bring it back${next})` };
}

// --- smelt ------------------------------------------------------------------------------------------------
const SMELTS = { raw_iron: "iron_ingot", raw_gold: "gold_ingot", raw_copper: "copper_ingot", sand: "glass", cobblestone: "stone",
  beef: "cooked_beef", porkchop: "cooked_porkchop", chicken: "cooked_chicken", mutton: "cooked_mutton", cod: "cooked_cod", salmon: "cooked_salmon", potato: "baked_potato" };

async function findOrMakeFurnace(bot, task, ctx) {
  const id = bot.registry.blocksByName.furnace.id;
  const found = bot.findBlock({ matching: id, maxDistance: 32 });
  if (found) {
    try { await walkTo(bot, new goals.GoalNear(found.position.x, found.position.y, found.position.z, 2), 30000); } catch { /* checked */ }
    if (bot.entity.position.distanceTo(found.position) <= 4.5) return bot.blockAt(found.position);
  }
  if (!count(bot, "furnace")) {
    if (count(bot, "cobblestone") < 8 && ctx.withdraw) await ctx.withdraw("cobblestone", 8);
    await SKILLS.craft(bot, { action: "craft", target: "furnace", amount: 1 }, task, ctx);
  }
  const item = bot.inventory.items().find((it) => it.name === "furnace");
  if (!item) return null;
  const base = bot.entity.position.floored();
  const free = (b) => b && b.boundingBox === "empty" && !["water", "lava"].includes(b.name);
  for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [-1, -1], [2, 0], [0, 2]]) {
    const spot = base.offset(dx, 0, dz);
    const below = bot.blockAt(spot.offset(0, -1, 0));
    if (!below || below.boundingBox !== "block" || !free(bot.blockAt(spot)) || isProtected(bot, spot)) continue;
    await bot.equip(item, "hand");
    try { await bot.placeBlock(below, new Vec3(0, 1, 0)); } catch { /* checked */ }
    await sleep(300);
    if (bot.blockAt(spot)?.name === "furnace") return bot.blockAt(spot);
  }
  return null;
}

async function smelt(bot, a, task, ctx) {
  const input = String(a.target || "raw_iron").replace(/^iron$/, "raw_iron").replace(/^iron_ore$/, "raw_iron");
  const output = SMELTS[input] || (input.endsWith("_log") ? "charcoal" : null);
  if (!output) return { ok: false, detail: `I can smelt: ${Object.keys(SMELTS).join(", ")}, or logs into charcoal` };
  if (!count(bot, input) && ctx.withdraw) await ctx.withdraw(input, a.amount ?? 16);
  const n = Math.min(count(bot, input), a.amount ?? 64);
  if (!n) return { ok: false, detail: `I have no ${input} to smelt${input === "raw_iron" ? " (mine iron first)" : ""}` };
  // Fuel: coal/charcoal (8 items each), else planks (1.5 each) or logs.
  const fuelItem = ["coal", "charcoal"].map((f) => bot.inventory.items().find((it) => it.name === f)).find(Boolean)
    || bot.inventory.items().find((it) => it.name.endsWith("_planks")) || bot.inventory.items().find((it) => it.name.endsWith("_log") && it.name !== input);
  if (!fuelItem) {
    if (ctx.withdraw) await ctx.withdraw("coal", Math.ceil(n / 8));
    if (!count(bot, "coal") && ctx.withdraw) await ctx.withdraw("wood", Math.ceil(n / 1.5));
  }
  const fuel = ["coal", "charcoal"].map((f) => bot.inventory.items().find((it) => it.name === f)).find(Boolean)
    || bot.inventory.items().find((it) => it.name.endsWith("_planks")) || bot.inventory.items().find((it) => it.name.endsWith("_log") && it.name !== input);
  if (!fuel) return { ok: false, detail: "no fuel: I need coal, charcoal, planks or logs" };
  const block = await findOrMakeFurnace(bot, task, ctx);
  if (!block) return { ok: false, detail: "no furnace and couldn't make one (8 cobblestone and a crafting table)" };
  const per = fuel.name === "coal" || fuel.name === "charcoal" ? 8 : fuel.name.endsWith("_planks") ? 1.5 : 1.5;
  const furnace = await bot.openFurnace(block);
  let got = 0;
  try {
    if (furnace.outputItem()) { got += furnace.outputItem().count; await furnace.takeOutput(); }
    await furnace.putFuel(fuel.type, null, Math.min(fuel.count, Math.ceil(n / per)));
    await furnace.putInput(bot.inventory.items().find((it) => it.name === input).type, null, n);
    const until = Date.now() + Math.min(n * 11000 + 5000, 5 * 60000);
    while (got < n && Date.now() < until && !task.cancelled) {
      await sleep(2000);
      const out = furnace.outputItem();
      if (out) { got += out.count; try { await furnace.takeOutput(); } catch { /* inventory full */ } }
    }
    // Leave nothing behind: take back unsmelted input.
    if (furnace.inputItem()) { try { await furnace.takeInput(); } catch { /* fine */ } }
  } finally {
    furnace.close();
    await sleep(300);
  }
  if (!got) return { ok: false, detail: `the furnace didn't produce ${output} (out of fuel?)` };
  return { ok: true, detail: `smelted ${got} ${output}${output === "iron_ingot" ? " (now gearup for iron tools and armour)" : ""}` };
}

// --- hide: shelter for the night ------------------------------------------------------------------------------
async function boxIn(bot) {
  // Wall in where we stand: blocks at feet and head height on all four sides, and one above the head.
  const blocks = () => bot.inventory.items().find((it) => ["dirt", "cobblestone", "cobbled_deepslate", "netherrack", "stone", "sand", "gravel"].includes(it.name) || it.name.endsWith("_planks"));
  const feet = bot.entity.position.floored();
  const spots = [];
  for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) for (const dy of [0, 1]) spots.push(feet.offset(dx, dy, dz));
  spots.push(feet.offset(0, 2, 0));
  let placed = 0;
  for (const s of spots) {
    const b = bot.blockAt(s);
    if (!b || b.boundingBox === "block") { placed++; continue; }
    const item = blocks();
    if (!item) break;
    const ref = [[0, -1, 0], [1, 0, 0], [-1, 0, 0], [0, 0, 1], [0, 0, -1], [0, 1, 0]].map(([x, y, z]) => ({ b: bot.blockAt(s.offset(x, y, z)), f: new Vec3(-x, -y, -z) }))
      .find((r) => r.b && r.b.boundingBox === "block" && !r.b.position.equals(feet) && !r.b.position.equals(feet.offset(0, 1, 0)));
    if (!ref) continue;
    try { await bot.equip(item, "hand"); await bot.placeBlock(ref.b, ref.f); placed++; } catch { /* next */ }
  }
  return placed >= 8;
}

export async function hideForNight(bot, a, task, ctx, digShelter) {
  if (!isNight(bot)) return { ok: false, detail: "it's day: no need to hide" };
  // 1. A finished building of our civilisation within 64 blocks: go inside.
  const me = bot.entity.position;
  const home = builder.all().filter((b) => b.status === "done" && (b.faction || null) === (ctx.faction || b.faction) && b.sx >= 4 && b.sz >= 4)
    .map((b) => ({ b, inside: new Vec3(b.x + Math.floor(b.sx / 2), b.y + 1, b.z + Math.floor(b.sz / 2)) }))
    .filter((h) => h.inside.distanceTo(me) < 64).sort((p, q) => p.inside.distanceTo(me) - q.inside.distanceTo(me))[0];
  if (home) {
    try {
      await walkTo(bot, new goals.GoalNear(home.inside.x, home.inside.y, home.inside.z, 1), 40000);
      while (isNight(bot) && !task.cancelled) await sleep(3000);
      return { ok: true, detail: `spent the night inside the ${home.b.blueprint}` };
    } catch { /* couldn't get in: dig or wall in instead */ }
  }
  // 2. Dig into the ground.
  const dug = await digShelter(bot, a, task, ctx);
  if (dug.ok || task.cancelled) return dug;
  // 3. Wall ourselves in with whatever blocks we carry.
  if (await boxIn(bot)) {
    while (isNight(bot) && !task.cancelled) await sleep(3000);
    return { ok: true, detail: "walled myself in for the night" };
  }
  return { ok: false, detail: `${dug.detail}; and I have too few blocks to wall myself in (carry 9 dirt or cobblestone)` };
}

export const PROGRESS_SKILLS = { gearup, mine, smelt };
