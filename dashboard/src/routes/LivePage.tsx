import { useEffect, useMemo, useRef, useState } from "react";
import { useDevices } from "../devices";

type Result = { ts: number; type?: string; ok?: boolean; error?: string; [k: string]: any };

export default function LivePage({ token, onTokenExpired }: { token: string | null; onTokenExpired?: () => void }) {
  const { devices, loading } = useDevices(token, onTokenExpired);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  useEffect(() => { if (!selectedId && devices[0]) setSelectedId(devices[0].deviceId); }, [devices, selectedId]);
  const [results, setResults] = useState<Result[]>([]);
  const [connected, setConnected] = useState(false);
  const sseRef = useRef<EventSource | null>(null);

  useEffect(() => {
    if (!token || !selectedId) return;
    setResults([]);
    const url = `/api/v2/devices/${selectedId}/live`;
    const es = new EventSource(url, { withCredentials: false } as any);
    sseRef.current = es;
    es.addEventListener("hello", () => setConnected(true));
    es.addEventListener("result", (ev: MessageEvent) => {
      try {
        const r = JSON.parse(ev.data);
        setResults((prev) => [r, ...prev].slice(0, 200));
      } catch {}
    });
    es.addEventListener("error", () => setConnected(false));
    return () => { es.close(); sseRef.current = null; };
  }, [token, selectedId]);

  const hist = useMemo(() => results.slice(0, 50), [results]);

  return (
    <section className="view">
      <div className="view-head">
        <div>
          <h2>Live Console</h2>
          <p className="muted">Server-Sent Events stream of command results from the device</p>
        </div>
        <span className={`pill ${connected ? "pill-online" : "pill-offline"}`}>
          <span className="pill-dot" />{connected ? "Connected" : "Disconnected"}
        </span>
      </div>

      {devices.length === 0 ? (
        <p className="muted">No paired devices yet.</p>
      ) : (
        <>
          <select className="select" value={selectedId ?? ""} onChange={(e) => setSelectedId(e.target.value)}>
            {devices.map((d) => <option key={d.deviceId} value={d.deviceId}>{d.deviceId}</option>)}
          </select>

          <div className="console-stream" style={{ marginTop: 16 }}>
            {hist.length === 0 && <p className="muted">No events yet — issue a command from the Command Palette.</p>}
            {hist.map((r, i) => (
              <div key={i} className={`stream-line ${r.ok ? "ok" : "err"}`}>
                <span className="ts">{new Date(r.ts).toLocaleTimeString()}</span>
                <span className="kind">{r.type || "result"}</span>
                <code className="payload">{JSON.stringify(r).slice(0, 320)}</code>
              </div>
            ))}
          </div>
        </>
      )}
    </section>
  );
}
