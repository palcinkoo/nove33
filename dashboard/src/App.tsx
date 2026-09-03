import { useEffect, useState } from "react"
import { useAuth, useSignIn } from "./auth"
import { firebaseConfigured } from "./firebase"
import { Console } from "./console"
import { fmtUptime } from "./format"

type BackendStatus = { status: string; version: string; uptime?: number }

const API_BASE = (import.meta.env.VITE_API_BASE as string) || ""

export default function App() {
  const { user, loading, token, refreshToken } = useAuth()
  const [status, setStatus] = useState<BackendStatus | null>(null)

  useEffect(() => {
    let cancel = false
    const check = async () => {
      try {
        const r = await fetch(`${API_BASE}/api/v2/status`)
        if (!r.ok) throw new Error("offline")
        const d = await r.json()
        if (!cancel) setStatus(d)
      } catch {
        if (!cancel) setStatus(null)
      }
    }
    void check()
    const t = setInterval(check, 30_000)
    return () => { cancel = true; clearInterval(t) }
  }, [])

  if (!firebaseConfigured) {
    return (
      <div className="auth-shell">
        <div className="auth-card">
          <div className="brand">
            <span className="brand-mark">N</span>
            <div>
              <h1>Nove</h1>
              <p>Device monitoring</p>
            </div>
          </div>
          <h3 className="auth-title">Configuration required</h3>
          <p className="auth-sub">Set VITE_FIREBASE_* environment variables in the Render dashboard and redeploy.</p>
        </div>
      </div>
    )
  }

  if (loading) {
    return (
      <div className="auth-shell">
        <div className="auth-card" style={{ display: "grid", placeItems: "center", padding: 60 }}>
          <div className="spinner" />
        </div>
      </div>
    )
  }

  if (!user) return <AuthScreen status={status} />
  return <Console token={token} user={user} status={status} onTokenExpired={refreshToken} />
}

function AuthScreen({ status }: { status: BackendStatus | null }) {
  const { busy, error, google, email, signOut } = useSignIn()
  const [mode, setMode] = useState<"in" | "up">("in")
  const [emailValue, setEmailValue] = useState("")
  const [password, setPassword] = useState("")

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!emailValue || !password) return
    void email(emailValue, password, mode)
  }

  return (
    <div className="auth-shell">
      <div className="auth-card">
        <div className="brand">
          <span className="brand-mark">N</span>
          <div>
            <h1>Nove</h1>
            <p>Device monitoring</p>
          </div>
        </div>

        <h2 className="auth-title">{mode === "in" ? "Welcome back" : "Create your account"}</h2>
        <p className="auth-sub">
          {mode === "in" ? "Sign in to monitor your paired devices." : "Set up access in seconds."}
        </p>

        <button className="btn btn-google" disabled={busy} onClick={() => void google()} type="button">
          <GoogleIcon /> Continue with Google
        </button>

        <div className="divider"><span>or with email</span></div>

        <form className="form" onSubmit={submit} noValidate>
          <div className="field">
            <label>Email</label>
            <input
              className="input"
              type="email"
              placeholder="you@example.com"
              value={emailValue}
              onChange={(e) => setEmailValue(e.target.value)}
              autoComplete="email"
              required
            />
          </div>
          <div className="field">
            <label>Password</label>
            <input
              className="input"
              type="password"
              placeholder="••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              minLength={6}
              autoComplete={mode === "in" ? "current-password" : "new-password"}
              required
            />
          </div>
          <button className="btn btn-primary" disabled={busy} type="submit">
            {busy && <span className="spinner" />}
            {busy ? "Working…" : mode === "in" ? "Sign in" : "Create account"}
          </button>
        </form>

        <div style={{ marginTop: 14, display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <button className="switch" type="button" onClick={() => setMode(mode === "in" ? "up" : "in")}>
            {mode === "in" ? "Need an account? Sign up" : "Already have one? Sign in"}
          </button>
        </div>

        {error && <div className="error">{error}</div>}

        <div className={`status-pill ${status?.status === "online" ? "online" : "offline"}`}>
          <span className="dot" />
          {status?.status === "online"
            ? <>Backend online · v{status.version ?? "?"} · {fmtUptime(status.uptime)}</>
            : "Backend offline"}
        </div>
      </div>
    </div>
  )
}

function GoogleIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 48 48" aria-hidden="true">
      <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
      <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
      <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
      <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
    </svg>
  )
}
