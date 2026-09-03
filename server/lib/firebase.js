// Single source of truth for the Firebase Admin init, exported as a lazy proxy
// so it is initialised exactly once regardless of which route module imports
// it first.
import firebaseAdmin from 'firebase-admin'
import path from 'node:path'
import fs from 'node:fs'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

async function loadEnv() {
  for (const f of [
    path.join(__dirname, '..', '.env'),
    path.join(__dirname, '..', '..', '.env'),
    path.join(process.cwd(), '.env')
  ]) { if (fs.existsSync(f)) { (await import('dotenv')).default.config({ path: f }) } }
}

let _inited = false
let _db = null
let _admin = null

export async function initFirebase() {
  if (_inited) return { admin: firebaseAdmin, db: _db }
  await loadEnv()
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT_JSON
  let serviceAccount
  try {
    serviceAccount = raw ? JSON.parse(raw) : {
      project_id: process.env.FIREBASE_PROJECT_ID,
      client_email: process.env.FIREBASE_CLIENT_EMAIL,
      private_key: (process.env.FIREBASE_PRIVATE_KEY || '').replace(/\\n/g, '\n')
    }
  } catch (e) {
    console.error('Invalid FIREBASE_SERVICE_ACCOUNT_JSON', e.message)
    process.exit(1)
  }
  if (!serviceAccount.project_id || !process.env.FIREBASE_DATABASE_URL) {
    console.error('Firebase configuration missing')
    process.exit(1)
  }
  if (!firebaseAdmin.apps.length) {
    firebaseAdmin.initializeApp({ credential: firebaseAdmin.credential.cert(serviceAccount), databaseURL: process.env.FIREBASE_DATABASE_URL })
  }
  _admin = firebaseAdmin
  _db = firebaseAdmin.database()
  _inited = true
  return { admin: firebaseAdmin, db: _db }
}

export const admin = new Proxy({}, { get: (_, p) => { if (!_admin) throw new Error('firebase not initialised yet'); return _admin[p] } })
export const db = new Proxy({}, { get: (_, p) => { if (!_db) throw new Error('firebase not initialised yet'); return _db[p] } })
