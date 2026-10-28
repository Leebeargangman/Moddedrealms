// Asks a server which Minecraft version it runs (a normal server-list ping), so ViaFabricPlus can be told
// to translate our 26.1.2 client to it. Servers that accept 26.1.2 (they answer with our own protocol) get none.
import net from 'node:net'
import dns from 'node:dns/promises'

export const NATIVE_PROTOCOL = 775 // 26.1-26.1.2
export const NATIVE_VERSION = '26.1.2'

// Protocol number -> a release ViaVersion's ProtocolVersion.getClosest() understands.
const RELEASES = {
  775: '26.1.2', 774: '1.21.11', 773: '1.21.10', 772: '1.21.8', 771: '1.21.6', 770: '1.21.5', 769: '1.21.4',
  768: '1.21.3', 767: '1.21.1', 766: '1.20.6', 765: '1.20.4', 764: '1.20.2', 763: '1.20.1', 762: '1.19.4',
  761: '1.19.3', 760: '1.19.2', 759: '1.19', 758: '1.18.2', 757: '1.18.1', 756: '1.17.1', 755: '1.17',
  754: '1.16.5', 753: '1.16.3', 751: '1.16.2', 736: '1.16.1', 735: '1.16', 578: '1.15.2', 498: '1.14.4',
  404: '1.13.2', 340: '1.12.2', 316: '1.11.2', 210: '1.10.2', 110: '1.9.4', 47: '1.8.9'
}

function varint (n) {
  const out = []
  do {
    let b = n & 0x7f
    n >>>= 7
    if (n) b |= 0x80
    out.push(b)
  } while (n)
  return Buffer.from(out)
}

function readVarint (buf, pos) {
  let value = 0
  for (let shift = 0; shift < 35; shift += 7) {
    if (pos >= buf.length) return null
    const b = buf[pos++]
    value |= (b & 0x7f) << shift
    if (!(b & 0x80)) return [value, pos]
  }
  throw new Error('bad varint')
}

/** Server-list ping. Resolves to the server's { version: { name, protocol } } or null if it doesn't answer. */
export async function pingServer (address, timeoutMs = 8000) {
  let [host, portText] = address.includes(':') ? address.split(':') : [address, '25565']
  let port = Number(portText) || 25565
  // Like the game, follow the _minecraft._tcp SRV record when no port is given (2b2t.org, 9b9t.com...).
  if (!address.includes(':')) {
    const srv = await dns.resolveSrv(`_minecraft._tcp.${host}`).catch(() => [])
    if (srv[0]) ({ name: host, port } = srv[0])
  }
  return new Promise(resolve => {
    const socket = net.connect(port, host)
    let data = Buffer.alloc(0)
    const done = value => { socket.destroy(); resolve(value) }
    socket.setTimeout(timeoutMs, () => done(null))
    socket.on('error', () => done(null))
    socket.on('connect', () => {
      const hostBuf = Buffer.from(host)
      const portBuf = Buffer.alloc(2)
      portBuf.writeUInt16BE(port)
      const handshake = Buffer.concat([varint(0), varint(NATIVE_PROTOCOL), varint(hostBuf.length), hostBuf, portBuf, varint(1)])
      socket.write(Buffer.concat([varint(handshake.length), handshake, varint(1), varint(0)]))
    })
    socket.on('data', chunk => {
      data = Buffer.concat([data, chunk])
      try {
        const len = readVarint(data, 0)
        if (!len || data.length < len[1] + len[0]) return
        const [, afterId] = readVarint(data, len[1])
        const [strLen, start] = readVarint(data, afterId)
        done(JSON.parse(data.subarray(start, start + strLen).toString('utf8')))
      } catch {
        done(null)
      }
    })
  })
}

// Last version seen per address: servers throttle quick repeat connections (several bots starting together), so a
// ping can go unanswered; falling back to our own version then gets the bot kicked as "Outdated server".
const known = new Map()

/**
 * The version ViaFabricPlus should use for `address`: our own if the server accepts it, else the server's release
 * (from its protocol number, or the version text like "Paper 1.21.11"). Unreachable: the last version seen for it.
 */
export async function detectServerVersion (address) {
  let status = null
  for (let attempt = 0; attempt < 3 && !status?.version?.protocol; attempt++) {
    if (attempt) await new Promise(resolve => setTimeout(resolve, 4000))
    status = await pingServer(address)
  }
  const protocol = status?.version?.protocol
  if (!protocol) return known.get(address) ?? NATIVE_VERSION
  let version = NATIVE_VERSION
  if (protocol !== NATIVE_PROTOCOL) {
    const named = String(status.version.name ?? '').match(/\b(?:1\.\d+(?:\.\d+)?|2\d\.\d+(?:\.\d+)?)\b/g)
    version = RELEASES[protocol] ?? (named ? named[named.length - 1] : NATIVE_VERSION)
  }
  known.set(address, version)
  return version
}
