// Blueprints: what the villagers can build. A blueprint is { size: {x, y, z}, blocks, description } where
// each block is { x, y, z, block, props?, opt? } relative to the plot corner:
//   - y = 0 is ground level (foundations and floors), the front is z = 0.
//   - block is a block name, or a generic wooden part: "planks", "log", "stairs", "slab", "door", "fence",
//     "torch" (any wood type will do; the builder crafts parts from the planks it carries).
//   - props gives direction where it matters: { facing: "north" | "south" | "east" | "west", type/half: "top" }.
//   - opt: optional (torches): skipped when the builder has none.
//
// The built-in designs only use what villagers can gather or craft in survival, and stay low enough to
// reach without scaffolding (about 6 blocks). Your own designs: put a WorldEdit .schem/.schematic or a
// Litematica .litematic in ai-bot/civ/schematics/, or upload a .litematic on the website's Bots page and
// tick it as a village blueprint. Each becomes a blueprint named after the file.

import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { ROOT } from "../config.js";
import { log } from "../log.js";

export const SCHEM_DIR = path.join(ROOT, "schematics");

// --- design helpers -------------------------------------------------------------------------------------
function design() {
  const blocks = new Map();
  const put = (x, y, z, block, props, opt) => blocks.set(`${x},${y},${z}`, { x, y, z, block, ...(props ? { props } : {}), ...(opt ? { opt: true } : {}) });
  const clear = (x, y, z) => blocks.delete(`${x},${y},${z}`);
  const list = () => [...blocks.values()];
  return { put, clear, list };
}

/** Walls with log corners, a door at the front and fence windows on the sides. */
function walls(d, w, len, h, { door = true, windows = true, wall = "planks", corner = "log" } = {}) {
  for (let y = 1; y <= h; y++) for (let x = 0; x < w; x++) for (let z = 0; z < len; z++) {
    const edgeX = x === 0 || x === w - 1, edgeZ = z === 0 || z === len - 1;
    if (!edgeX && !edgeZ) continue;
    d.put(x, y, z, edgeX && edgeZ ? corner : wall);
  }
  const mid = Math.floor(w / 2);
  if (door) { d.put(mid, 1, 0, "door", { facing: "south" }); d.clear(mid, 2, 0); } // the door's top half comes with it
  // Fence "glass" in the windows: optional, so a missing fence leaves an open window instead of stopping the build.
  if (windows) for (const z of len >= 7 ? [2, len - 3] : [Math.floor(len / 2)]) { d.put(0, 2, z, "fence", null, true); d.put(w - 1, 2, z, "fence", null, true); }
}

/** A gable roof for a 5-wide building: stairs on both slopes, planks under them, slabs on the ridge. */
function gableRoof5(d, len, base) {
  for (let z = 0; z < len; z++) {
    d.put(0, base, z, "stairs", { facing: "east" }); d.put(4, base, z, "stairs", { facing: "west" });
    for (const x of [1, 2, 3]) d.put(x, base, z, "planks");
    d.put(1, base + 1, z, "stairs", { facing: "east" }); d.put(3, base + 1, z, "stairs", { facing: "west" });
    d.put(2, base + 1, z, "planks");
    d.put(2, base + 2, z, "slab");
  }
}

function floor(d, w, len, block, inner = true) {
  for (let x = 0; x < w; x++) for (let z = 0; z < len; z++) {
    const edge = x === 0 || z === 0 || x === w - 1 || z === len - 1;
    d.put(x, 0, z, edge ? "cobblestone" : inner ? block : "cobblestone");
  }
}

// --- the built-in designs ----------------------------------------------------------------------------------
function hut() {
  const d = design();
  walls(d, 5, 5, 3);
  gableRoof5(d, 5, 4);
  d.put(2, 2, 3, "torch", { facing: "north" }, true);
  return { size: { x: 5, y: 7, z: 5 }, blocks: d.list(), description: "a small wooden hut with a pitched roof, a door and windows" };
}

