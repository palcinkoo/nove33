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
  const [ready, setReady] = useState(false)
  const a = fbAuth()
  useEffect(() => {
    if (!a) { setReady(true); return }
    return onAuthStateChanged(a, (u) => { setUser(u); setReady(true) })
  }, [a])
  return { user, ready, signedIn: !!user }
}

export function useSignIn() {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const signInEmail = useCallback(async (email: string, password: string) => {
    const a = fbAuth(); if (!a) { setError("Firebase not initialized"); return }
    setBusy(true); setError(null)
    try { await signInWithEmailAndPassword(a, email, password) }
    catch (e: any) { setError(e?.message ?? String(e)) }
    finally { setBusy(false) }
  }, [])

  const signUpEmail = useCallback(async (email: string, password: string) => {
    const a = fbAuth(); if (!a) { setError("Firebase not initialized"); return }
    setBusy(true); setError(null)
    try { await createUserWithEmailAndPassword(a, email, password) }
    catch (e: any) { setError(e?.message ?? String(e)) }
    finally { setBusy(false) }
  }, [])

  const signInGoogle = useCallback(async () => {
    const a = fbAuth(); if (!a) { setError("Firebase not initialized"); return }
    setBusy(true); setError(null)
    try { await signInWithPopup(a, new GoogleAuthProvider()) }
    catch (e: any) { setError(e?.message ?? String(e)) }
    finally { setBusy(false) }
  }, [])

  const signOutCurrent = useCallback(async () => {
    const a = fbAuth(); if (!a) return
    try { await signOut(a) } catch (e) { console.error(e) }
  }, [])

  return { busy, error, signInEmail, signUpEmail, signInGoogle, signOut: signOutCurrent }
}
