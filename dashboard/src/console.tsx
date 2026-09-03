import { useEffect, useMemo, useState } from "react"
import { firebaseConfigured } from "./firebase"
import { fmtUptime, fmtRelativeTime } from "./format"

const API_BASE = (import.meta.env.VITE_API_BASE as string) || ""

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

type BackendStatus = { status: string; version: string; uptime?: number }

type ConsoleProps = {
  token: string
  user: { uid: string; email?: string | null }
  status: BackendStatus | null
  onTokenExpired: () => void
}

type Tab = "overview" | "devices" | "commands" | "live"

export function Console({ token, user, status, onTokenExpired }: ConsoleProps) {
  const [devices, setDevices] = useState<Device[]>([])
  const [err, setErr] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [tab, setTab] = useState<Tab>("overview")

  useEffect(() => {
    let cancel = false
    ;(async () => {
      try {
        setLoading(true)
        const r = await fetch(`${API_BASE}/api/v2/devices`, {
          headers: { Authorization: `Bearer ${token}` },
        })
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
  }, [token, onTokenExpired])

  const stats = useMemo(() => {
    const online = devices.filter((d) => d.status === "active").length
    const offline = devices.length - online
    const avgBattery = devices.length
      ? Math.round(devices.reduce((s, d) => s + (typeof d.battery === "number" ? d.battery : 0), 0) / devices.length)
      : 0
    return { online, offline, total: devices.length, avgBattery }
  }, [devices])

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">N</span>
          <div>
            <h1>Nove</h1>
            <p>Device monitoring</p>
          </div>
        </div>

        <nav className="nav">
          <button className={`nav-item ${tab === "overview" ? "active" : ""}`} onClick={() => setTab("overview")}>
            <Icon name="grid" /> Overview
          </button>
          <button className={`nav-item ${tab === "devices" ? "active" : ""}`} onClick={() => setTab("devices")}>
            <Icon name="phone" /> Devices
            {devices.length > 0 && <span className="badge">{devices.length}</span>}
          </button>
          <button className={`nav-item ${tab === "commands" ? "active" : ""}`} onClick={() => setTab("commands")}>
            <Icon name="terminal" /> Commands
          </button>
          <button className={`nav-item ${tab === "live" ? "active" : ""}`} onClick={() => setTab("live")}>
            <Icon name="activity" /> Live
          </button>
        </nav>

        <div className="sidebar-footer">
          <div className="user-chip" title={user.email ?? user.uid}>
            <div className="avatar">{(user.email ?? user.uid).slice(0, 1).toUpperCase()}</div>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontWeight: 600, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                {user.email ?? "Signed in"}
              </div>
              <div className="email">{user.uid.slice(0, 16)}…</div>
            </div>
          </div>
          <button className="btn btn-ghost" onClick={onTokenExpired}>Sign out</button>
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <h2 style={{ textTransform: "capitalize" }}>{tab}</h2>
          <div className="right">
            <div className="indicator">
              <span className={`dot ${status?.status === "online" ? "on" : "off"}`} />
              API · {status?.status ?? "…"} · {fmtUptime(status?.uptime)}
            </div>
          </div>
        </header>

        <div className="content">
          {tab === "overview" && <Overview stats={stats} devices={devices} loading={loading} err={err} />}
          {tab === "devices" && <Devices devices={devices} loading={loading} err={err} />}
          {tab === "commands" && <CommandPalette token={token} devices={devices} />}
          {tab === "live" && <LiveConsole token={token} devices={devices} />}
        </div>
      </main>
    </div>
  )
}

function Overview({ stats, devices, loading, err }: { stats: any; devices: Device[]; loading: boolean; err: string | null }) {
  return (
    <>
      <div className="grid grid-4" style={{ marginBottom: 24 }}>
        <StatCard title="Total devices" value={loading ? "—" : stats.total} trend={loading ? "loading…" : `${stats.online} active`} />
        <StatCard title="Online" value={loading ? "—" : stats.online} trend={stats.total ? `${Math.round((stats.online / stats.total) * 100)}% fleet` : "—"} accent="success" />
        <StatCard title="Offline" value={loading ? "—" : stats.offline} trend="needs attention" accent={stats.offline > 0 ? "warning" : undefined} />
        <StatCard title="Avg battery" value={loading ? "—" : `${stats.avgBattery}%`} trend="across all devices" />
      </div>

      <h3 className="section-title">Recent devices</h3>
      {loading ? (
        <div className="empty">Loading devices…</div>
      ) : err ? (
        <div className="empty">Error: {err}</div>
      ) : devices.length === 0 ? (
        <div className="empty">
          No paired devices yet. Build the Android client (<code>make android-debug</code>) and pair one — it will show up here.
        </div>
      ) : (
        <div className="dev-list">
          {devices.slice(0, 5).map((d) => (
            <DeviceRow key={d.deviceId} d={d} />
          ))}
        </div>
      )}
    </>
  )
}

function StatCard({ title, value, trend, accent }: { title: string; value: any; trend?: string; accent?: "success" | "warning" }) {
  return (
    <div className="card">
      <div className="card-title">{title}</div>
      <div className="card-value" style={{ color: accent === "success" ? "var(--success)" : accent === "warning" ? "var(--warning)" : undefined }}>
        {value}
      </div>
      {trend && <div className="card-trend">{trend}</div>}
    </div>
  )
}

function Devices({ devices, loading, err }: { devices: Device[]; loading: boolean; err: string | null }) {
  if (loading) return <div className="empty">Loading…</div>
  if (err) return <div className="empty">Error: {err}</div>
  if (devices.length === 0) {
    return (
      <div className="empty">
        No paired devices. Once the Android client pairs with this server, it will appear here.
      </div>
    )
  }
  return (
    <div className="dev-list">
      {devices.map((d) => <DeviceRow key={d.deviceId} d={d} />)}
    </div>
  )
}

function DeviceRow({ d }: { d: Device }) {
  const bat = typeof d.battery === "number" ? d.battery : null
  const status = (d.status ?? "unknown") as string
  return (
    <div className="dev-row">
      <div>
        <div className="dev-id">{d.deviceId}</div>
        <div className="dev-meta">interval {d.interval ?? "—"}s · last seen {fmtRelativeTime(d.lastSeen ?? undefined)}</div>
      </div>
      <div className="battery">
        <div className="bar"><div className="fill" style={{ width: `${bat ?? 0}%` }} /></div>
        <span className="v">{bat != null ? `${bat}%` : "—"}</span>
      </div>
      <span className={`status-pill-sm ${status}`}><span className="d" />{status}</span>
      <button className="btn btn-ghost" style={{ padding: "6px 12px" }} disabled>Manage</button>
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
    if (!deviceId) { setResult("Pick a device first"); return }
    setBusy(true); setResult(null)
    try {
      const parsed = args.trim() ? JSON.parse(args) : {}
      const r = await fetch(`${API_BASE}/api/v2/devices/${encodeURIComponent(deviceId)}/cmd`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
        body: JSON.stringify({ command, args: parsed }),
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
    <>
      <div className="card" style={{ maxWidth: 560 }}>
        <h3 className="section-title" style={{ marginTop: 0 }}>Send command</h3>
        <div className="form">
          <div className="field">
            <label>Device</label>
            <select className="input" value={deviceId} onChange={(e) => setDeviceId(e.target.value)}>
              <option value="">— select —</option>
              {devices.map((d) => <option key={d.deviceId} value={d.deviceId}>{d.deviceId}</option>)}
            </select>
          </div>
          <div className="field">
            <label>Command</label>
            <select className="input" value={command} onChange={(e) => setCommand(e.target.value)}>
              <option value="ping">ping</option>
              <option value="get_device_info">get_device_info</option>
              <option value="list_apps">list_apps</option>
              <option value="screencap">screencap</option>
              <option value="location">location</option>
            </select>
          </div>
          <div className="field">
            <label>Arguments (JSON)</label>
            <textarea className="input" rows={3} value={args} onChange={(e) => setArgs(e.target.value)} />
          </div>
          <button className="btn btn-primary" onClick={send} disabled={busy} type="button">
            {busy && <span className="spinner" />} {busy ? "Sending…" : "Send command"}
          </button>
        </div>
      </div>
      {result && (
        <>
          <h3 className="section-title" style={{ marginTop: 24 }}>Response</h3>
          <pre className="output">{result}</pre>
        </>
      )}
    </>
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
    e.onerror = () => setEvents((arr) => [`[SSE disconnected]`, ...arr])
    setEs(e)
    setDeviceId(id)
  }
  const stop = () => { es?.close(); setEs(null) }

  return (
    <>
      <div className="card" style={{ maxWidth: 560 }}>
        <h3 className="section-title" style={{ marginTop: 0 }}>Live results (SSE)</h3>
        <div className="form">
          <div className="field">
            <label>Device</label>
            <select className="input" value={deviceId} onChange={(e) => setDeviceId(e.target.value)}>
              <option value="">— select —</option>
              {devices.map((d) => <option key={d.deviceId} value={d.deviceId}>{d.deviceId}</option>)}
            </select>
          </div>
          <div style={{ display: "flex", gap: 8 }}>
            <button className="btn btn-primary" type="button" onClick={() => start(deviceId)} disabled={!!es}>Start</button>
            <button className="btn" type="button" onClick={stop} disabled={!es}>Stop</button>
          </div>
        </div>
      </div>
      <h3 className="section-title" style={{ marginTop: 24 }}>Events</h3>
      <div className="live-events">
        {events.length === 0
          ? <div className="empty">No events yet. Start the stream above.</div>
          : events.map((e, i) => <div className="ev" key={i}>{e}</div>)}
      </div>
    </>
  )
}

function Icon({ name }: { name: "grid" | "phone" | "terminal" | "activity" }) {
  const common = { width: 16, height: 16, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round" as const, strokeLinejoin: "round" as const }
  switch (name) {
    case "grid": return <svg {...common}><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>
    case "phone": return <svg {...common}><rect x="5" y="2" width="14" height="20" rx="2"/><line x1="12" y1="18" x2="12" y2="18"/></svg>
    case "terminal": return <svg {...common}><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>
    case "activity": return <svg {...common}><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
  }
}
