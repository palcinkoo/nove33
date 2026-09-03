// Drop-in patch for the v3.1.0 server. Apply with:
//
//   import { mountExtensions } from './mount.js'
//   mountExtensions(app, { verifyUser, sanitizeDeviceId, db, ...})
//
//   // in server/index.js, just before the dashboard dist static handler
//
import { registerExtended } from './routes/extended.js'
import { handleFileMessage, listFilesRoute, downloadFileRoute, deleteFileRoute } from './routes/files.js'
import { registerOta } from './routes/ota.js'
import { registerStream } from './routes/stream.js'
import express from 'express'

export function mountExtensions(app, deps) {
  // Body parser for the binary stream-chunk endpoint. 50 MB cap is enough
  // for a 5-10s 1080p H.264 chunk at 4 Mbps.
  const rawParser = express.raw({ type: 'application/octet-stream', limit: '50mb' })
  app.use('/api/v2/', (req, res, next) => {
    if (req.method === 'POST' && req.path.endsWith('/stream/chunk')) return rawParser(req, res, next)
    next()
  })

  registerExtended(app, deps)
  registerOta(app)
  registerStream(app)

  app.get('/api/v2/devices/:deviceId/files', deps.verifyUser, listFilesRoute)
  app.get('/api/v2/devices/:deviceId/files/:id', deps.verifyUser, downloadFileRoute)
  app.delete('/api/v2/devices/:deviceId/files/:id', deps.verifyUser, deleteFileRoute)

  console.log('Nove extensions mounted: cmd, results, live (SSE), files, ota, stream')
  return { handleFileMessage }
}
