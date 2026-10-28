// Reads a .litematic file (gzipped NBT) and works out which items it takes to build.
import zlib from 'node:zlib'
import nbt from 'prismarine-nbt'

const SKIP = new Set(['air', 'cave_air', 'void_air', 'piston_head', 'moving_piston', 'fire', 'soul_fire', 'nether_portal', 'end_portal', 'end_gateway', 'bubble_column'])
const ITEM_FOR = {
  wall_torch: 'torch', soul_wall_torch: 'soul_torch', redstone_wall_torch: 'redstone_torch', redstone_wire: 'redstone',
  tripwire: 'string', water: 'water_bucket', lava: 'lava_bucket', powder_snow: 'powder_snow_bucket',
  potatoes: 'potato', carrots: 'carrot', beetroots: 'beetroot_seeds', wheat: 'wheat_seeds', melon_stem: 'melon_seeds',
  pumpkin_stem: 'pumpkin_seeds', attached_melon_stem: 'melon_seeds', attached_pumpkin_stem: 'pumpkin_seeds',
  cocoa: 'cocoa_beans', sweet_berry_bush: 'sweet_berries', bamboo_sapling: 'bamboo', kelp_plant: 'kelp',
  cave_vines: 'glow_berries', cave_vines_plant: 'glow_berries', twisting_vines_plant: 'twisting_vines',
  weeping_vines_plant: 'weeping_vines', big_dripleaf_stem: 'big_dripleaf', tall_seagrass: 'seagrass', farmland: 'dirt',
  dirt_path: 'dirt', frosted_ice: 'ice'
}

// Litematica packs palette indices tightly; an entry may straddle two longs.
function bitArray (longs, bits) {
  const mask = (1n << BigInt(bits)) - 1n
  return i => {
    const start = BigInt(i) * BigInt(bits)
    const startIdx = Number(start >> 6n)
    const endIdx = Number((start + BigInt(bits) - 1n) >> 6n)
    const off = start & 63n
    if (startIdx === endIdx) return Number((longs[startIdx] >> off) & mask)
    return Number(((longs[startIdx] >> off) | (longs[endIdx] << (64n - off))) & mask)
  }
}

const toLong = v => {
  if (typeof v === 'bigint') return BigInt.asUintN(64, v)
  const [hi, lo] = v
  return (BigInt.asUintN(32, BigInt(hi)) << 32n) | BigInt.asUintN(32, BigInt(lo))
}

// Item name (and how many) needed for one block state, or null if it costs nothing.
function itemFor (name, props = {}) {
  name = name.replace(/^minecraft:/, '')
  if (SKIP.has(name)) return null
  if (props.half === 'upper' || props.part === 'head') return null // doors, tall plants, beds: counted once
  if (name.endsWith('_slab') && props.type === 'double') return [name, 2]
  if (props.waterlogged === 'true' && name === 'water') return null
  if (/^potted_/.test(name)) return ['flower_pot', 1]
  if (name.endsWith('_wall_sign')) return [name.replace('_wall_sign', '_sign'), 1]
  if (name.endsWith('_wall_hanging_sign')) return [name.replace('_wall_hanging_sign', '_hanging_sign'), 1]
  if (name.endsWith('_wall_banner')) return [name.replace('_wall_banner', '_banner'), 1]
  if (name.endsWith('_wall_head') || name.endsWith('_wall_skull')) return [name.replace('_wall_', '_'), 1]
  if (name.endsWith('_wall_fan')) return [name.replace('_wall_fan', '_fan'), 1]
  if (name.startsWith('candle') || name.endsWith('_candle')) return [name.replace(/_cake$/, ''), Number(props.candles ?? 1)]
  if (name === 'sea_pickle') return [name, Number(props.pickles ?? 1)]
  if (name === 'turtle_egg') return [name, Number(props.eggs ?? 1)]
  return [ITEM_FOR[name] ?? name, 1]
}

/** Parse a .litematic buffer: { name, author, size, totalBlocks, materials: {item: count} }. */
export async function readLitematic (buffer) {
  const { parsed } = await nbt.parse(buffer)
  const root = nbt.simplify(parsed)
  if (!root.Regions) throw new Error('not a Litematica schematic (no Regions)')
  const materials = {}
  let totalBlocks = 0
  for (const region of Object.values(root.Regions)) {
    const s = region.Size
    const volume = Math.abs(s.x) * Math.abs(s.y) * Math.abs(s.z)
    const palette = region.BlockStatePalette ?? []
    const bits = Math.max(2, Math.ceil(Math.log2(Math.max(palette.length, 1))))
    const get = bitArray((region.BlockStates ?? []).map(toLong), bits)
    const counts = new Array(palette.length).fill(0)
    for (let i = 0; i < volume; i++) counts[get(i)]++
    palette.forEach((p, idx) => {
      if (!counts[idx]) return
      const item = itemFor(p.Name, p.Properties)
      if (!item) return
      materials[item[0]] = (materials[item[0]] ?? 0) + counts[idx] * item[1]
      totalBlocks += counts[idx]
    })
  }
  const meta = root.Metadata ?? {}
  return {
    name: meta.Name ?? 'schematic',
    author: meta.Author ?? '',
    size: meta.EnclosingSize ?? null,
    totalBlocks,
    materials: Object.fromEntries(Object.entries(materials).sort((a, b) => b[1] - a[1]))
  }
}

/**
 * Baritone only builds Litematica format 7 (Minecraft 1.21+). Format 6 (1.18-1.20) stores blocks the same
 * way, so it's re-labelled as 7; anything older is refused with a clear message. Returns a gzipped buffer.
 */
export async function normalizeLitematic (buffer) {
  const { parsed } = await nbt.parse(buffer)
  if (!parsed.value.Regions) throw new Error('not a Litematica schematic (no Regions)')
  const version = parsed.value.Version?.value
  if (version === 7) return buffer
  if (version != null && version < 6) throw new Error(`this schematic was saved in a very old Litematica (format ${version}, Minecraft 1.17 or older); re-save it in a newer version`)
  parsed.value.Version = { type: 'int', value: 7 }
  return zlib.gzipSync(nbt.writeUncompressed(parsed))
}

/** Compare materials with an inventory {item: count}: what's still missing. */
export function missingMaterials (materials, inventory) {
  const missing = {}
  for (const [item, need] of Object.entries(materials)) {
    const have = inventory[item] ?? 0
    if (have < need) missing[item] = need - have
  }
  return missing
}
