import { useEffect, useMemo, useState } from "react"
import { firebaseConfigured } from "./firebase"
import { fmtUptime, fmtRelativeTime } from "./format"
import { COMMANDS, defaultsFor, buildArgs, type FieldSpec } from "./commands"
import { PairPanel } from "./pair"

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
          {tab === "devices" && <Devices devices={devices} loading={loading} err={err} token={token} refresh={() => window.location.reload()} />}
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

function Devices({ devices, loading, err, token, refresh }: { devices: Device[]; loading: boolean; err: string | null; token: string; refresh: () => void }) {
  if (loading) return <div className="empty">Loading…</div>
  return (
    <>
      <div style={{ marginBottom: 18 }}>
        <PairPanel token={token} onPaired={refresh} />
      </div>
      {err && <div className="empty">Error: {err}</div>}
      {!err && devices.length === 0 && (
        <div className="empty">
          Žiadne spárované zariadenia. Použi panel vyššie.
        </div>
      )}
      {devices.length > 0 && (
        <div className="dev-list">
          {devices.map((d) => <DeviceRow key={d.deviceId} d={d} />)}
        </div>
      )}
    </>
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
  const [commandId, setCommandId] = useState("ping")
  const [values, setValues] = useState<Record<string, any>>({})
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<string | null>(null)

  const cmd = COMMANDS.find((c) => c.id === commandId)!

  useEffect(() => {
    if (!deviceId && devices[0]) setDeviceId(devices[0].deviceId)
  }, [devices, deviceId])

  useEffect(() => {
    setValues(defaultsFor(commandId))
    setResult(null)
  }, [commandId])

  const setVal = (k: string, v: any) => setValues((s) => ({ ...s, [k]: v }))

  const send = async () => {
    if (!deviceId) { setResult("Pick a device first"); return }
    if (commandId === "panic_wipe" && !values.confirm) {
      setResult("You must confirm the panic wipe by toggling 'I understand this is irreversible'."); return
    }
    setBusy(true); setResult(null)
    try {
      const args = buildArgs(commandId, values)
      const r = await fetch(`${API_BASE}/api/v2/devices/${encodeURIComponent(deviceId)}/cmd`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
        body: JSON.stringify({ command: commandId, args }),
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
    <div style={{ display: "grid", gridTemplateColumns: "260px 1fr", gap: 18 }} className="cmd-layout">
      <aside className="cmd-list">
        <div className="cmd-list-title">Commands</div>
        {COMMANDS.map((c) => (
          <button
            key={c.id}
            className={`cmd-list-item ${c.id === commandId ? "active" : ""} ${c.id === "panic_wipe" ? "danger" : ""}`}
            onClick={() => setCommandId(c.id)}
          >
            <span className="cmd-list-icon"><CmdIcon name={c.icon} /></span>
            <span>{c.label}</span>
          </button>
        ))}
      </aside>

      <div className="card cmd-form">
        <div className="cmd-form-head">
          <div className="cmd-form-title">
            <CmdIcon name={cmd.icon} />
            <div>
              <h3 style={{ margin: 0 }}>{cmd.label}</h3>
              <p className="muted" style={{ margin: "2px 0 0", fontSize: 13 }}>{cmd.description}</p>
            </div>
          </div>
        </div>

        <div className="form">
          <div className="field">
            <label>Target device</label>
            <select className="input" value={deviceId} onChange={(e) => setDeviceId(e.target.value)}>
              <option value="">— select —</option>
              {devices.map((d) => <option key={d.deviceId} value={d.deviceId}>{d.deviceId}</option>)}
            </select>
          </div>

          {cmd.fields.map((f) => <FieldInput key={f.key} field={f} value={values[f.key]} onChange={(v) => setVal(f.key, v)} />)}

          <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <button className={`btn ${commandId === "panic_wipe" ? "" : "btn-primary"}`}
                    onClick={send} disabled={busy}
                    style={commandId === "panic_wipe" ? { background: "var(--danger)", border: "none", color: "#fff" } : undefined}>
              {busy && <span className="spinner" />}
              {busy ? "Working…" : commandId === "panic_wipe" ? "Execute panic wipe" : `Run ${cmd.label.toLowerCase()}`}
            </button>
            <span className="muted" style={{ fontSize: 12 }}>Command <code>{commandId}</code></span>
          </div>
        </div>

        {result && (
          <div style={{ marginTop: 18 }}>
            <h4 className="section-title">Response</h4>
            <pre className="output">{result}</pre>
          </div>
        )}
      </div>
    </div>
  )
}

function FieldInput({ field, value, onChange }: { field: FieldSpec; value: any; onChange: (v: any) => void }) {
  switch (field.kind) {
    case "text":
      return (
        <div className="field">
          <label>{field.label}{field.required ? " *" : ""}</label>
          <input className="input" type="text" placeholder={field.placeholder}
                 value={value ?? ""} onChange={(e) => onChange(e.target.value)} required={field.required} />
        </div>
      )
    case "number":
      return (
        <div className="field">
          <label>{field.label}</label>
          <div className="num-row">
            <input className="input" type="number" min={field.min} max={field.max} step={field.step ?? 1}
                   value={value ?? ""} onChange={(e) => onChange(e.target.value === "" ? "" : Number(e.target.value))} />
            {field.suffix && <span className="muted" style={{ minWidth: 24 }}>{field.suffix}</span>}
          </div>
        </div>
      )
    case "select":
      return (
        <div className="field">
          <label>{field.label}</label>
          <select className="input" value={value ?? ""} onChange={(e) => onChange(e.target.value)}>
            {field.options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </div>
      )
    case "slider":
      return (
        <div className="field">
          <label>{field.label} <span className="muted" style={{ float: "right" }}>{value ?? 0}{field.suffix ?? ""}</span></label>
          <input className="slider" type="range" min={field.min} max={field.max} step={field.step ?? 1}
                 value={value ?? 0} onChange={(e) => onChange(Number(e.target.value))} />
        </div>
      )
    case "toggle":
      return (
        <div className="field">
          <label className="toggle-row">
            <span>{field.label}</span>
            <button type="button" className={`switch-pill ${value ? "on" : ""}`} onClick={() => onChange(!value)}>
              <span className="knob" />
            </button>
          </label>
        </div>
      )
    case "multiselect":
      return (
        <div className="field">
          <label>{field.label}</label>
          <div className="chip-row">
            {field.options.map((o) => {
              const arr = (value as string[]) ?? []
              const on = arr.includes(o.value)
              return (
                <button type="button" key={o.value} className={`chip ${on ? "on" : ""}`}
                        onClick={() => onChange(on ? arr.filter((v) => v !== o.value) : [...arr, o.value])}>
                  {o.label}
                </button>
              )
            })}
          </div>
        </div>
      )
  }
}

function CmdIcon({ name }: { name: string }) {
  const c = { width: 18, height: 18, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round" as const, strokeLinejoin: "round" as const }
  switch (name) {
    case "wifi": return <svg {...c}><path d="M5 12.55a11 11 0 0 1 14.08 0"/><path d="M1.42 9a16 16 0 0 1 21.16 0"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><line x1="12" y1="20" x2="12.01" y2="20"/></svg>
    case "info": return <svg {...c}><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
    case "grid": return <svg {...c}><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>
    case "image": return <svg {...c}><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
    case "map": return <svg {...c}><polygon points="1 6 1 22 8 18 16 22 23 18 23 2 16 6 8 2 1 6"/><line x1="8" y1="2" x2="8" y2="18"/><line x1="16" y1="6" x2="16" y2="22"/></svg>
    case "camera": return <svg {...c}><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg>
    case "mic": return <svg {...c}><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>
    case "users": return <svg {...c}><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
    case "message": return <svg {...c}><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
    case "phone": return <svg {...c}><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"/></svg>
    case "bell": return <svg {...c}><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>
    case "terminal": return <svg {...c}><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>
    case "alert": return <svg {...c}><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
    default: return <svg {...c}><circle cx="12" cy="12" r="9"/></svg>
  }
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
