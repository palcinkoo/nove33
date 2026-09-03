import { useEffect, useState } from "react";
import { useDevices } from "../devices";

type OtaStatus = { success: boolean; current: { versionCode: number; versionName: string; sha256: string; sizeBytes: number; mandatory?: boolean; publishedAt?: number; changelog?: string; previousVersionCode?: number } | null; historyCount: number };

export default function OtaPage({ adminKey, onAdminKey }: { adminKey: string; onAdminKey: (k: string) => void }) {
  const { devices } = useDevices(null);
  const [status, setStatus] = useState<OtaStatus | null>(null);
  const [versionCode, setVersionCode] = useState("");
  const [versionName, setVersionName] = useState("");
  const [apkBase64, setApkBase64] = useState("");
  const [changelog, setChangelog] = useState("");
  const [mandatory, setMandatory] = useState(false);
  const [cohorts, setCohorts] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState("");

  async function refresh() {
    const r = await fetch("/api/v2/ota/status");
    if (r.ok) setStatus(await r.json());
  }
  useEffect(() => { void refresh(); }, []);

  async function publish() {
    if (!adminKey) { setMsg("Set X-Admin-Key first"); return; }
    if (!versionCode || !apkBase64) { setMsg("versionCode + apkBase64 required"); return; }
    setBusy(true); setMsg("");
    try {
      const r = await fetch("/api/v2/ota/publish", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Admin-Key": adminKey },
        body: JSON.stringify({
          versionCode: Number(versionCode),
          versionName: versionName || `v${versionCode}`,
          apkBase64,
          changelog,
          mandatory,
          cohorts: cohorts ? cohorts.split(",").map(s => s.trim()).filter(Boolean) : null
        })
      });
      const j = await r.json();
      setMsg(JSON.stringify(j, null, 2));
      if (r.ok) void refresh();
    } finally { setBusy(false); }
  }

  async function rollback() {
    if (!adminKey) return;
    setBusy(true); setMsg("");
    try {
      const r = await fetch("/api/v2/ota/rollback", { method: "POST", headers: { "X-Admin-Key": adminKey } });
      const j = await r.json();
      setMsg(JSON.stringify(j, null, 2));
      if (r.ok) void refresh();
    } finally { setBusy(false); }
  }

  return (
    <section className="view">
      <div className="view-head">
        <div>
          <h2>OTA Updates</h2>
          <p className="muted">Publish a new APK and roll out to paired devices. Devices poll on each heartbeat.</p>
        </div>
        <button className="btn-ghost" onClick={() => void refresh()}>Refresh</button>
      </div>

      <div className="ota-grid">
        <article className="ota-card">
          <h3>Current</h3>
          {status?.current ? (
            <ul>
              <li><strong>Version:</strong> {status.current.versionName} ({status.current.versionCode})</li>
              <li><strong>Size:</strong> {(status.current.sizeBytes / 1024 / 1024).toFixed(2)} MB</li>
              <li><strong>SHA-256:</strong> <code className="muted">{status.current.sha256.slice(0, 16)}…</code></li>
              <li><strong>Mandatory:</strong> {status.current.mandatory ? "yes" : "no"}</li>
              {status.current.publishedAt && <li><strong>Published:</strong> {new Date(status.current.publishedAt).toLocaleString()}</li>}
              {status.current.previousVersionCode && <li><strong>Previous:</strong> {status.current.previousVersionCode}</li>}
            </ul>
          ) : <p className="muted">No build published yet.</p>}
          <button className="btn-ghost" onClick={() => void rollback()}>Rollback to previous</button>
        </article>

        <article className="ota-card">
          <h3>Publish</h3>
          <label><span>Admin key</span>
            <input className="select" type="password" value={adminKey} onChange={(e) => onAdminKey(e.target.value)} placeholder="OTA_ADMIN_KEY" />
          </label>
          <label><span>versionCode</span>
            <input className="select" value={versionCode} onChange={(e) => setVersionCode(e.target.value)} placeholder="429" />
          </label>
          <label><span>versionName</span>
            <input className="select" value={versionName} onChange={(e) => setVersionName(e.target.value)} placeholder="4.1.3" />
          </label>
          <label><span>apkBase64 (the .apk as base64)</span>
            <textarea className="select" rows={4} value={apkBase64} onChange={(e) => setApkBase64(e.target.value)} placeholder="UEsDBAoAAAA…" />
          </label>
          <label><span>changelog</span>
            <textarea className="select" rows={2} value={changelog} onChange={(e) => setChangelog(e.target.value)} />
          </label>
          <label><input type="checkbox" checked={mandatory} onChange={(e) => setMandatory(e.target.checked)} /> Mandatory (no skip)</label>
          <label><span>Cohorts (comma-sep device ids, empty = all)</span>
            <input className="select" value={cohorts} onChange={(e) => setCohorts(e.target.value)} />
          </label>
          <button className="btn-primary" disabled={busy} onClick={() => void publish()}>{busy ? "Publishing…" : "Publish"}</button>
          {msg && <pre className="cmd-result">{msg}</pre>}
        </article>

        <article className="ota-card">
          <h3>Paired devices</h3>
          {devices.length === 0 ? <p className="muted">No devices.</p> : (
            <ul>{devices.map((d) => <li key={d.deviceId}><code>{d.deviceId}</code> · {d.status}</li>)}</ul>
          )}
          <p className="muted">History: {status?.historyCount ?? 0} entries</p>
        </article>
      </div>
    </section>
  );
}
