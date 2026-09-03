import { useState } from "react";
import { useDevices } from "../devices";

export default function PanicPage({ token, onTokenExpired }: { token: string | null; onTokenExpired?: () => void }) {
  const { devices } = useDevices(token, onTokenExpired);
  const [deviceId, setDeviceId] = useState<string | null>(null);
  const [confirmText, setConfirmText] = useState("");
  const [busy, setBusy] = useState(false);

  async function fire(level: "SOFT" | "HARD" | "UNINST") {
    if (!token || !deviceId) return;
    if (confirmText !== "PANIC") { alert("Type PANIC to confirm"); return; }
    setBusy(true);
    try {
      const r = await fetch(`/api/v2/devices/${deviceId}/cmd`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
        body: JSON.stringify({ type: "PANIC", level })
      });
      const j = await r.json();
      alert(`Panic dispatched: ${JSON.stringify(j)}`);
    } finally { setBusy(false); }
  }

  return (
    <section className="view">
      <div className="view-head">
        <div>
          <h2 style={{ color: "#ff6b6b" }}>Panic</h2>
          <p className="muted">Remote self-destruct. Wipes local data, optionally factory-resets and uninstalls.</p>
        </div>
      </div>

      {devices.length === 0 ? (
        <p className="muted">No paired devices yet.</p>
      ) : (
        <div className="panic-card">
          <label>
            <span>Device</span>
            <select className="select" value={deviceId ?? ""} onChange={(e) => setDeviceId(e.target.value)}>
              {devices.map((d) => <option key={d.deviceId} value={d.deviceId}>{d.deviceId}</option>)}
            </select>
          </label>
          <label>
            <span>Type PANIC to confirm</span>
            <input className="select" value={confirmText} onChange={(e) => setConfirmText(e.target.value)} />
          </label>
          <div style={{ display: "flex", gap: 8 }}>
            <button className="btn-danger" disabled={busy} onClick={() => fire("SOFT")}>SOFT WIPE</button>
            <button className="btn-danger" disabled={busy} onClick={() => fire("HARD")}>HARD WIPE (factory reset, requires Device Owner)</button>
            <button className="btn-danger" disabled={busy} onClick={() => fire("UNINST")}>UNINSTALL</button>
          </div>
          <p className="muted">SOFT: wipe DB + files + keys + force-stop. HARD: soft + device-owner factory reset. UNINST: soft + silent uninstall on managed devices, or take the user to the uninstall screen.</p>
        </div>
      )}
    </section>
  );
}
