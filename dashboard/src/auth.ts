import { useEffect, useState, useCallback } from "react"
import {
  signInWithEmailAndPassword,
  createUserWithEmailAndPassword,
  signOut,
  onAuthStateChanged,
  type User,
  GoogleAuthProvider,
  signInWithPopup,
} from "firebase/auth"
import { auth as fbAuth } from "./firebase"

export function useAuth() {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)
  const [token, setToken] = useState<string>("")
  const a = fbAuth()
  useEffect(() => {
    if (!a) { setLoading(false); return }
    let unsub = () => {}
    unsub = onAuthStateChanged(a, async (u) => {
      setUser(u)
      if (u) {
        try { setToken(await u.getIdToken()) } catch { setToken("") }
      } else {
        setToken("")
      }
      setLoading(false)
    })
    return () => unsub()
  }, [a])
  const refreshToken = useCallback(async () => {
    if (!a?.currentUser) return
    setToken(await a.currentUser.getIdToken(true))
  }, [a])
  return { user, loading, token, refreshToken }
}

export function useSignIn() {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const a = fbAuth()

  const email = useCallback(async (addr: string, password: string, mode: "in" | "up") => {
    if (!a) { setError("Firebase not initialized"); return }
    setBusy(true); setError(null)
    try {
      if (mode === "up") {
        await createUserWithEmailAndPassword(a, addr, password)
      } else {
        await signInWithEmailAndPassword(a, addr, password)
      }
    } catch (e: any) {
      setError(e?.message ?? String(e))
    } finally {
      setBusy(false)
    }
  }, [a])

  const google = useCallback(async () => {
    if (!a) { setError("Firebase not initialized"); return }
    setBusy(true); setError(null)
    try { await signInWithPopup(a, new GoogleAuthProvider()) }
    catch (e: any) { setError(e?.message ?? String(e)) }
    finally { setBusy(false) }
  }, [a])

  const signOutCurrent = useCallback(async () => {
    if (!a) return
    try { await signOut(a) } catch (e) { console.error(e) }
  }, [a])

  return { busy, error, google, email, signOut: signOutCurrent }
}
