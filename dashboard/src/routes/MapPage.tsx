import { useEffect, useRef, useState } from "react";
import { useDevices } from "../devices";

type Loc = { latitude: number; longitude: number; accuracy: number; timestamp: number };

export default function MapPage({ token, onTokenExpired }: { token: string | null; onTokenExpired?: () => void }) {
  const { devices } = useDevices(token, onTokenExpired);
  const [deviceId, setDeviceId] = useState<string | null>(null);
  const [locations, setLocations] = useState<Loc[]>([]);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<any>(null);
  const markersRef = useRef<any[]>([]);

  useEffect(() => { if (!deviceId && devices[0]) setDeviceId(devices[0].deviceId); }, [devices, deviceId]);
  useEffect(() => { if (deviceId && token) void load(); }, [deviceId, token]);

  async function load() {
    if (!token || !deviceId) return;
    const r = await fetch(`/api/v2/devices/${deviceId}/modules?module=locations`, { headers: { Authorization: `Bearer ${token}` } });
    if (r.ok) { const j = await r.json(); setLocations((j.modules.locations || []).map((d: any) => ({ latitude: d.latitude, longitude: d.longitude, accuracy: d.accuracy, timestamp: d.timestamp }))); }
  }

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!containerRef.current) return;
      if (mapRef.current) return;
      const L = (await import("leaflet")).default;
      // CSS imported via index.css or via a CDN link in index.html
      if (cancelled) return;
      const m = L.map(containerRef.current).setView([0, 0], 2);
      L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { attribution: "© OpenStreetMap", maxZoom: 19 }).addTo(m);
      mapRef.current = { L, m };
    })();
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    const ref = mapRef.current; if (!ref) return;
    const { L, m } = ref;
    markersRef.current.forEach((mk) => m.removeLayer(mk));
    markersRef.current = [];
    if (locations.length === 0) return;
    const latlngs = locations.map((l) => [l.latitude, l.longitude] as [number, number]);
    locations.forEach((l, i) => {
      const mk = L.circleMarker([l.latitude, l.longitude], { radius: 6, color: i === 0 ? "#35d0a0" : "#4f8cff", fillOpacity: 0.6 }).bindPopup(`#${locations.length - i}<br>${new Date(l.timestamp).toLocaleString()}<br>±${(l.accuracy || 0).toFixed(0)} m`);
      mk.addTo(m);
      markersRef.current.push(mk);
    });
    m.fitBounds(L.latLngBounds(latlngs as any).pad(0.2));
  }, [locations]);

  return (
    <section className="view">
      <div className="view-head">
        <div>
          <h2>Locations</h2>
          <p className="muted">GPS samples captured by the device — most recent pin in green</p>
        </div>
        <span className="muted">{locations.length} point{locations.length === 1 ? "" : "s"}</span>
      </div>

      {devices.length === 0 ? (
        <p className="muted">No paired devices yet.</p>
      ) : (
        <>
          <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
            <select className="select" value={deviceId ?? ""} onChange={(e) => setDeviceId(e.target.value)}>
              {devices.map((d) => <option key={d.deviceId} value={d.deviceId}>{d.deviceId}</option>)}
            </select>
            <button className="btn-ghost" onClick={() => void load()}>Refresh</button>
          </div>
          <div ref={containerRef} className="map-container" />
        </>
      )}
    </section>
  );
}
