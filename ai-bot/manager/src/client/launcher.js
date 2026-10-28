// Downloads and launches a real Minecraft client: Mojang's Java runtime, the vanilla client,
// Fabric, Baritone, ViaFabricPlus and our bridge mod. Runs it under a virtual X display.
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { spawn } from 'node:child_process'
import { ROOT } from '../config.js'
import { log } from '../log.js'

export const MC_VERSION = '26.1.2'
const FABRIC_LOADER = '0.20.1'
const JAVA_COMPONENT = 'java-runtime-epsilon'
const CACHE = path.join(ROOT, 'runtime')
const BRIDGE_JAR = path.join(ROOT, '..', 'bridge-mod', 'build', 'libs', 'ai-bridge-1.0.0.jar')

const MODS = [
  { modrinth: 'fabric-api', version: '0.155.3+26.1.2' },
  { modrinth: 'viafabricplus', version: '4.5.6' },
  { url: 'https://github.com/cabaletta/baritone/releases/download/v1.18.0/baritone-api-fabric-1.18.0.jar', file: 'baritone-api-fabric-1.18.0.jar' }
]

// ------------------------------------------------------------------ downloads

async function getJson (url) {
  const res = await fetch(url, { headers: { 'user-agent': 'moddedrealms-ai-bot' } })
  if (!res.ok) throw new Error(`GET ${url}: HTTP ${res.status}`)
  return res.json()
}

async function sha1 (file) {
  const h = crypto.createHash('sha1')
  for await (const chunk of fs.createReadStream(file)) h.update(chunk)
  return h.digest('hex')
}

// Download to `dest` unless it's already there with the right hash.
async function download (url, dest, hash) {
  if (fs.existsSync(dest) && (!hash || await sha1(dest) === hash)) return dest
  await fsp.mkdir(path.dirname(dest), { recursive: true })
  const res = await fetch(url, { headers: { 'user-agent': 'moddedrealms-ai-bot' } })
  if (!res.ok) throw new Error(`download ${url}: HTTP ${res.status}`)
  const tmp = `${dest}.part`
  await fsp.writeFile(tmp, Buffer.from(await res.arrayBuffer()))
  if (hash && await sha1(tmp) !== hash) throw new Error(`hash mismatch for ${url}`)
  await fsp.rename(tmp, dest)
  return dest
}

async function pool (items, limit, fn) {
  let i = 0
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (i < items.length) await fn(items[i++])
  }))
}

// ------------------------------------------------------------------ pieces

export async function ensureJava () {
  const dir = path.join(CACHE, 'java', JAVA_COMPONENT)
  const java = path.join(dir, 'bin', 'java')
  if (fs.existsSync(java)) return java
  log('[launcher] downloading Java runtime', JAVA_COMPONENT)
  const all = await getJson('https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json')
  const entry = all.linux[JAVA_COMPONENT]?.[0]
  if (!entry) throw new Error(`no ${JAVA_COMPONENT} for linux`)
  const manifest = await getJson(entry.manifest.url)
  const files = Object.entries(manifest.files)
  for (const [p, f] of files) if (f.type === 'directory') await fsp.mkdir(path.join(dir, p), { recursive: true })
  await pool(files.filter(([, f]) => f.type === 'file'), 16, async ([p, f]) => {
    const dest = path.join(dir, p)
    await download(f.downloads.raw.url, dest, f.downloads.raw.sha1)
    if (f.executable) await fsp.chmod(dest, 0o755)
  })
  for (const [p, f] of files) {
    if (f.type !== 'link') continue
    const dest = path.join(dir, p)
    await fsp.rm(dest, { force: true })
    await fsp.symlink(f.target, dest)
  }
  return java
}

const allowed = rules => {
  if (!rules?.length) return true
  let ok = false
  for (const r of rules) {
    if (r.features) continue
    if (!r.os || r.os.name === 'linux') ok = r.action === 'allow'
  }
  return ok
}

