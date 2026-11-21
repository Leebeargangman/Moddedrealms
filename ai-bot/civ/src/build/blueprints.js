// Blueprints: what the villagers can build. Each blueprint is a list of blocks relative to the corner
// of its plot: { name, size: {x, y, z}, blocks: [{ x, y, z, block }] } where y = 0 is the ground level
// (the floor the building stands on) and block is a block name or a material:
//   "planks" = any kind of planks, "log" = any log.
//
// Two come built in (hut, house). Any WorldEdit schematic in ai-bot/civ/schematics/*.schem (or
// the older .schematic) is loaded too, named after its file: make one in-game with WorldEdit
// (//pos1, //pos2, //copy, //schem save house1) and copy the file into that folder.

import fs from "node:fs";
import path from "node:path";
import { ROOT } from "../config.js";
import { log } from "../log.js";

const SCHEM_DIR = path.join(ROOT, "schematics");

/** A box of walls with a door gap at the front middle, and a flat roof. */
function hut({ w = 5, d = 5, h = 3, wall = "planks", roof = "planks" }) {
  const blocks = [];
  const door = Math.floor(w / 2);
  for (let y = 1; y <= h; y++) {
    for (let x = 0; x < w; x++) for (let z = 0; z < d; z++) {
      const edge = x === 0 || z === 0 || x === w - 1 || z === d - 1;
      if (!edge) continue;
      if (z === 0 && x === door && y <= 2) continue; // the doorway (2 high)
      blocks.push({ x, y, z, block: wall });
    }
  }
  for (let x = 0; x < w; x++) for (let z = 0; z < d; z++) blocks.push({ x, y: h + 1, z, block: roof });
  return { size: { x: w, y: h + 2, z: d }, blocks, door: { x: door, z: 0 } };
}

const BUILT_IN = {
  hut: { ...hut({}), description: "a small 5x5 wooden hut with a doorway (about 71 planks)" },
  house: { ...hut({ w: 7, d: 7, h: 4 }), description: "a 7x7 wooden house (about 143 planks)" },
};

let loaded = null;
/** All blueprints: built-in plus schematics/*.schem. Loaded once. */
export async function all() {
  if (loaded) return loaded;
  loaded = { ...BUILT_IN };
  if (fs.existsSync(SCHEM_DIR)) {
    const { Schematic } = await import("prismarine-schematic");
    for (const f of fs.readdirSync(SCHEM_DIR).filter((n) => /\.(schem|schematic)$/i.test(n))) {
      try {
        const schem = await Schematic.read(fs.readFileSync(path.join(SCHEM_DIR, f)), "1.21.11");
        const blocks = [];
        const start = schem.start();
        await schem.forEach((block, pos) => {
          if (["air", "cave_air", "void_air", "structure_void"].includes(block.name)) return;
          blocks.push({ x: pos.x - start.x, y: pos.y - start.y, z: pos.z - start.z, block: block.name });
        });
        // y = 0 of a schematic is its bottom layer: we treat that as the floor at ground level.
        const name = path.basename(f).replace(/\.(schem|schematic)$/i, "").toLowerCase();
        loaded[name] = { size: { ...schem.size }, blocks, description: `${blocks.length} blocks from ${f}` };
        log.info("build", `loaded schematic ${f}: ${schem.size.x}x${schem.size.y}x${schem.size.z}, ${blocks.length} blocks`);
      } catch (e) {
        log.warn("build", `couldn't read schematic ${f}: ${e.message}`);
      }
    }
  }
  return loaded;
}

/** Materials a blueprint needs: { material: count }. */
export function materials(bp) {
  const m = {};
  for (const b of bp.blocks) m[b.block] = (m[b.block] || 0) + 1;
  return m;
}

/** Short list for prompts, e.g. "hut (71 planks), house (143 planks)". */
export async function text() {
  const list = await all();
  return Object.entries(list).map(([n, bp]) => `${n} (${Object.entries(materials(bp)).map(([k, v]) => `${v} ${k}`).join(", ")})`).join(", ");
}
