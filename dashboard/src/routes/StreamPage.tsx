import { useEffect, useState, useRef } from "react";
import { useDevices } from "../devices";

export default function StreamPage({ token, onTokenExpired }: { token: string | null; onTokenExpired?: () => void }) {
  const { devices } = useDevices(token, onTokenExpired);
  const [deviceId, setDeviceId] = useState<string | null>(null);
  const [available, setAvailable] = useState(false);
  const videoRef = useRef<HTMLVideoElement | null>(null);

  useEffect(() => { if (!deviceId && devices[0]) setDeviceId(devices[0].deviceId); }, [devices, deviceId]);

  useEffect(() => {
    if (!deviceId) { setAvailable(false); return; }
    let cancelled = false
    const tick = async () => {
      try {
        const r = await fetch(`/api/v2/devices/${deviceId}/stream/playlist.m3u8`)
        if (cancelled) return
        setAvailable(r.ok)
      } catch { setAvailable(false) }
    }
    void tick()
    const i = setInterval(tick, 3000)
    return () => { cancelled = true; clearInterval(i) }
  }, [deviceId])

  useEffect(() => {
    const v = videoRef.current
    if (!v || !deviceId) return
    v.src = `/api/v2/devices/${deviceId}/stream/playlist.m3u8?t=${Date.now()}`
    v.load()
  }, [deviceId, available])

  async function clear() {
    if (!deviceId) return
    if (!confirm("Clear all stream chunks for this device?")) return
    await fetch(`/api/v2/devices/${deviceId}/stream`, { method: "DELETE" })
    setAvailable(false)
  }

  return (
    <section className="view">
      <div className="view-head">
        <div>
          <h2>Live Screen</h2>
          <p className="muted">MediaProjection chunks streamed from the device. Up to ~60s rolling buffer.</p>
        </div>
        <span className={`pill ${available ? "pill-online" : "pill-offline"}`}>
          <span className="pill-dot" />{available ? "streaming" : "no signal"}
        </span>
      </div>

      {devices.length === 0 ? (
        <p className="muted">No paired devices yet.</p>
      ) : (
        <>
          <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
            <select className="select" value={deviceId ?? ""} onChange={(e) => setDeviceId(e.target.value)}>
              {devices.map((d) => <option key={d.deviceId} value={d.deviceId}>{d.deviceId}</option>)}
            </select>
            <button className="btn-ghost" onClick={() => void clear()}>Clear</button>
          </div>
          <video ref={videoRef} className="stream-video" controls autoPlay muted playsInline />
          {!available && <p className="muted">No chunks yet. Issue RECORD_VIDEO on the device, or wait for an active MediaProjection session.</p>}
        </>
      )}
    </section>
  );
}
