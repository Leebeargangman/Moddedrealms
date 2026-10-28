// Writes small .litematic files, e.g. the nether portal frame Baritone builds for nether travel.
import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'
import nbt from 'prismarine-nbt'

const int = value => ({ type: 'int', value })
const str = value => ({ type: 'string', value })
const compound = value => ({ type: 'compound', value })
const vec = (x, y, z) => compound({ x: int(x), y: int(y), z: int(z) })

/** blocks: (x, y, z) => block name or null (air). Litematica packs palette indices across longs. */
export function writeLitematic (file, name, size, blocks) {
  const palette = ['minecraft:air']
  const values = []
  // Litematica order: x fastest, then z, then y.
  for (let y = 0; y < size.y; y++) {
    for (let z = 0; z < size.z; z++) {
      for (let x = 0; x < size.x; x++) {
        const b = blocks(x, y, z) ?? 'minecraft:air'
        let i = palette.indexOf(b)
        if (i < 0) { palette.push(b); i = palette.length - 1 }
        values.push(i)
      }
    }
  }
  const bits = Math.max(2, Math.ceil(Math.log2(palette.length)))
  const longs = new Array(Math.ceil(values.length * bits / 64)).fill(0n)
  values.forEach((v, i) => {
    const start = BigInt(i * bits)
    const idx = Number(start >> 6n)
    const off = start & 63n
    longs[idx] |= (BigInt(v) << off) & ((1n << 64n) - 1n)
    if (off + BigInt(bits) > 64n) longs[idx + 1] |= BigInt(v) >> (64n - off)
  })
  const pair = l => {
    const s = BigInt.asIntN(64, l)
    return [Number(BigInt.asIntN(32, s >> 32n)), Number(BigInt.asIntN(32, s & 0xffffffffn))]
  }
  const total = values.filter(v => v !== 0).length
  const root = compound({
    MinecraftDataVersion: int(4671),
    Version: int(7),
    Metadata: compound({
      Name: str(name), Author: str('Moddedrealms AI'), Description: str(''),
      RegionCount: int(1), TotalBlocks: int(total), TotalVolume: int(values.length),
      EnclosingSize: vec(size.x, size.y, size.z),
      TimeCreated: { type: 'long', value: [0, 0] }, TimeModified: { type: 'long', value: [0, 0] }
    }),
    Regions: compound({
      [name]: compound({
        Position: vec(0, 0, 0),
        Size: vec(size.x, size.y, size.z),
        BlockStatePalette: { type: 'list', value: { type: 'compound', value: palette.map(n => ({ Name: str(n) })) } },
        BlockStates: { type: 'longArray', value: longs.map(pair) },
        TileEntities: { type: 'list', value: { type: 'compound', value: [] } },
        Entities: { type: 'list', value: { type: 'compound', value: [] } },
        PendingBlockTicks: { type: 'list', value: { type: 'compound', value: [] } },
        PendingFluidTicks: { type: 'list', value: { type: 'compound', value: [] } }
      })
    })
  })
  fs.mkdirSync(path.dirname(file), { recursive: true })
  fs.writeFileSync(file, zlib.gzipSync(nbt.writeUncompressed({ ...root, name: '' })))
}

/** Minimal portal frame along X: 4 wide, 5 tall, no corners (10 obsidian), inside kept clear. */
export function writePortalSchematic (file) {
  writeLitematic(file, 'nether_portal', { x: 4, y: 5, z: 1 }, (x, y) => {
    const edgeX = x === 0 || x === 3
    const edgeY = y === 0 || y === 4
    if (edgeX && edgeY) return null
    if (edgeX || edgeY) return 'minecraft:obsidian'
    return null
  })
}
