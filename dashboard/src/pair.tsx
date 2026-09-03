import { useEffect, useState } from "react"

const API_BASE = (import.meta.env.VITE_API_BASE as string) || ""

export function PairPanel({ token, onPaired }: { token: string; onPaired: () => void }) {
  const [digits, setDigits] = useState<string[]>(["", "", "", "", "", ""])
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [ok, setOk] = useState<string | null>(null)
  const [timeLeft, setTimeLeft] = useState<number>(0)

  const code = digits.join("")

  useEffect(() => {
    if (timeLeft <= 0) return
    const t = setTimeout(() => setTimeLeft((s) => s - 1), 1000)
    return () => clearTimeout(t)
  }, [timeLeft])

  const setDigit = (i: number, v: string) => {
    const ch = v.replace(/\D/g, "").slice(-1)
    const next = [...digits]
    next[i] = ch
    setDigits(next)
    if (ch && i < 5) {
      const nextEl = document.getElementById(`pair-d-${i + 1}`)
      nextEl?.focus()
    }
  }

  const onKey = (i: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Backspace" && !digits[i] && i > 0) {
      const prev = document.getElementById(`pair-d-${i - 1}`)
      prev?.focus()
    }
  }

  const onPaste = (e: React.ClipboardEvent) => {
    const text = e.clipboardData.getData("text").replace(/\D/g, "").slice(0, 6)
    if (text.length === 6) {
      e.preventDefault()
      setDigits(text.split(""))
      document.getElementById("pair-submit")?.focus()
    }
  }

  const submit = async () => {
    if (code.length !== 6) { setErr("Kód musí mať 6 číslic"); return }
    setBusy(true); setErr(null); setOk(null)
    try {
      const r = await fetch(`${API_BASE}/api/v2/pair`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
        body: JSON.stringify({ pairing_code: code }),
      })
      const data = await r.json()
      if (!r.ok) throw new Error(data?.error ?? `HTTP ${r.status}`)
      setOk(`Spárované · ${data.deviceId ?? "device"}`)
      setDigits(["", "", "", "", "", ""])
      setTimeLeft(300) // 5 min
      onPaired()
    } catch (e: any) {
      setErr(e?.message ?? String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="pair-card">
      <div className="pair-head">
        <div>
          <h3 style={{ margin: 0 }}>Spárovať nové zariadenie</h3>
          <p className="muted" style={{ margin: "4px 0 0", fontSize: 13 }}>
            Otvor Android app, povoľ špeciálne prístupy, kód sa zobrazí. Zadaj ho tu.
          </p>
        </div>
        <div className="pair-step">
          <div className="pair-step-num">1</div>
          <span>Android</span>
        </div>
        <div className="pair-step">
          <div className="pair-step-num">2</div>
          <span>Tu zadaj kód</span>
        </div>
        <div className="pair-step">
          <div className="pair-step-num">3</div>
          <span>Hotovo</span>
        </div>
      </div>

      <div className="pair-digits" onPaste={onPaste}>
        {digits.map((d, i) => (
          <input
            key={i}
            id={`pair-d-${i}`}
            className="pair-digit"
            inputMode="numeric"
            maxLength={1}
            value={d}
            onChange={(e) => setDigit(i, e.target.value)}
            onKeyDown={(e) => onKey(i, e)}
            autoFocus={i === 0}
          />
        ))}
      </div>

      <div className="pair-actions">
        <button
          id="pair-submit"
          className="btn btn-primary"
          onClick={submit}
          disabled={busy || code.length !== 6}
          type="button"
        >
          {busy && <span className="spinner" />}
          {busy ? "Párujem…" : "Spárovať zariadenie"}
        </button>
        {timeLeft > 0 && (
          <span className="muted small">Platnosť kódu: {Math.floor(timeLeft / 60)}:{String(timeLeft % 60).padStart(2, "0")}</span>
        )}
      </div>

      {err && <div className="error">{err}</div>}
      {ok && <div className="success">{ok}</div>}

      <details className="pair-help">
        <summary>Ako získať kód?</summary>
        <ol>
          <li>Nainštaluj APK na Android zariadenie</li>
          <li>Spusti appku, povoľ <b>Accessibility Service</b>, <b>Device Admin</b> a <b>Notification Listener</b></li>
          <li>App automaticky vygeneruje 6-miestny kód</li>
          <li>Kód zadaj do políčok vyššie (platí 5 minút)</li>
        </ol>
      </details>
    </div>
  )
}
