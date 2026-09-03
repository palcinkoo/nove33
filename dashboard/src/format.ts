export function fmtUptime(seconds?: number): string {
  if (seconds == null || !Number.isFinite(seconds)) return "—"
  const s = Math.max(0, Math.floor(seconds))
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  if (d) return `${d}d ${h}h ${m}m`
  if (h) return `${h}h ${m}m ${sec}s`
  if (m) return `${m}m ${sec}s`
  return `${sec}s`
}

export function fmtBytes(n?: number): string {
  if (n == null || !Number.isFinite(n)) return "—"
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
}

export function fmtRelativeTime(ms?: number): string {
  if (!ms) return "—"
  const diff = Date.now() - ms
  if (diff < 60_000) return `${Math.floor(diff / 1000)}s ago`
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`
  return `${Math.floor(diff / 86_400_000)}d ago`
}