const mavenPath = name => {
  const [group, artifact, version, classifier] = name.split(':')
  return `${group.replace(/\./g, '/')}/${artifact}/${version}/${artifact}-${version}${classifier ? `-${classifier}` : ''}.jar`
}

async function ensureVersion () {
  const vdir = path.join(CACHE, 'versions', MC_VERSION)
  const vjsonFile = path.join(vdir, `${MC_VERSION}.json`)
  let vjson
  if (fs.existsSync(vjsonFile)) vjson = JSON.parse(await fsp.readFile(vjsonFile, 'utf8'))
  else {
    const manifest = await getJson('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')
    const v = manifest.versions.find(x => x.id === MC_VERSION)
    vjson = await getJson(v.url)
    await fsp.mkdir(vdir, { recursive: true })
    await fsp.writeFile(vjsonFile, JSON.stringify(vjson))
  }
  const clientJar = await download(vjson.downloads.client.url, path.join(vdir, `${MC_VERSION}.jar`), vjson.downloads.client.sha1)
  const libs = vjson.libraries.filter(l => allowed(l.rules) && l.downloads?.artifact)
  await pool(libs, 12, l => download(l.downloads.artifact.url, path.join(CACHE, 'libraries', l.downloads.artifact.path), l.downloads.artifact.sha1))
  // Everything but sounds and music (most of the ~450 MB): built-in packs and icons are required.
  const indexFile = await download(vjson.assetIndex.url, path.join(CACHE, 'assets', 'indexes', `${vjson.assetIndex.id}.json`), vjson.assetIndex.sha1)
  const objects = Object.entries(JSON.parse(await fsp.readFile(indexFile, 'utf8')).objects)
    .filter(([name]) => !/\/(sounds|music|records)\//.test(name) && !name.endsWith('.ogg'))
  await pool(objects, 16, ([, o]) => download(`https://resources.download.minecraft.net/${o.hash.slice(0, 2)}/${o.hash}`,
    path.join(CACHE, 'assets', 'objects', o.hash.slice(0, 2), o.hash), o.hash))

  const fabric = await getJson(`https://meta.fabricmc.net/v2/versions/loader/${MC_VERSION}/${FABRIC_LOADER}/profile/json`)
  await pool(fabric.libraries, 8, l => download(`${l.url.replace(/\/?$/, '/')}${mavenPath(l.name)}`, path.join(CACHE, 'libraries', mavenPath(l.name)), l.sha1))

  // Fabric's copies of shared libraries (asm...) win over vanilla's.
  const key = name => name.split(':').slice(0, 2).join(':')
  const fabricKeys = new Set(fabric.libraries.map(l => key(l.name)))
  const classpath = [
    ...fabric.libraries.map(l => path.join(CACHE, 'libraries', mavenPath(l.name))),
    ...libs.filter(l => !fabricKeys.has(key(l.name))).map(l => path.join(CACHE, 'libraries', l.downloads.artifact.path)),
    clientJar
  ]
  return { vjson, fabric, classpath }
}

async function ensureMods () {
  const dir = path.join(CACHE, 'mods')
  const files = []
  for (const m of MODS) {
    if (m.url) {
      files.push(await download(m.url, path.join(dir, m.file)))
      continue
    }
    const versions = await getJson(`https://api.modrinth.com/v2/project/${m.modrinth}/version`)
    const v = versions.find(x => x.version_number === m.version)
    if (!v) throw new Error(`${m.modrinth} ${m.version} not found on Modrinth`)
    const f = v.files.find(x => x.primary) ?? v.files[0]
    files.push(await download(f.url, path.join(dir, f.filename), f.hashes.sha1))
  }
  if (!fs.existsSync(BRIDGE_JAR)) throw new Error(`bridge mod not built: run ./gradlew build in ai-bot/bridge-mod (${BRIDGE_JAR})`)
  files.push(BRIDGE_JAR)
  return files
}

// Pre-seed options so the client skips first-run screens and renders as little as possible.
const OPTIONS = {
  onboardAccessibility: 'false',
  skipMultiplayerWarning: 'true',
  joinedFirstServer: 'true',
  tutorialStep: 'none',
  renderDistance: '4',
  simulationDistance: '5',
  maxFps: '10',
  pauseOnLostFocus: 'false',
  narrator: '0',
  soundCategory_master: '0.0',
  particles: '2',
  graphicsMode: '0',
  renderClouds: '"false"',
  entityShadows: 'false',
  autoJump: 'false'
}

async function prepareGameDir (gameDir, modFiles) {
  const mods = path.join(gameDir, 'mods')
  await fsp.rm(mods, { recursive: true, force: true })
  await fsp.mkdir(mods, { recursive: true })
  for (const f of modFiles) await fsp.copyFile(f, path.join(mods, path.basename(f)))
  await fsp.mkdir(path.join(gameDir, 'schematics'), { recursive: true })
  const optFile = path.join(gameDir, 'options.txt')
  const current = {}
  try {
    for (const line of (await fsp.readFile(optFile, 'utf8')).split('\n')) {
      const i = line.indexOf(':')
      if (i > 0) current[line.slice(0, i)] = line.slice(i + 1)
    }
  } catch {}
  Object.assign(current, OPTIONS)
  await fsp.writeFile(optFile, Object.entries(current).map(([k, v]) => `${k}:${v}`).join('\n') + '\n')
}

export function offlineUuid (name) {
  const h = crypto.createHash('md5').update(`OfflinePlayer:${name}`).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  return h.toString('hex')
}

// ------------------------------------------------------------------ launch

/**
 * Starts a client. `account` is { name, uuid, accessToken } (Microsoft) or { name, offline: true }.
 * Returns the child process; its stdout/stderr go to `logFile`.
 */
export async function launchClient ({ gameDir, account, server, bridgePort, bridgeToken, heapMb = 1400, logFile }) {
  const java = await ensureJava()
  const { vjson, fabric, classpath } = await ensureVersion()
  await prepareGameDir(gameDir, await ensureMods())
  const natives = path.join(gameDir, 'natives')
  await fsp.mkdir(natives, { recursive: true })

  const vars = {
    auth_player_name: account.name,
    version_name: MC_VERSION,
    game_directory: gameDir,
    assets_root: path.join(CACHE, 'assets'),
    assets_index_name: vjson.assetIndex.id,
    auth_uuid: account.uuid ?? offlineUuid(account.name),
    auth_access_token: account.offline ? '0' : account.accessToken,
    clientid: '',
    auth_xuid: '',
    user_type: account.offline ? 'legacy' : 'msa',
    version_type: 'release',
    natives_directory: natives,
    launcher_name: 'moddedrealms-ai',
    launcher_version: '1',
    classpath: classpath.join(path.delimiter)
  }
  const fill = s => s.replace(/\$\{(\w+)\}/g, (_, k) => vars[k] ?? '')
  const flat = list => list.filter(a => typeof a === 'string').map(fill)

  const jvm = [
    `-Xmx${heapMb}m`, '-XX:+UseG1GC',
    `-Daibridge.port=${bridgePort}`, `-Daibridge.token=${bridgeToken}`, '-Daibridge.norender=true',
    ...flat(vjson.arguments.jvm),
    ...flat(fabric.arguments?.jvm ?? [])
  ]
  const game = flat(vjson.arguments.game).filter((a, i, arr) => !((a === '--clientId' || a === '--xuid') || ((arr[i - 1] === '--clientId' || arr[i - 1] === '--xuid'))))
  if (server) game.push('--quickPlayMultiplayer', server)

  const out = fs.openSync(logFile, 'a')
  const child = spawn('xvfb-run', ['-a', '-s', '-screen 0 854x480x24', java, ...jvm, fabric.mainClass, ...game], {
    cwd: gameDir,
    env: { ...process.env, LIBGL_ALWAYS_SOFTWARE: '1' },
    stdio: ['ignore', out, out],
    detached: true
  })
  log(`[launcher] started client for ${account.name} (pid ${child.pid})${server ? ` -> ${server}` : ''}`)
  return child
}
