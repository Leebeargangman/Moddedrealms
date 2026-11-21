// Plain-code skills for village life (Phase 3): storing things in the village chests, giving items to
// a neighbour, hiding in an emergency shelter at night, and the imposters' secret attack.
// Same shape as skills.js: async (bot, action, task, ctx) => { ok, detail }. No AI calls in here.

import pf from "mineflayer-pathfinder";
import { Vec3 } from "vec3";
import * as chests from "../village/chests.js";
import { FOOD_ITEMS, isNight, isProtected } from "./state.js";
import { walkTo, sleep, equipWeapon, SKILLS } from "./skills.js";

const { goals } = pf;

const TOOL = /_(pickaxe|axe|sword|shovel|hoe)$/;
/** Things a villager keeps when it deposits "all": tools, weapons, a bit of food, its bed. */
const keepWhenDepositing = (name) => TOOL.test(name) || FOOD_ITEMS.includes(name) || name.endsWith("_bed") || name === "torch";

/** Item test for a target like "oak_log", "wood"/"logs" (any log), "planks", or "all". */
function itemTest(target) {
  const t = (target || "all").toLowerCase();
  if (t === "all" || t === "everything") return (n) => !keepWhenDepositing(n);
  if (["wood", "log", "logs"].includes(t)) return (n) => n.endsWith("_log");
  if (t === "planks") return (n) => n.endsWith("_planks");
  if (t === "stone") return (n) => n === "cobblestone" || n === "stone";
  if (["food", "meat"].includes(t)) return (n) => FOOD_ITEMS.includes(n);
  return (n) => n === t;
}

// --- finding or making a village chest -----------------------------------------------------------
async function placeNewChest(bot, ctx) {
  let item = bot.inventory.items().find((it) => it.name === "chest");
  if (!item) {
    // The normal craft skill makes the planks, walks to (or places) a crafting table, then crafts.
    const made = await SKILLS.craft(bot, { action: "craft", target: "chest", amount: 1 }, { cancelled: false }, ctx);
    for (let i = 0; i < 10 && !item; i++) { await sleep(300); item = bot.inventory.items().find((it) => it.name === "chest"); } // inventory updates a moment later
    if (!item) return { error: `no village chest yet, and I couldn't make one (${made.detail})` };
  }
  // A free spot 3-10 blocks from the village centre, on solid ground, next to existing chests if any.
  const c = ctx.village.floored();
  const spots = [];
  for (let r = 3; r <= 10; r++) for (let dx = -r; dx <= r; dx++) for (let dz = -r; dz <= r; dz++) {
    if (Math.max(Math.abs(dx), Math.abs(dz)) !== r) continue;
    for (let dy = -3; dy <= 3; dy++) spots.push(c.offset(dx, dy, dz));
  }
  const ok = (p) => {
    const here = bot.blockAt(p), below = bot.blockAt(p.offset(0, -1, 0)), above = bot.blockAt(p.offset(0, 1, 0));
    // Grass and flowers count as free: placing the chest replaces them.
    const free = (b) => b && b.boundingBox === "empty" && !["water", "lava"].includes(b.name);
    return free(here) && below?.boundingBox === "block" && free(above) && !isProtected(bot, p);
  };
  const spot = spots.find(ok);
  if (!spot) return { error: "found no flat free spot for a chest near the village centre" };
  await walkTo(bot, new goals.GoalNear(spot.x, spot.y, spot.z, 3), 60000);
  await bot.equip(item, "hand");
  await bot.placeBlock(bot.blockAt(spot.offset(0, -1, 0)), new Vec3(0, 1, 0));
  const id = chests.add(spot);
  return { chest: { id, x: spot.x, y: spot.y, z: spot.z, contents: {} } };
}

/** The village chests that still exist (forgets chests that were broken). */
function liveChests(bot) {
  const out = [];
  for (const ch of chests.all()) {
    const b = bot.blockAt(new Vec3(ch.x, ch.y, ch.z));
    if (b && b.name !== "chest" && b.name !== "trapped_chest") { chests.remove(ch.id); continue; } // broken
    out.push(ch);
  }
  return out;
}

