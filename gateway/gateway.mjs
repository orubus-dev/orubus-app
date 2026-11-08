/**
 * orubus-backend — stream B: the ALWAYS-ON Discord gateway, on real compute.
 *
 * The Pages dc-gateway (stream A) holds 120-second shifts under Cloudflare's
 * runtime kill. This process is a REAL long-running client: one websocket per
 * bot, hours at a time, proper RESUME, zero handover gaps — the fix for
 * "the Banga bot feels dead". It does NOT run the brain: every DM and guild
 * mention is POSTed to the Pages dc-ingest route, which runs the exact same
 * turn pipeline (typing, intent, brain, delivery) with the SAME lossless
 * markers — one brain, two sockets, no double replies.
 *
 *   PAGES_BASE   https://orubus-ai.pages.dev
 *   GATEWAY_KEY  the shared gateway key (also authorizes /api/dc-ingest)
 *   HOLD_MS      how long this run holds the stream (Actions: ~5h40m)
 *
 * Liveness contract: while ANY socket is open this process heartbeats the
 * shared gateway lock every 20s; stream A skips its cron shift while that
 * lock is fresh. If this process dies the lock goes stale in ≤50s and stream
 * A takes over — two streams, zero abandoned windows.
 *
 * Run: PAGES_BASE=… GATEWAY_KEY=… node gateway/gateway.mjs
 */

const PAGES_BASE = (process.env.PAGES_BASE || 'https://orubus-ai.pages.dev').replace(/\/+$/, '')
const KEY = (process.env.GATEWAY_KEY || '').trim()
const HOLD_MS = Number(process.env.HOLD_MS || 20_400_000) // ~5h40m (under the 6h Actions cap)
const LOCK_EVERY_MS = 20_000
const NOTE_EVERY_MS = 5 * 60_000
const INTENTS = 1 | 512 | 4096 // GUILDS | GUILD_MESSAGES | DIRECT_MESSAGES

const STARTED = Date.now()
const events = []
const seen = new Map() // "channelId:messageId" -> ts (dedupe across reconnects)