function house() {
  const d = design();
  floor(d, 5, 7, "planks");
  walls(d, 5, 7, 3);
  gableRoof5(d, 7, 4);
  d.put(2, 2, 5, "torch", { facing: "north" }, true);
  d.put(1, 2, 1, "torch", { facing: "east" }, true);
  return { size: { x: 5, y: 7, z: 7 }, blocks: d.list(), description: "a family house: stone foundation, plank floor, pitched roof" };
}

function storehouse() {
  const d = design();
  floor(d, 5, 5, "planks");
  walls(d, 5, 5, 3, { windows: false });
  gableRoof5(d, 5, 4);
  d.put(1, 1, 3, "chest"); d.put(3, 1, 3, "chest");
  d.put(2, 2, 3, "torch", { facing: "north" }, true);
  return { size: { x: 5, y: 7, z: 5 }, blocks: d.list(), description: "a storehouse with two chests (they become village chests)" };
}

function well() {
  const d = design();
  for (let x = 0; x < 4; x++) for (let z = 0; z < 4; z++) {
    const ring = x === 0 || z === 0 || x === 3 || z === 3;
    if (ring) { d.put(x, 0, z, "cobblestone"); d.put(x, 1, z, "cobblestone"); }
  }
  for (const [x, z] of [[0, 0], [3, 0], [0, 3], [3, 3]]) { d.put(x, 2, z, "fence"); d.put(x, 3, z, "fence"); }
  for (let x = 0; x < 4; x++) for (let z = 0; z < 4; z++) d.put(x, 4, z, "slab");
  return { size: { x: 4, y: 5, z: 4 }, blocks: d.list(), description: "a stone well with a little roof (the village meeting point)" };
}

function watchtower() {
  const d = design();
  // Four log posts, a plank platform at the top, a fence railing and a ladder up the front post.
  for (let y = 1; y <= 4; y++) for (const [x, z] of [[0, 1], [4, 1], [0, 5], [4, 5]]) d.put(x, y, z, "log");
  for (let x = 0; x < 5; x++) for (let z = 1; z < 6; z++) d.put(x, 5, z, "planks");
  for (let x = 0; x < 5; x++) for (let z = 1; z < 6; z++) {
    if (x === 0 || z === 1 || x === 4 || z === 5) if (!(x === 0 && z === 1)) d.put(x, 6, z, "fence");
  }
  for (let y = 1; y <= 5; y++) d.put(0, y, 0, "ladder", { facing: "north" });
  return { size: { x: 5, y: 7, z: 6 }, blocks: d.list(), description: "a lookout tower for guards, with a ladder and railing" };
}

function wallSection() {
  const d = design();
  for (let x = 0; x < 9; x++) {
    for (let y = 0; y <= 2; y++) d.put(x, y, 0, "cobblestone");
    if (x % 2 === 0) d.put(x, 3, 0, "cobblestone");
  }
  d.put(4, 2, 0, "torch", { facing: "south" }, true);
  return { size: { x: 9, y: 4, z: 1 }, blocks: d.list(), description: "a 9-block stretch of stone town wall with battlements" };
}

function pen() {
  const d = design();
  for (let x = 0; x < 7; x++) for (let z = 0; z < 7; z++) {
    const edge = x === 0 || z === 0 || x === 6 || z === 6;
    if (edge && !(z === 0 && x === 3)) d.put(x, 1, z, "fence"); // a gap at the front to lead animals in
  }
  return { size: { x: 7, y: 2, z: 7 }, blocks: d.list(), description: "a fenced animal pen (lead cows and sheep in for food)" };
}

