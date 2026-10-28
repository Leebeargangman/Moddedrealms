import { EventEmitter } from 'node:events'

// 2b2t: "Position in queue: 123"; other anarchy servers (9b9t...) use similar wording.
const POSITION_RE = /(?:(?:position|place) in (?:the )?queue|queue position)[^\d]{0,12}(\d+)|you are (?:number |#)?(\d+)(?:st|nd|rd|th)? in (?:the )?(?:queue|line)/i
const CONNECTED_RE = /connected to the server|you are now playing|joined the server/i

// Tracks whether we are waiting in a server queue or actually playing, from the bridge's chat feed.
// States: offline -> unknown -> queue -> game. Emits 'state' and 'position'.
export class QueueTracker extends EventEmitter {
  constructor (bridge, { unknownTimeoutMs = 25000, queueSilenceMs = 120000 } = {}) {
    super()
    this.state = 'offline'
    this.position = null
    this.history = [] // [{t, p}]
    this.lastQueueMsg = 0
    this.unknownTimeoutMs = unknownTimeoutMs
    this.queueSilenceMs = queueSilenceMs

    bridge.on('join', () => { this.joinedAt = Date.now(); this.position = null; this._set('unknown') })
    bridge.on('disconnect', () => this._set('offline'))
    bridge.on('closed', () => this._set('offline'))
    for (const ev of ['system', 'actionbar']) bridge.on(ev, m => this._scan(m.text))
    this.timer = setInterval(() => this._poll(), 3000)
  }

  stop () { clearInterval(this.timer) }

  _set (state) {
    if (this.state === state) return
    const prev = this.state
    this.state = state
    this.emit('state', state, prev)
  }

  _scan (text) {
    if (!text || text.startsWith('<')) return // ignore player chat
    const m = POSITION_RE.exec(text)
    if (m) {
      const p = Number(m[1] ?? m[2])
      this.lastQueueMsg = Date.now()
      if (p !== this.position) {
        this.position = p
        this.history.push({ t: Date.now(), p })
        if (this.history.length > 400) this.history.shift()
        this.emit('position', p)
      }
      this._set('queue')
      return
    }
    if (CONNECTED_RE.test(text) && this.state !== 'game' && this.state !== 'offline') this._set('game')
  }

  _poll () {
    const now = Date.now()
    // Servers without a queue (or one we didn't recognise): assume we're playing.
    if (this.state === 'unknown' && now - this.joinedAt > this.unknownTimeoutMs) this._set('game')
    // Queue went quiet (moved to the main server): we got in.
    if (this.state === 'queue' && now - this.lastQueueMsg > this.queueSilenceMs) this._set('game')
  }

  // Estimated ms left in the queue, from the last ~30 min of movement.
  eta () {
    const h = this.history.filter(x => Date.now() - x.t < 30 * 60e3)
    if (h.length < 2 || this.position == null) return null
    const moved = h[0].p - h[h.length - 1].p
    const dt = h[h.length - 1].t - h[0].t
    if (moved <= 0 || dt <= 0) return null
    return Math.round(this.position / (moved / dt))
  }
}