async function openChest(bot, ch) {
  await walkTo(bot, new goals.GoalNear(ch.x, ch.y, ch.z, 2), 90000);
  const block = bot.blockAt(new Vec3(ch.x, ch.y, ch.z));
  if (!block || block.name !== "chest") throw new Error("the chest is gone");
  return bot.openContainer(block);
}

// --- deposit / withdraw ---------------------------------------------------------------------------
async function deposit(bot, a, task, ctx) {
  const test = itemTest(a.target);
  const items = bot.inventory.items().filter((it) => test(it.name));
  if (!items.length) {
    return { ok: false, detail: (a.target || "all") === "all" ? "nothing spare to put away (tools, food and beds are kept): go gather, build or help instead" : `I carry no ${a.target} to put away` };
  }
  let list = liveChests(bot);
  if (!list.length) {
    const made = await placeNewChest(bot, ctx);
    if (made.error) return { ok: false, detail: made.error };
    list = [made.chest];
  }
  let stored = 0;
  for (const ch of list) {
    if (task.cancelled) break;
    let box;
    try { box = await openChest(bot, ch); } catch (e) { continue; }
    try {
      for (const it of bot.inventory.items().filter((i) => test(i.name))) {
        try { await box.deposit(it.type, null, it.count); stored += it.count; } catch { break; } // chest full
      }
      chests.saveContents(ch.id, box.containerItems());
    } finally { box.close(); await sleep(400); }
    if (!bot.inventory.items().some((i) => test(i.name))) break;
  }
  if (!stored) {
    const made = await placeNewChest(bot, ctx); // all full: add a chest
    return { ok: false, detail: made.error || "the chests were full, so I placed a new one: deposit again" };
  }
  return { ok: true, detail: `put ${stored} items in the village chests` };
}

async function withdraw(bot, a, task) {
  if (!a.target) return { ok: false, detail: "withdraw what?" };
  const test = itemTest(a.target);
  const want = a.amount ?? 16;
  const list = liveChests(bot).filter((ch) => Object.keys(ch.contents).some(test));
  if (!list.length) {
    const t = Object.entries(chests.totals()).sort((x, y) => y[1] - x[1]).slice(0, 8).map(([n, c]) => `${n} ${c}`).join(", ");
    const food = a.target && /food|bread|beef|pork|chicken|mutton|apple|potato|carrot/.test(a.target) ? " Nobody has stored food yet: hunt animals (attack cow/pig/chicken/sheep) or pick sweet_berries." : "";
    return { ok: false, detail: `the village chests have no ${a.target} (they have: ${t || "nothing"}).${food}` };
  }
  let got = 0;
  for (const ch of list) {
    if (task.cancelled || got >= want) break;
    let box;
    try { box = await openChest(bot, ch); } catch { continue; }
    try {
      for (const it of box.containerItems().filter((i) => test(i.name))) {
        const n = Math.min(it.count, want - got);
        if (n <= 0) break;
        try { await box.withdraw(it.type, null, n); got += n; } catch { break; } // my inventory is full
      }
      chests.saveContents(ch.id, box.containerItems());
    } finally { box.close(); await sleep(400); }
  }
  return got ? { ok: true, detail: `took ${got} ${a.target} from the village chests` } : { ok: false, detail: `couldn't take any ${a.target}` };
}

// --- give -------------------------------------------------------------------------------------------
/** target = "Brynn stone_pickaxe", amount = how many. Walks to them and throws the items at their feet. */
async function give(bot, a) {
  const [who, ...rest] = String(a.target || "").split(/\s+/);
  const itemName = rest.join("_");
  const player = Object.values(bot.players).find((p) => p.username.toLowerCase() === who.toLowerCase())?.entity;
  if (!player) return { ok: false, detail: `${who} is not close enough to see` };
  const test = itemTest(itemName || "nothing");
  const item = bot.inventory.items().find((it) => test(it.name));
  if (!item) return { ok: false, detail: `I don't have ${itemName || "that"} (I can only give what I carry)` };
  await walkTo(bot, new goals.GoalNear(player.position.x, player.position.y, player.position.z, 2), 60000);
  await bot.lookAt(player.position.offset(0, 1.2, 0), true);
  const n = Math.min(a.amount ?? 1, item.count);
  await bot.toss(item.type, null, n);
  return { ok: true, detail: `gave ${n} ${item.name} to ${player.username}` };
}