function townhall() {
  const d = design();
  floor(d, 9, 9, "planks");
  for (let y = 1; y <= 4; y++) for (let x = 0; x < 9; x++) for (let z = 0; z < 9; z++) {
    const edgeX = x === 0 || x === 8, edgeZ = z === 0 || z === 8;
    if (!edgeX && !edgeZ) continue;
    d.put(x, y, z, (edgeX && edgeZ) || (x === 4 && edgeZ) || (z === 4 && edgeX) ? "log" : "cobblestone");
  }
  d.put(3, 1, 0, "door", { facing: "south" }); d.clear(3, 2, 0);
  d.put(5, 1, 0, "door", { facing: "south" }); d.clear(5, 2, 0);
  d.clear(4, 1, 0); d.clear(4, 2, 0); d.put(4, 1, 0, "log"); d.put(4, 2, 0, "log");
  for (const z of [2, 6]) { d.put(0, 2, z, "fence"); d.put(8, 2, z, "fence"); d.put(0, 3, z, "fence"); d.put(8, 3, z, "fence"); }
  for (const x of [2, 6]) { d.put(x, 2, 8, "fence"); d.put(x, 3, 8, "fence"); }
  for (let x = 0; x < 9; x++) for (let z = 0; z < 9; z++) d.put(x, 5, z, "planks");
  for (let x = 0; x < 9; x++) for (let z = 0; z < 9; z++) if (x === 0 || z === 0 || x === 8 || z === 8) d.put(x, 6, z, "slab");
  d.put(4, 3, 7, "torch", { facing: "north" }, true); d.put(1, 3, 4, "torch", { facing: "east" }, true); d.put(7, 3, 4, "torch", { facing: "west" }, true);
  return { size: { x: 9, y: 7, z: 9 }, blocks: d.list(), description: "the town hall: stone walls, log pillars, double doors, a flat roof" };
}

const BUILT_IN = { hut, house, storehouse, well, watchtower, wall: wallSection, pen, townhall };

// --- loading schematics -------------------------------------------------------------------------------------
// Blocks we never place: air, fluids (no buckets yet), and the second half of two-block things (doors and
// beds place their other half themselves).
function keepBlock(name, props) {
  if (["air", "cave_air", "void_air", "structure_void", "water", "lava", "bubble_column", "fire", "piston_head", "moving_piston"].includes(name)) return false;
  if (props?.half === "upper" && (name.endsWith("_door") || name.includes("tall") || name.includes("sunflower") || name.includes("lilac") || name.includes("peony") || name.includes("rose_bush"))) return false;
  if (props?.part === "head" && name.endsWith("_bed")) return false;
  return true;
}
const keepProps = (p) => {
  if (!p) return undefined;
  const out = {};
  for (const k of ["facing", "half", "type"]) if (p[k] !== undefined) out[k] = String(p[k]);
  return Object.keys(out).length ? out : undefined;
};

function normalise(blocks) {
  const min = { x: Infinity, y: Infinity, z: Infinity }, max = { x: -Infinity, y: -Infinity, z: -Infinity };
  for (const b of blocks) for (const k of ["x", "y", "z"]) { min[k] = Math.min(min[k], b[k]); max[k] = Math.max(max[k], b[k]); }
  return {
    size: { x: max.x - min.x + 1, y: max.y - min.y + 1, z: max.z - min.z + 1 },
    blocks: blocks.map((b) => ({ ...b, x: b.x - min.x, y: b.y - min.y, z: b.z - min.z })),
  };
}

/** Litematica .litematic: gzipped NBT with regions of tightly packed palette indices. */
async function readLitematic(buffer) {
  const nbt = (await import("prismarine-nbt")).default;
  const { parsed } = await nbt.parse(zlib.gunzipSync(buffer));
  const root = nbt.simplify(parsed);
  if (!root.Regions) throw new Error("not a Litematica schematic");
  const blocks = [];
  for (const region of Object.values(root.Regions)) {
    const s = region.Size, p = region.Position;
    const sx = Math.abs(s.x), sy = Math.abs(s.y), sz = Math.abs(s.z);
    const origin = { x: p.x + (s.x < 0 ? s.x + 1 : 0), y: p.y + (s.y < 0 ? s.y + 1 : 0), z: p.z + (s.z < 0 ? s.z + 1 : 0) };
    const palette = region.BlockStatePalette ?? [];
    const bits = Math.max(2, Math.ceil(Math.log2(Math.max(palette.length, 1))));
    const longs = (region.BlockStates ?? []).map((l) => (BigInt.asUintN(32, BigInt(l[0])) << 32n) | BigInt.asUintN(32, BigInt(l[1])));
    const mask = (1n << BigInt(bits)) - 1n;
    const get = (i) => {
      const start = BigInt(i) * BigInt(bits), li = Number(start / 64n), off = start % 64n;
      let v = longs[li] >> off;
      if (off + BigInt(bits) > 64n) v |= longs[li + 1] << (64n - off);
      return Number(v & mask);
    };
    for (let y = 0; y < sy; y++) for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) {
      const entry = palette[get((y * sz + z) * sx + x)];
      if (!entry) continue;
      const name = String(entry.Name).replace(/^minecraft:/, "");
      if (!keepBlock(name, entry.Properties)) continue;
      blocks.push({ x: origin.x + x, y: origin.y + y, z: origin.z + z, block: name, props: keepProps(entry.Properties) });
    }
  }
  return normalise(blocks);
}

