import { admin } from './firebase.js'

export const sanitizeDeviceId = (id) => {
  if (!id || typeof id !== 'string') return null
  const clean = id.replace(/[^a-zA-Z0-9_-]/g, '').substring(0, 64)
  return clean.length > 0 ? clean : null
}

export const sanitizeUserId = (id) => {
  if (!id || typeof id !== 'string') return null
  return id.substring(0, 128)
}

export const verifyUser = async (req, res, next) => {
  try {
    const token = req.headers.authorization?.split('Bearer ')[1]
    if (!token) return res.status(401).json({ error: 'no token' })
    const decoded = await admin.auth().verifyIdToken(token)
    req.uid = decoded.uid
    next()
  } catch (e) { res.status(403).json({ error: 'invalid token' }) }
}