// --- hide: emergency shelter for the night ------------------------------------------------------------
/** Dig 3 blocks straight down, cover the hole, wait for morning. Costs nothing but a few seconds. */
async function hide(bot, _a, task) {
  if (!isNight(bot)) return { ok: false, detail: "it's day: no need to hide" };
  // A column we can dig 3 down into without hitting a cave, water or lava: here, or a few blocks away.
  const good = (p) => {
    for (let dy = 1; dy <= 4; dy++) {
      const b = bot.blockAt(p.offset(0, -dy, 0));
      if (!b || ["air", "cave_air", "water", "lava", "bedrock"].includes(b.name) || b.name.includes("leaves") || (dy <= 3 && !bot.canDigBlock(b))) return false;
    }
    return bot.blockAt(p)?.boundingBox === "empty" && bot.blockAt(p.offset(0, 1, 0))?.boundingBox === "empty";
  };
  let start = bot.entity.position.floored();
  if (!good(start)) {
    const near = [];
    for (let dx = -4; dx <= 4; dx++) for (let dz = -4; dz <= 4; dz++) for (let dy = -2; dy <= 2; dy++) near.push(start.offset(dx, dy, dz));
    const spot = near.filter(good).sort((p, q) => p.distanceTo(start) - q.distanceTo(start))[0];
    if (!spot) return { ok: false, detail: "no ground to dig a shelter in near here: move somewhere else" };
    try { await walkTo(bot, new goals.GoalBlock(spot.x, spot.y, spot.z), 20000); } catch { return { ok: false, detail: "couldn't reach a spot to dig a shelter" }; }
    start = bot.entity.position.floored();
    if (!good(start)) return { ok: false, detail: "couldn't dig a shelter here: try again" };
  }
  bot.pathfinder.setGoal(null);
  for (let dy = 1; dy <= 3 && !task.cancelled; dy++) {
    const b = bot.blockAt(bot.entity.position.floored().offset(0, -1, 0));
    await bot.dig(b, true);
    await sleep(600); // fall into the hole
  }
  // Cover the top: the block at the old ground level, placed against the hole's wall.
  const cover = bot.inventory.items().find((it) => ["dirt", "cobblestone", "cobbled_deepslate", "stone", "gravel"].includes(it.name) || it.name.endsWith("_planks"));
  const top = start.offset(0, -1, 0);
  if (cover && bot.blockAt(top)?.name === "air") {
    const wall = [[1, 0], [-1, 0], [0, 1], [0, -1]].map(([dx, dz]) => bot.blockAt(top.offset(dx, 0, dz))).find((b) => b && b.boundingBox === "block");
    if (wall) {
      try {
        await bot.equip(cover, "hand");
        await bot.placeBlock(wall, top.minus(wall.position));
      } catch { /* an open roof still helps a bit */ }
    }
  }
  while (isNight(bot) && !task.cancelled) await sleep(3000);
  if (task.cancelled) return { ok: true, detail: "left the shelter" };
  // Morning: dig the cover and climb out (the pathfinder digs steps if it has to).
  try { await walkTo(bot, new goals.GoalNearXZ(start.x + 3, start.z, 1), 30000); } catch { /* the next task will get us out */ }
  return { ok: true, detail: "spent the night safely in a hole shelter" };
}

// --- the imposters' attack ------------------------------------------------------------------------------
/**
 * Attack a villager until they die (or 40 s pass, or we are hurt). The rules about WHEN this is
 * allowed (kill window, no witnesses) are checked before, in civbot.js with crime.canKill().
 */
export async function murder(bot, a, task) {
  const victim = Object.values(bot.players).find((p) => p.username.toLowerCase() === String(a.target).toLowerCase())?.entity;
  if (!victim) return { ok: false, detail: `${a.target} is not close enough` };
  await equipWeapon(bot);
  bot.pathfinder.setGoal(new goals.GoalFollow(victim, 1.2), true);
  const until = Date.now() + 40000;
  while (Date.now() < until && !task.cancelled && bot.players[victim.username]?.entity && bot.health > 6) {
    const v = bot.players[victim.username].entity;
    if (v.position.distanceTo(bot.entity.position) < 3.2) {
      await bot.lookAt(v.position.offset(0, 1.4, 0), true);
      bot.attack(v);
    }
    await sleep(550);
  }
  bot.pathfinder.setGoal(null);
  if (bot.health <= 6) return { ok: false, detail: `${a.target} fought back and I'm badly hurt` };
  const still = bot.players[a.target]?.entity;
  return still ? { ok: false, detail: `${a.target} got away (they know it was you!)` } : { ok: true, detail: `${a.target} is dead` };
}