/** WorldEdit .schem / MCEdit .schematic. */
async function readSchem(buffer) {
  const { Schematic } = await import("prismarine-schematic");
  const schem = await Schematic.read(buffer, "1.21.11");
  const blocks = [];
  await schem.forEach((block, pos) => {
    const props = block.getProperties?.();
    if (!keepBlock(block.name, props)) return;
    blocks.push({ x: pos.x, y: pos.y, z: pos.z, block: block.name, props: keepProps(props) });
  });
  return normalise(blocks);
}

let loaded = null;
/** All blueprints: built-in plus schematics/*. Call reload() after adding a file. */
export async function all() {
  if (loaded) return loaded;
  loaded = {};
  for (const [name, make] of Object.entries(BUILT_IN)) loaded[name] = make();
  if (fs.existsSync(SCHEM_DIR)) {
    for (const f of fs.readdirSync(SCHEM_DIR).filter((n) => /\.(schem|schematic|litematic)$/i.test(n))) {
      try {
        const buf = fs.readFileSync(path.join(SCHEM_DIR, f));
        const bp = /\.litematic$/i.test(f) ? await readLitematic(buf) : await readSchem(buf);
        const name = path.basename(f).replace(/\.(schem|schematic|litematic)$/i, "").toLowerCase().replace(/[^a-z0-9_-]/g, "_").slice(0, 30);
        if (!bp.blocks.length) continue;
        const tooTall = bp.size.y > 7 ? " (taller than villagers can reach: the top may stay unfinished)" : "";
        loaded[name] = { ...bp, description: `${bp.blocks.length} blocks from ${f}${tooTall}` };
        log.info("build", `loaded ${f}: ${bp.size.x}x${bp.size.y}x${bp.size.z}, ${bp.blocks.length} blocks`);
      } catch (e) {
        log.warn("build", `couldn't read ${f}: ${e.message}`);
      }
    }
  }
  return loaded;
}
export function reload() { loaded = null; return all(); }

/** Materials a blueprint needs: { material: count }. With { required: true } optional blocks are left out. */
export function materials(bp, { required = false } = {}) {
  const m = {};
  for (const b of bp.blocks) if (!required || !b.opt) m[b.block] = (m[b.block] || 0) + 1;
  return m;
}

// Roughly how many logs a wooden part costs, to describe blueprints in one number.
const LOGS_PER = { planks: 0.25, log: 1, stairs: 0.375, slab: 0.125, door: 0.5, fence: 0.45, ladder: 0.3, chest: 2 };
/** Short list for prompts, e.g. "hut (~30 logs), house (~38 logs, 24 cobblestone)". */
export async function text() {
  const list = await all();
  return Object.entries(list).map(([n, bp]) => {
    const m = materials(bp, { required: true });
    let logs = 0;
    const other = [];
    for (const [k, v] of Object.entries(m)) {
      if (LOGS_PER[k] !== undefined) logs += v * LOGS_PER[k];
      else other.push(`${v} ${k}`);
    }
    return `${n} (${[logs ? `~${Math.ceil(logs)} logs` : "", ...other.slice(0, 3)].filter(Boolean).join(", ")})`;
  }).join(", ");
}
