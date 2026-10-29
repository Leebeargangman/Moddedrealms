// Node side of the bridge mod: a JSON-lines TCP connection to 127.0.0.1 inside the real client.
// Requests: {id, op, ...args} -> {id, ok, result|error}. Pushed: {event, ...} and {event:'state', state}.
import net from 'node:net'
import { EventEmitter } from 'node:events'
import { sleep } from '../util.js'

export class Bridge extends EventEmitter {
  constructor ({ port, token }) {
    super()
    this.port = port
    this.token = token
    this.nextId = 1
    this.pending = new Map()
    this.state = null
    this.connected = false
  }

  // Keep trying until the game has started and opened the socket.
  async connect (timeoutMs = 180000) {
    const t0 = Date.now()
    while (Date.now() - t0 < timeoutMs) {
      if (this.closing) throw new Error('bridge closed')
      try {
        await this.#open()
        await this.request('auth', { token: this.token })
        this.connected = true
        this.emit('connected')
        return
      } catch {
        this.socket?.destroy()
        await sleep(2000)
      }
    }
    throw new Error('bridge did not come up (did the client start?)')
  }

  #open () {
    return new Promise((resolve, reject) => {
      const socket = net.connect({ host: '127.0.0.1', port: this.port })
      this.socket = socket
      let buf = ''
      socket.setEncoding('utf8')
      socket.once('connect', resolve)
      socket.once('error', reject)
      socket.on('data', chunk => {
        buf += chunk
        let i
        while ((i = buf.indexOf('\n')) >= 0) {
          const line = buf.slice(0, i)
          buf = buf.slice(i + 1)
          if (line.trim()) this.#onMessage(line)
        }
      })
      socket.on('close', () => {
        const was = this.connected
        this.connected = false
        for (const p of this.pending.values()) p.reject(new Error('bridge closed'))
        this.pending.clear()
        if (was) this.emit('closed')
      })
    })
  }

  #onMessage (line) {
    let msg
    try { msg = JSON.parse(line) } catch { return }
    if (msg.id != null && this.pending.has(msg.id)) {
      const p = this.pending.get(msg.id)
      this.pending.delete(msg.id)
      clearTimeout(p.timer)
      if (msg.ok) p.resolve(msg.result)
      else p.reject(new Error(msg.error || 'bridge error'))
      return
    }
    if (msg.event === 'state') {
      this.state = msg.state
      this.emit('state', msg.state)
    } else if (msg.event) {
      this.emit(msg.event, msg)
    }
  }

  request (op, args = {}, timeoutMs = 15000) {
    return new Promise((resolve, reject) => {
      if (!this.socket || this.socket.destroyed) return reject(new Error('bridge not connected'))
      const id = this.nextId++
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`bridge ${op} timed out`))
      }, timeoutMs)
      this.pending.set(id, { resolve, reject, timer })
      this.socket.write(JSON.stringify({ id, op, ...args }) + '\n')
    })
  }

  // Shorthands used by the skills.
  baritone (command) { return this.request('baritone', { command }) }
  chat (text) { return this.request('chat', { text }) }

  close () {
    this.closing = true
    this.connected = false
    this.socket?.destroy()
  }
}
