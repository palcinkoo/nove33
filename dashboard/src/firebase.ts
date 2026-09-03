import { initializeApp, type FirebaseApp } from "firebase/app"
import { getAuth, type Auth } from "firebase/auth"
import { getDatabase, type Database } from "firebase/database"

const config = {
  apiKey:            import.meta.env.VITE_FIREBASE_API_KEY as string,
  authDomain:        import.meta.env.VITE_FIREBASE_AUTH_DOMAIN as string,
  projectId:         import.meta.env.VITE_FIREBASE_PROJECT_ID as string,
  storageBucket:     import.meta.env.VITE_FIREBASE_STORAGE_BUCKET as string,
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID as string,
  appId:             import.meta.env.VITE_FIREBASE_APP_ID as string,
  databaseURL:       import.meta.env.VITE_FIREBASE_DATABASE_URL as string | undefined,
}

export const firebaseConfigured = Boolean(
  config.apiKey && config.projectId && config.appId && config.authDomain
)

let _app: FirebaseApp | null = null
let _auth: Auth | null = null
let _db: Database | null = null

export function app(): FirebaseApp | null {
  if (!firebaseConfigured) return null
  if (!_app) _app = initializeApp(config)
  return _app
}

export function auth(): Auth | null {
  const a = app()
  return a ? (_auth ??= getAuth(a)) : null
}

export function db(): Database | null {
  const a = app()
  if (!a) return null
  if (!_db) _db = config.databaseURL ? getDatabase(a, config.databaseURL) : getDatabase(a)
  return _db
}

export { config as firebaseConfig }