export const VILLAGE_SKILLS = { deposit, withdraw, give, hide };

// --- signs, books, reading -------------------------------------------------------------------------
/** Wrap text into sign lines: up to 4 lines of 15 characters (what fits visibly on a sign). */
function signLines(text) {
  const words = String(text).replace(/\s+/g, " ").trim().split(" ");
  const lines = [""];
  for (const w of words) {
    const cur = lines[lines.length - 1];
    if ((cur + " " + w).trim().length <= 15) lines[lines.length - 1] = (cur + " " + w).trim();
    else lines.push(w.slice(0, 15));
  }
  return lines;
}

async function getSigns(bot, n, ctx) {
  const have = () => bot.inventory.items().filter((it) => it.name.endsWith("_sign") && !it.name.includes("hanging")).reduce((s, it) => s + it.count, 0);
  if (have() >= n) return true;
  // 6 planks + 1 stick = 3 signs (needs a crafting table: the craft skill handles that and the planks).
  await SKILLS.craft(bot, { action: "craft", target: "oak_sign", amount: 3 * Math.ceil((n - have()) / 3) }, { cancelled: false }, ctx);
  for (let i = 0; i < 10 && have() < n; i++) await sleep(300);
  return have() >= n;
}

/** Find free ground spots for signs: here, or the village notice board (near the centre). */
function signSpots(bot, around, count, radius) {
  const free = (b) => b && b.boundingBox === "empty" && !["water", "lava"].includes(b.name);
  const c = around.floored();
  const spots = [];
  for (let r = radius[0]; r <= radius[1] && spots.length < count; r++) {
    for (let dx = -r; dx <= r && spots.length < count; dx++) for (let dz = -r; dz <= r && spots.length < count; dz++) {
      if (Math.max(Math.abs(dx), Math.abs(dz)) !== r) continue;
      for (let dy = -2; dy <= 2; dy++) {
        const p = c.offset(dx, dy, dz);
        if (free(bot.blockAt(p)) && free(bot.blockAt(p.offset(0, 1, 0))) && bot.blockAt(p.offset(0, -1, 0))?.boundingBox === "block" &&
            !spots.some((s) => s.distanceTo(p) < 1.5 && s.y === p.y && false)) { spots.push(p); break; }
      }
    }
  }
  return spots;
}

/**
 * "sign": put text on signs. target = "here" (default) or "village" (the notice board near the village
 * centre, where laws belong). text = what to write (up to 120 characters: 1 or 2 signs).
 */
async function placeSign(bot, a, task, ctx) {
  const text = String(a.text || a.say || "").trim();
  if (text.length < 2) return { ok: false, detail: "what should the sign say? (put it in \"text\")" };
  const lines = signLines(text.slice(0, 120));
  const groups = [];
  for (let i = 0; i < lines.length; i += 4) groups.push(lines.slice(i, i + 4));
  if (!(await getSigns(bot, groups.length, ctx))) return { ok: false, detail: "I have no sign and couldn't make one (a sign needs 6 planks and a stick, or 2 logs)" };
  const atVillage = String(a.target || "").includes("village");
  const spots = signSpots(bot, atVillage ? ctx.village : bot.entity.position, groups.length, atVillage ? [4, 9] : [1, 3]);
  if (spots.length < groups.length) return { ok: false, detail: "found no free ground for a sign here" };
  const placed = [];
  for (let i = 0; i < groups.length && !task.cancelled; i++) {
    const p = spots[i];
    await walkTo(bot, new goals.GoalNear(p.x, p.y, p.z, 2), 60000);
    const sign = bot.inventory.items().find((it) => it.name.endsWith("_sign") && !it.name.includes("hanging"));
    if (!sign) break;
    await bot.equip(sign, "hand");
    await bot.lookAt(p.offset(0.5, 0.5, 0.5), true);
    try { await bot.placeBlock(bot.blockAt(p.offset(0, -1, 0)), new Vec3(0, 1, 0)); } catch { /* checked below */ }
    await sleep(500);
    const b = bot.blockAt(p);
    if (!b || !b.name.endsWith("_sign")) continue;
    bot.updateSign(b, groups[i].join("\n"));
    placed.push(`${p.x},${p.z}`);
    await sleep(300);
  }
  if (!placed.length) return { ok: false, detail: "couldn't place the sign" };
  return { ok: true, detail: `put up ${placed.length} sign(s) at ${placed.join(" and ")}${atVillage ? " (village notice board)" : ""}: "${text.slice(0, 60)}"` };
}