function log(msg) {
  const line = `${new Date().toISOString().slice(11, 19)} ${msg}`
  console.log(line)
  events.push(line.slice(7))
  if (events.length > 400) events.splice(0, events.length - 400)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function pages(path, body) {
  const r = await fetch(`${PAGES_BASE}/api/${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-gateway-key': KEY },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(90_000),
  })
  const j = await r.json().catch(() => ({}))
  if (!r.ok) throw new Error(`${path}:${r.status}:${String(j.error || '').slice(0, 80)}`)
  return j
}

async function ingest(body) {
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      return await pages('dc-ingest', body)
    } catch (e) {
      if (attempt === 1) {
        log(`ingest ${body.op} failed: ${String(e.message || e).slice(0, 80)}`)
        return null
      }
      await sleep(3_000)
    }
  }
}

/** Stream A holds the lock while its shift runs — wait briefly, then take it. */
async function waitForLock() {
  const deadline = Date.now() + 70_000
  while (Date.now() < deadline) {
    try {
      const r = await fetch(`${PAGES_BASE}/api/dc-gateway?key=${encodeURIComponent(KEY)}&status=1`, {
        signal: AbortSignal.timeout(10_000),
      })
      const j = await r.json().catch(() => ({}))
      const age = Number(j.lockAgeS)
      if (!j.lockAgeS || age >= 50 || j.last?.log?.some((l) => String(l).includes('streamb'))) return
    } catch {}
    await sleep(8_000)
  }
}

async function holdLock() {
  const beat = async () => {
    try {
      await pages('dc-ingest', { op: 'lock' })
    } catch (e) {
      log(`lock beat failed: ${String(e.message || e).slice(0, 60)}`)
    }
  }
  await beat()
  const iv = setInterval(beat, LOCK_EVERY_MS)
  return () => clearInterval(iv)
}

function pushNote() {
  ingest({ op: 'note', at: Date.now(), log: events.slice(-24) }).catch(() => {})
}

// ---------- one bot socket ----------

class BotStream {
  constructor(label, token, appIdx) {
    this.label = label
    this.token = token
    this.appIdx = appIdx
    this.ws = null
    this.seq = 0
    this.sessionId = null
    this.botId = ''
    this.hb = null
    this.dead = false
    this.backoff = 1_000
  }

  async run(onDown) {
    while (!this.dead && Date.now() - STARTED < HOLD_MS) {
      try {
        await this.connect()
        this.backoff = 1_000
      } catch (e) {
        log(`${this.label}: connect ${String(e.message || e).slice(0, 70)}`)
      }
      if (this.dead || Date.now() - STARTED >= HOLD_MS) break
      await sleep(this.backoff + Math.random() * 2_000)
      this.backoff = Math.min(this.backoff * 2, 30_000)
    }
    onDown?.()
  }

  connect() {
    return new Promise((resolve) => {
      let settled = false
      const done = () => {
        if (settled) return
        settled = true
        clearInterval(this.hb)
        resolve()
      }
      // RESUME only inside the same process run; a fresh run IDENTIFYs
      const canResume = Boolean(this.sessionId)
      const ws = new WebSocket('wss://gateway.discord.gg/?v=10&encoding=json')
      this.ws = ws

      ws.onopen = () => log(`${this.label}: socket open${canResume ? ' (resume)' : ''}`)
      ws.onmessage = (ev) => {
        let p
        try {
          p = JSON.parse(String(ev.data))
        } catch {
          return
        }
        if (typeof p.s === 'number') this.seq = p.s
        switch (p.op) {
          case 10: {
            const interval = Math.max(5_000, Number(p.d?.heartbeat_interval ?? 41_250) - 2_000)
            clearInterval(this.hb)
            this.hb = setInterval(() => {
              try {
                ws.send(JSON.stringify({ op: 1, d: this.seq || null }))
              } catch {}
            }, interval)
            if (canResume && this.sessionId) {
              ws.send(JSON.stringify({ op: 6, d: { token: this.token, session_id: this.sessionId, seq: this.seq } }))
            } else {
              ws.send(
                JSON.stringify({
                  op: 2,
                  d: {
                    token: this.token,
                    intents: Number(process.env.DC_INTENTS || INTENTS),
                    properties: { os: 'linux', browser: 'orubus-backend', device: 'orubus-backend' },
                    presence: {
                      since: null,
                      activities: [{ name: 'Custom Status', type: 4, state: 'no commands — just talk 💬' }],
                      status: 'online',
                      afk: false,
                    },
                  },
                })
              )
            }
            break
          }
          case 0: {
            if (p.t === 'READY') {
              this.sessionId = String(p.d?.session_id || '')
              this.botId = String(p.d?.user?.id || '')
              log(`${this.label}: READY (${this.botId})`)
            } else if (p.t === 'RESUMED') {
              log(`${this.label}: RESUMED @s${this.seq}`)
            } else if (p.t === 'MESSAGE_CREATE') {
              this.onMessage(p.d ?? {})
            }
            break
          }
          case 7:
            log(`${this.label}: reconnect requested`)
            try {
              ws.close(4000, 'reconnect')
            } catch {}
            done()
            break
          case 9:
            // invalid session — resumable flag false means start clean
            if (p.d === false) this.sessionId = null
            log(`${this.label}: op9 (${p.d === false ? 'dead' : 'resumable'})`)
            try {
              ws.close(4000, 'op9')
            } catch {}
            done()
            break
          case 11:
            break // heartbeat ack
        }
      }
      ws.onerror = () => done()
      ws.onclose = (ev) => {
        log(`${this.label}: close ${ev?.code ?? '?'} @s${this.seq}`)
        done()
      }
    })
  }

  deduped(key) {
    const now = Date.now()
    if (seen.has(key)) return true
    seen.set(key, now)
    if (seen.size > 6_000) {
      for (const [k, t] of seen) if (now - t > 30 * 60_000) seen.delete(k)
    }
    return false
  }

  async onMessage(d) {
    const isDm = d.channel_type === 1 || (!d.guild_id && d.author && d.channel_id)
    const name = String(d.author?.global_name || d.author?.username || '') || undefined
    if (isDm) {
      if (!d.author || d.author.bot || typeof d.content !== 'string' || !d.content.trim() || !d.channel_id) return
      const key = `${d.channel_id}:${d.id}`
      if (this.deduped(key)) return
      log(`${this.label}: dm ${name || d.author.id}`)
      await ingest({
        op: 'dm',
        appIdx: this.appIdx,
        authorId: String(d.author.id),
        channelId: String(d.channel_id),
        content: d.content,
        name,
        messageId: String(d.id || ''),
      })
    } else if (d.guild_id && d.author && !d.author.bot && typeof d.content === 'string' && this.botId) {
      const mentioned = Array.isArray(d.mentions) && d.mentions.some((u) => String(u?.id ?? '') === this.botId)
      const repliedToMe = String(d.referenced_message?.author?.id ?? '') === this.botId
      if (!mentioned && !repliedToMe) return
      const clean = d.content.replace(new RegExp(`<@!?${this.botId}>`, 'g'), '').trim()
      if (!clean) return
      const key = `${d.channel_id}:${d.id}`
      if (this.deduped(key)) return
      log(`${this.label}: guild ${name || d.author.id}`)
      const ref = d.referenced_message?.author
      await ingest({
        op: 'guild',
        appIdx: this.appIdx,
        authorId: String(d.author.id),
        guildId: String(d.guild_id),
        channelId: String(d.channel_id),
        content: clean,
        name,
        messageId: String(d.id || ''),
        replyTargetId: ref && String(ref.id) !== this.botId ? String(ref.id) : undefined,
        replyTargetName: ref ? String(ref.global_name || ref.username || '') || undefined : undefined,
      })
    }
  }

  stop() {
    this.dead = true
    try {
      this.ws?.close(1000, 'hold-over')
    } catch {}
    clearInterval(this.hb)
  }
}

// ---------- main ----------

async function main() {
  if (!KEY) {
    console.error('GATEWAY_KEY is required')
    process.exit(1)
  }
  log(`stream B starting — hold ${Math.round(HOLD_MS / 60_000)}min`)

  // config: labels + tokens come from the Pages gateway (TLS, key-gated)
  let cfg
  for (let attempt = 0; attempt < 5; attempt++) {
    try {
      const r = await fetch(`${PAGES_BASE}/api/dc-gateway?key=${encodeURIComponent(KEY)}&config=1`, {
        signal: AbortSignal.timeout(15_000),
      })
      cfg = await r.json()
      if (cfg?.ok && Array.isArray(cfg.apps) && cfg.apps.length) break
    } catch (e) {
      log(`config fetch ${attempt + 1}: ${String(e.message || e).slice(0, 70)}`)
    }
    await sleep(5_000)
  }
  if (!cfg?.ok) {
    console.error('no gateway config — exiting (the queued run will retry)')
    process.exit(1)
  }
  log(`config: ${cfg.apps.map((a) => a.label).join(' + ')}`)

  await waitForLock()
  const releaseLock = await holdLock()
  const noteIv = setInterval(pushNote, NOTE_EVERY_MS)

  const bots = cfg.apps.map((a, i) => new BotStream(a.label, a.token, i))
  // every bot gets its own resilient socket loop; one dying never kills the others
  await Promise.all(bots.map((b) => b.run().catch((e) => log(`${b.label}: loop ${String(e.message || e).slice(0, 70)}`))))

  clearInterval(noteIv)
  bots.forEach((b) => b.stop())
  releaseLock()
  // hand the lock back BEFORE exiting so stream A takes over within seconds
  try {
    await pages('dc-ingest', { op: 'unlock' })
  } catch {}
  log(`hold over after ${Math.round((Date.now() - STARTED) / 60_000)}min — clean exit`)
  pushNote()
  process.exit(0)
}

process.on('SIGTERM', () => {
  log('SIGTERM — releasing lock')
  fetch(`${PAGES_BASE}/api/dc-ingest`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-gateway-key': KEY },
    body: JSON.stringify({ op: 'unlock' }),
    signal: AbortSignal.timeout(3_000),
  }).catch(() => {})
  setTimeout(() => process.exit(0), 1_500)
})

main().catch((e) => {
  console.error('fatal:', e)
  process.exit(1)
})
