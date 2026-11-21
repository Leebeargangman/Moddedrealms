// Turns what a bot can see into a SHORT text summary for the prompt (a few hundred characters),
// instead of sending raw data. Example:
//   pos 102,64,-33 (40 m from village) · hp 18/20 · food 15/20 · day
//   inventory: oak_log 12, cobblestone 30, stone_pickaxe 1
//   nearby: players Brynn 5m · mobs zombie 12m · animals cow 9m · blocks oak_log 4m, iron_ore 9m, crafting_table 6m

import * as roster from "../village/roster.js";

const HOSTILE = new Set(["zombie", "husk", "drowned", "skeleton", "stray", "spider", "cave_spider", "creeper", "witch",
  "enderman", "slime", "phantom", "pillager", "vindicator", "zombie_villager", "silverfish", "bogged", "breeze"]);
const FOOD = ["cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken", "cooked_cod", "cooked_salmon", "bread",
  "baked_potato", "apple", "carrot", "beef", "porkchop", "mutton", "chicken", "potato", "sweet_berries", "melon_slice", "cookie"];
// Blocks worth mentioning when they are close.
const INTERESTING = ["oak_log", "birch_log", "spruce_log", "jungle_log", "acacia_log", "dark_oak_log", "cherry_log", "mangrove_log",
  "stone", "coal_ore", "iron_ore", "copper_ore", "gold_ore", "diamond_ore", "deepslate_iron_ore", "deepslate_coal_ore",
  "crafting_table", "furnace", "chest", "white_bed", "red_bed", "wheat", "water", "sand", "clay"];

/** True if this username is one of our AI villagers, including newcomers (anyone else is a real player). */
export const isVillager = (name) => roster.isVillagerName(String(name));

/** True if `pos` is inside the server's spawn protection (non-ops can't break or place blocks there). */
export function isProtected(bot, pos) {
  const r = bot.civSpawnProtection;
  const s = bot.civWorldSpawn;
  if (!r || !s) return false;
  return Math.abs(Math.floor(pos.x) - Math.floor(s.x)) <= r && Math.abs(Math.floor(pos.z) - Math.floor(s.z)) <= r;
}

export const isHostile = (e) => Boolean(e && (e.type === "hostile" || HOSTILE.has(e.name)));
export const FOOD_ITEMS = FOOD;
export const isNight = (bot) => {
  const t = bot.time?.timeOfDay ?? 0;
  return t >= 12542 && t <= 23460; // when monsters can spawn / beds can be used
};

const dist = (a, b) => Math.round(a.distanceTo(b));

/** Inventory as [name, count] pairs, biggest stacks first. */
export function inventoryCounts(bot) {
  const counts = new Map();
  for (const it of bot.inventory.items()) counts.set(it.name, (counts.get(it.name) || 0) + it.count);
  return [...counts.entries()].sort((a, b) => b[1] - a[1]);
}

export function countItem(bot, test) {
  return bot.inventory.items().filter((it) => (typeof test === "string" ? it.name === test : test(it.name))).reduce((n, it) => n + it.count, 0);
}

/** Look around once and return both the summary text and a few facts the code uses for fallbacks. */
export function snapshot(bot, village) {
  const me = bot.entity.position;
  const facts = {
    food: bot.food,
    health: bot.health,
    hasFood: bot.inventory.items().some((it) => FOOD.includes(it.name)),
    isNight: isNight(bot),
    logs: countItem(bot, (n) => n.endsWith("_log")),
    dangerNear: false,
    bedNear: false,
  };

  // Entities: players, hostile mobs and animals, nearest first.
  const villagers = [], players = [], mobs = [], animals = [];
  for (const e of Object.values(bot.entities)) {
    if (e === bot.entity || !e.position) continue;
    const d = dist(e.position, me);
    if (e.type === "player" && d <= 48) (isVillager(e.username) ? villagers : players).push([e.username, d]);
    // Hostiles: only those within 16 m and roughly on our level count (a creeper in a cave 15 blocks
    // below is no threat). Within 8 m = danger.
    else if (isHostile(e) && d <= 16 && Math.abs(e.position.y - me.y) <= 6) mobs.push([e.name, d]);
    else if (["cow", "pig", "sheep", "chicken", "rabbit"].includes(e.name) && d <= 24) animals.push([e.name, d]);
  }
  const danger = mobs.filter(([, d]) => d <= 8);
  const far = mobs.filter(([, d]) => d > 8);
  facts.dangerNear = danger.length > 0;

  // Blocks: one search for all interesting types, then keep the nearest of each.
  const ids = new Set(INTERESTING.map((n) => bot.registry.blocksByName[n]?.id).filter((id) => id !== undefined));
  const nearest = new Map();
  for (const pos of bot.findBlocks({ matching: (b) => ids.has(b.type), maxDistance: 16, count: 120 })) {
    const name = bot.blockAt(pos)?.name;
    if (!name) continue;
    const d = dist(pos, me);
    if (!nearest.has(name) || nearest.get(name) > d) nearest.set(name, d);
  }
  facts.bedNear = [...nearest.keys()].some((n) => n.endsWith("_bed"));

  const fmt = (list, n = 4) => list.sort((a, b) => a[1] - b[1]).slice(0, n).map(([name, d]) => `${name} ${d}m`).join(", ");
  const inv = inventoryCounts(bot);
  const spare = inv.filter(([n]) => !/_(pickaxe|axe|sword|shovel|hoe)$/.test(n) && !FOOD.includes(n) && !n.endsWith("_bed")).reduce((s, [, c]) => s + c, 0);
  const invText = inv.length ? inv.slice(0, 10).map(([n, c]) => `${n} ${c}`).join(", ") + (inv.length > 10 ? `, +${inv.length - 10} more kinds` : "") : "empty";
  const villageText = village ? ` (${dist(village, me)} m from village)` : "";
  const nearby = [
    villagers.length && `villagers ${fmt(villagers)}`,
    players.length && `players (not villagers) ${fmt(players)}`,
    danger.length && `DANGER ${fmt(danger)}`,
    far.length && `hostile but not close ${fmt(far)}`,
    animals.length && `animals ${fmt(animals, 3)}`,
    nearest.size && `blocks ${fmt([...nearest.entries()], 8)}`,
  ].filter(Boolean).join(" · ");

  // Signs close by (laws on the village notice board, messages from other villagers).
  const signs = [];
  try {
    for (const p of bot.findBlocks({ matching: (b) => b.name.endsWith("_sign"), maxDistance: 6, count: 3 })) {
      const [front] = bot.blockAt(p)?.getSignText?.() || [];
      const t = String(front || "").replace(/\s+/g, " ").trim();
      if (t) signs.push(`"${t.slice(0, 60)}"`);
    }
  } catch { /* sign text not loaded yet */ }

  const text = [
    `pos ${Math.round(me.x)},${Math.round(me.y)},${Math.round(me.z)}${villageText} · hp ${Math.round(bot.health)}/20 · food ${bot.food}/20 · ${facts.isNight ? "night" : "day"}`,
    `inventory: ${invText}${spare ? ` (${spare} spare materials)` : ""}`,
    `nearby: ${nearby || "nothing notable"}`,
    signs.length ? `signs here: ${signs.join(" · ")}` : "",
  ].filter(Boolean).join("\n");
  return { text, facts };
}