/** "write": write a book. target = the title, text = the content. Needs a book and quill (writable_book). */
async function writeBook(bot, a) {
  const book = bot.inventory.items().find((it) => it.name === "writable_book");
  if (!book) {
    return { ok: false, detail: "I need a book and quill (writable_book): craft it from a book + ink_sac + feather; a book is 3 paper (from sugar_cane) + leather (from cows)" };
  }
  const text = String(a.text || "").trim();
  if (text.length < 5) return { ok: false, detail: "what should the book say? (put it in \"text\")" };
  const pages = [];
  for (let i = 0; i < text.length && pages.length < 10; i += 240) pages.push(text.slice(i, i + 240));
  const title = String(a.target || "Village records").replace(/[^\w ,.'!?-]/g, "").slice(0, 30) || "Village records";
  try {
    await bot.signBook(book.slot, pages, bot.username, title);
  } catch (e) {
    return { ok: false, detail: `couldn't write the book: ${e.message}` };
  }
  return { ok: true, detail: `wrote and signed the book "${title}" (${pages.length} page${pages.length > 1 ? "s" : ""}); deposit it so others can read it` };
}

/** Text of the signs within `radius` blocks (for the state and for "read"). */
export function nearbySigns(bot, radius = 6, max = 3) {
  const out = [];
  try {
    for (const p of bot.findBlocks({ matching: (b) => b.name.endsWith("_sign"), maxDistance: radius, count: max })) {
      const b = bot.blockAt(p);
      const [front] = b?.getSignText?.() || [];
      const t = String(front || "").replace(/\s+/g, " ").trim();
      if (t) out.push({ x: p.x, z: p.z, text: t });
    }
  } catch { /* no sign data yet */ }
  return out;
}

/** Text of a written book's pages. 1.21: componentMap "written_book_content" = { rawTitle, author, pages: [{ content }] }. */
function bookText(item) {
  const plain = (v) => (v == null ? "" : typeof v === "string" ? v : v.value ?? v.raw ?? v.text ?? (typeof v.content !== "undefined" ? plain(v.content) : ""));
  const c = item.componentMap?.get?.("written_book_content")?.data;
  if (c) return { title: plain(c.rawTitle ?? c.title) || "untitled", author: plain(c.author), text: (c.pages || []).map((p) => plain(p.content ?? p)).join(" ").replace(/\s+/g, " ").trim() };
  const n = item.nbt?.value; // older versions
  return { title: n?.title?.value || "untitled", author: n?.author?.value || "", text: (n?.pages?.value?.value || []).map(plain).join(" ").trim() };
}

/** "read": read the signs within 16 blocks and the books you carry. */
async function read(bot) {
  const signs = nearbySigns(bot, 16, 6);
  const books = bot.inventory.items().filter((it) => it.name === "written_book").map(bookText);
  if (!signs.length && !books.length) return { ok: false, detail: "there are no signs near me and I carry no written books (books may be in the village chests: withdraw written_book)" };
  const parts = [
    ...signs.map((s) => `sign at ${s.x},${s.z}: "${s.text.slice(0, 80)}"`),
    ...books.map((b) => `book "${b.title}"${b.author ? ` by ${b.author}` : ""}: "${b.text.slice(0, 200)}"`),
  ];
  return { ok: true, detail: `I read: ${parts.join(" | ")}` };
}

Object.assign(VILLAGE_SKILLS, { sign: placeSign, write: writeBook, read });
