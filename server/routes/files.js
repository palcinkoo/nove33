// File reassembly endpoint. The app posts `file_header`, then a stream of
// `file_chunk`, then a `file_footer`. We reassemble per (deviceId, id) into
// `var/files/<deviceId>/<id>__<originalName>`, write the SHA-256 + total size
// into `var/files/<deviceId>/<id>.json`, and surface a /files/:deviceId/:id
// download route that the dashboard uses to view / stream the file.

import path from 'node:path'
import fs from 'node:fs'
import crypto from 'node:crypto'
import { db } from '../lib/firebase.js'

const FILES_ROOT = path.resolve(process.cwd(), 'var', 'files')
fs.mkdirSync(FILES_ROOT, { recursive: true })

const inflight = new Map() // deviceId -> Map(id -> { name, mime, size, sha, totalChunks, got, fd, path })

function deviceDir(deviceId) {
  const d = path.join(FILES_ROOT, deviceId)
  fs.mkdirSync(d, { recursive: true })
  return d
}

function safeId(id) { return String(id).replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 128) }

function openStream(deviceId, id) {
  if (!inflight.has(deviceId)) inflight.set(deviceId, new Map())
  const perDevice = inflight.get(deviceId)
  if (perDevice.has(id)) return perDevice.get(id)
  const stub = { fd: null, path: null, got: 0, sha: crypto.createHash('sha256'), totalChunks: 0, name: '', mime: '', size: 0, meta: {} }
  perDevice.set(id, stub)
  return stub
}

export async function handleFileMessage(deviceId, msg) {
  const id = safeId(msg?.id || '')
  if (!id) return false
  if (msg.type === 'file_header') {
    const c = msg.content
    const stub = openStream(deviceId, id)
    const dir = deviceDir(deviceId)
    const filename = `${id}__${(c.name || 'file').replace(/[^a-zA-Z0-9._-]/g, '_')}`
    const fullPath = path.join(dir, filename)
    try { if (stub.fd) fs.closeSync(stub.fd) } catch (_) {}
    if (fs.existsSync(fullPath)) fs.unlinkSync(fullPath)
    stub.fd = fs.openSync(fullPath, 'w')
    stub.path = fullPath
    stub.got = 0
    stub.sha = crypto.createHash('sha256')
    stub.totalChunks = Number(c.chunks) || 0
    stub.size = Number(c.size) || 0
    stub.name = c.name || ''
    stub.mime = c.mime || 'application/octet-stream'
    stub.meta = {}
    Object.keys(c || {}).forEach((k) => { if (k.startsWith('meta_')) stub.meta[k.slice(5)] = c[k] })
    // write a manifest
    fs.writeFileSync(path.join(dir, `${id}.json`), JSON.stringify({
      deviceId, id, name: stub.name, mime: stub.mime, size: stub.size, sha256: c.sha256, chunks: stub.totalChunks, meta: stub.meta, createdAt: Date.now()
    }, null, 2))
    return true
  }
  if (msg.type === 'file_chunk') {
    const stub = openStream(deviceId, id)
    if (!stub.fd) return false
    const data = Buffer.from(String(msg.content.data || ''), 'base64')
    fs.writeSync(stub.fd, data, 0, data.length, stub.got)
    stub.sha.update(data)
    stub.got += data.length
    return true
  }
  if (msg.type === 'file_footer') {
    const stub = openStream(deviceId, id)
    if (!stub.fd) return false
    try { fs.closeSync(stub.fd) } catch (_) {}
    stub.fd = null
    const manifestPath = path.join(deviceDir(deviceId), `${id}.json`)
    let m = {}
    try { m = JSON.parse(fs.readFileSync(manifestPath, 'utf8')) } catch (_) {}
    m.received = stub.got
    m.completedAt = Date.now()
    m.sha256_actual = stub.sha.digest('hex')
    m.complete = stub.got >= stub.size
    fs.writeFileSync(manifestPath, JSON.stringify(m, null, 2))
    // also drop a RTDB pointer for live dashboards
    try {
      await db.ref(`devices/${deviceId}/files/${id}`).set({
        name: stub.name, mime: stub.mime, size: stub.size, sha256: stub.sha.digest('hex'),
        complete: m.complete, createdAt: m.createdAt, completedAt: m.completedAt
      })
    } catch (e) { /* ignore */ }
    inflight.get(deviceId)?.delete(id)
    return true
  }
  return false
}

export function listFilesRoute(req, res) {
  const deviceId = String(req.params.deviceId || '').replace(/[^a-zA-Z0-9_-]/g, '')
  if (!deviceId) return res.status(400).json({ error: 'invalid deviceId' })
  const dir = deviceDir(deviceId)
  const manifests = fs.readdirSync(dir).filter((f) => f.endsWith('.json'))
  const items = manifests.map((f) => {
    try {
      const j = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'))
      return { id: j.id, name: j.name, mime: j.mime, size: j.size, complete: j.complete, createdAt: j.createdAt, completedAt: j.completedAt, sha256: j.sha256_actual || j.sha256 }
    } catch (_) { return null }
  }).filter(Boolean)
  res.json({ success: true, files: items.sort((a, b) => (b.completedAt || b.createdAt || 0) - (a.completedAt || a.createdAt || 0)) })
}

export function downloadFileRoute(req, res) {
  const deviceId = String(req.params.deviceId || '').replace(/[^a-zA-Z0-9_-]/g, '')
  const id = safeId(req.params.id || '')
  if (!deviceId || !id) return res.status(400).json({ error: 'invalid' })
  const dir = deviceDir(deviceId)
  const manifestPath = path.join(dir, `${id}.json`)
  if (!fs.existsSync(manifestPath)) return res.status(404).json({ error: 'not found' })
  let m = {}
  try { m = JSON.parse(fs.readFileSync(manifestPath, 'utf8')) } catch (_) {}
  const dataPath = path.join(dir, `${id}__${(m.name || 'file').replace(/[^a-zA-Z0-9._-]/g, '_')}`)
  if (!fs.existsSync(dataPath)) return res.status(404).json({ error: 'data missing' })
  res.setHeader('Content-Type', m.mime || 'application/octet-stream')
  res.setHeader('Content-Length', fs.statSync(dataPath).size)
  res.setHeader('Content-Disposition', `inline; filename="${m.name || id}"`)
  if (req.query.download === '1') res.setHeader('Content-Disposition', `attachment; filename="${m.name || id}"`)
  fs.createReadStream(dataPath).pipe(res)
}

export function deleteFileRoute(req, res) {
  const deviceId = String(req.params.deviceId || '').replace(/[^a-zA-Z0-9_-]/g, '')
  const id = safeId(req.params.id || '')
  if (!deviceId || !id) return res.status(400).json({ error: 'invalid' })
  const dir = deviceDir(deviceId)
  const manifestPath = path.join(dir, `${id}.json`)
  if (!fs.existsSync(manifestPath)) return res.status(404).json({ error: 'not found' })
  let m = {}
  try { m = JSON.parse(fs.readFileSync(manifestPath, 'utf8')) } catch (_) {}
  const dataPath = path.join(dir, `${id}__${(m.name || 'file').replace(/[^a-zA-Z0-9._-]/g, '_')}`)
  try { fs.unlinkSync(dataPath) } catch (_) {}
  try { fs.unlinkSync(manifestPath) } catch (_) {}
  try { fs.rmSync(path.join(dir, id + '.partial'), { force: true }) } catch (_) {}
  try { db.ref('devices/'+deviceId+'/files/'+id).remove() } catch (_) {}
  res.json({ success: true })
}
