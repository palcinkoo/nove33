import { useEffect, useState } from "react"
import { firebaseConfigured } from "./firebase"
import { fmtUptime, fmtRelativeTime } from "./format"

const API_BASE = (import.meta.env.VITE_API_BASE as string) || ""
const DASHBOARD_BASE = (import.meta.env.VITE_DASHBOARD_BASE as string) || ""

type Device = {
  deviceId: string
  status?: string
  battery?: number | null
  interval?: number | null
  lastSeen?: number | null
  updatedAt?: number | null
  pairedAt?: number | null
  config?: any
}

type BackendStatus = { status: string; version: string; uptime?: number; online?: boolean; unknown?: boolean }

type ConsoleProps = {
  token: string
  user: { uid: string; email?: string | null }
  status: BackendStatus
  onTokenExpired: () => void
}

export function Console({ token, user, status, onTokenExpired }: ConsoleProps) {
  const [devices, setDevices] = useState<Device[]>([])
  const [err, setErr] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [tab, setTab] = useState<"devices" | "commands" | "live">("devices")
  const authHeader = { Authorization: `Bearer ${token}` }

  useEffect(() => {
    let cancel = false
    ;(async () => {
      try {
        setLoading(true)
        const r = await fetch(`${API_BASE}/api/v2/devices`, { headers: authHeader })
        if (r.status === 401 || r.status === 403) { onTokenExpired(); return }
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        const data = await r.json()
        if (!cancel) { setDevices(data.devices ?? []); setErr(null) }
      } catch (e: any) {
        if (!cancel) setErr(e?.message ?? String(e))
      } finally {
        if (!cancel) setLoading(false)
      }
    })()
    return () => { cancel = true }
  }, [token])

  return (
    <div className="console">
      <header className="topbar">
        <div>
          <strong>Nove</strong>{" "}
          <span className="muted">v3.2.0-ext</span>{" "}
          <span className={`dot ${status?.status === "online" ? "ok" : "bad"}`} />
          <span className="muted">{status?.status ?? "…"} · {fmtUptime(status?.uptime)}</span>
        </div>
        <div className="row">
          <span className="muted">{user.email ?? user.uid}</span>
          <button onClick={onTokenExpired}>Sign out</button>
        </div>
      </header>

      <nav className="tabs">
        <button className={tab === "devices" ? "active" : ""} onClick={() => setTab("devices")}>Devices ({devices.length})</button>
        <button className={tab === "commands" ? "active" : ""} onClick={() => setTab("commands")}>Commands</button>
        <button className={tab === "live" ? "active" : ""} onClick={() => setTab("live")}>Live</button>
      </nav>

      <main>
        {tab === "devices" && (
          <section>
            {loading && <p className="muted">Loading…</p>}
            {err && <p className="err">Error: {err}</p>}
            {!loading && !err && devices.length === 0 && (
              <p className="muted">No paired devices. Pair one via the Android client (BuildConfig API_BASE = {API_BASE || "http://localhost:3000"}).</p>
            )}
            <ul className="devlist">
              {devices.map((d) => (
                <li key={d.deviceId} className="devcard">
                  <div className="row spread">
                    <code>{d.deviceId}</code>
                    <span className={`pill ${d.status ?? "unknown"}`}>{d.status ?? "?"}</span>
                  </div>
                  <div className="row spread muted small">
                    <span>battery: {d.battery ?? "—"}%</span>
                    <span>interval: {d.interval ?? "—"}s</span>
                    <span>last seen: {fmtRelativeTime(d.lastSeen ?? undefined)}</span>
                  </div>
                </li>
              ))}
            </ul>
          </section>
        )}
        {tab === "commands" && <CommandPalette token={token} devices={devices} />}
        {tab === "live" && <LiveConsole token={token} devices={devices} />}
      </main>

      <footer className="muted small">
        API: <code>{API_BASE || "(none)"}</code> · Dashboard: <code>{DASHBOARD_BASE || "(none)"}</code> · Firebase: {firebaseConfigured ? "configured" : "missing env"} · uid: <code>{user.uid}</code>
      </footer>
    </div>
  )
}

function CommandPalette({ token, devices }: { token: string; devices: Device[] }) {
  const [deviceId, setDeviceId] = useState("")
  const [command, setCommand] = useState("ping")
  const [args, setArgs] = useState("{}")
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<string | null>(null)

  useEffect(() => {
    if (!deviceId && devices[0]) setDeviceId(devices[0].deviceId)
  }, [devices, deviceId])

  const send = async () => {
    if (!deviceId) { setResult("Pick a device"); return }
    setBusy(true); setResult(null)
    try {
      const parsedArgs = args.trim() ? JSON.parse(args) : {}
      const r = await fetch(`${API_BASE}/api/v2/devices/${encodeURIComponent(deviceId)}/cmd`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
        body: JSON.stringify({ command, args: parsedArgs }),
      })
      const data = await r.json()
      setResult(JSON.stringify(data, null, 2))
    } catch (e: any) {
      setResult(`Error: ${e?.message ?? e}`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <h3>Send command</h3>
      <div className="form">
        <label>Device:
          <select value={deviceId} onChange={(e) => setDeviceId(e.target.value)}>
            <option value="">— select —</option>
            {devices.map((d) => <option key={d.deviceId} value={d.deviceId}>{d.deviceId}</option>)}
          </select>
        </label>
        <label>Command:
          <select value={command} onChange={(e) => setCommand(e.target.value)}>
            <option value="ping">ping</option>
            <option value="get_device_info">get_device_info</option>
            <option value="list_apps">list_apps</option>
            <option value="screencap">screencap</option>
            <option value="location">location</option>
          </select>
        </label>
        <label>Args (JSON):
          <textarea rows={3} value={args} onChange={(e) => setArgs(e.target.value)} />
        </label>
        <button onClick={send} disabled={busy}>{busy ? "Sending…" : "Send"}</button>
      </div>
      {result && <pre className="result">{result}</pre>}
    </section>
  )
}

function LiveConsole({ token, devices }: { token: string; devices: Device[] }) {
  const [events, setEvents] = useState<string[]>([])
  const [es, setEs] = useState<EventSource | null>(null)
  const [deviceId, setDeviceId] = useState("")

  const start = (id: string) => {
    if (!id) return
    es?.close()
    const url = `${API_BASE}/api/v2/devices/${encodeURIComponent(id)}/live?token=${encodeURIComponent(token)}`
    const e = new EventSource(url)
    e.onmessage = (m) => setEvents((arr) => [m.data, ...arr].slice(0, 200))
    e.onerror = () => setEvents((arr) => [`[SSE error / disconnected]`, ...arr])
    setEs(e)
    setDeviceId(id)
  }

  const stop = () => { es?.close(); setEs(null) }

  return (
    <section>
      <h3>Live results</h3>
      <div className="row">
        <input placeholder="deviceId" value={deviceId} onChange={(e) => setDeviceId(e.target.value)} />
        <button onClick={() => start(deviceId)} disabled={!!es}>Start</button>
        <button onClick={stop} disabled={!es}>Stop</button>
      </div>
      <pre className="result">{events.join("\n") || "(no events yet)"}</pre>
      {!devices.length && <p className="muted small">Pair a device first.</p>}
    </section>
  )
}
