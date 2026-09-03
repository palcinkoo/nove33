# Nove v3.2.0-ext — Render deploy

Render nepodporuje multi-image compose. Riešenie: **dva services** v jednom `render.yaml`,
prepojené cez verejné URL (server ↔ dashboard). Súbory perzistujeme cez Render Disk
(`/var/files`).

## 0. Predpoklady

- Konto na https://render.com
- GitHub repo obsahujúci `nove33/` (tento worktree)
- Firebase projekt (RTDB + Auth) so service account

## 1. Render Blueprint

Render → **New** → **Blueprint** → povedz na `render/render.yaml` v repo.

Vytvorí:
- `nove-server`  — Web Service (Node 20), rootDir `server/`, port 3000, health `/api/v2/status`
- `nove-dashboard` — Static Site (Vite build), rootDir `dashboard/`, publish `dist/`

## 2. Secret env vars (vyplniť v Render Dashboard po vytvorení)

### `nove-server`
| Key | Hodnota |
|-----|---------|
| `FIREBASE_DATABASE_URL` | `https://<project-id>-default-rtdb.firebaseio.com` |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Celý JSON service accountu, **minified na jeden riadok** (Render neakceptuje multi-line) |
| `ENCRYPTION_KEY` | 32B hex (nechaj generované cez `generateValue: true`, NEZMAZÁVAJ — všetky batch dáta sú tým kľúčom šifrované) |
| `ALLOWED_ORIGINS` | `https://nove-dashboard.onrender.com` (alebo vlastná doména) |
| `FILES_DIR` | `/var/files` (mount disk) |

### `nove-dashboard`
| Key | Hodnota |
|-----|---------|
| `VITE_FIREBASE_API_KEY` | z Firebase Console |
| `VITE_FIREBASE_AUTH_DOMAIN` | `<project>.firebaseapp.com` |
| `VITE_FIREBASE_PROJECT_ID` | `<project>` |
| `VITE_FIREBASE_STORAGE_BUCKET` | `<project>.appspot.com` |
| `VITE_FIREBASE_MESSAGING_SENDER_ID` | numerické |
| `VITE_FIREBASE_APP_ID` | `1:...:web:...` |
| `VITE_API_BASE` | `https://nove-server.onrender.com` |
| `VITE_DASHBOARD_BASE` | `https://nove-dashboard.onrender.com` |

> **VITE_*** sa vnorujú do bundle pri `npm run build`. Ak ich zmeníš, dashboard
> treba re-deploynúť (autoDeploy=true to urobí za teba po uložení).

## 3. Disk pre súbory

`render.yaml` deklaruje `disk: { name: nove-files, mountPath: /var/files, sizeGB: 5 }`.
Render Disk:
- prežije redeploy,
- **migruje** medzi startermi službami v rovnakej infra,
- nesdiela sa medzi službami (je privátny pre `nove-server`).

Ak potrebuješ >5 GB, Render Starter povoľuje max 32 GB. Starter tier vyžaduje
aj disk (prvé 1 GB zadarmo, potom $0.25/GB-mesiac).

## 4. CORS

Dashboard beží na `https://nove-dashboard.onrender.com`, server na
`https://nove-server.onrender.com`. `ALLOWED_ORIGINS` na serveri musí presne
obsahovať dashboard URL (vrátane schémy, bez trailing slash). Ak pridáš custom
doménu (napr. `nove.example.com`), pridaj ju tamtiež.

## 5. Health check

`healthCheckPath: /api/v2/status` → Render pingá každých 30s. Endpoint existuje
v `index-extended.js`, nevyžaduje auth.

## 6. Prvý boot — overenie

```bash
curl https://nove-server.onrender.com/api/v2/status
# -> {"status":"online","version":"3.2.0-ext",...}

curl -I https://nove-dashboard.onrender.com
# -> 200 OK, text/html
```

Dashboard otvor v prehliad → Auth cez Firebase. Ak vidíš 0 devices, ešte
žiadne nie sú spárované — spáruj cez Android build (viď `make android-emu` /
`docs/WIRING.md`).

## 7. Cold start

Render Starter free-tier uspáva Web Services po 15 min nečinnosti. Prvý
request potom trvá ~30s. Ak chceš stabilné latency, ostaneš na Starter
($7/mes) alebo vyššie.

## 8. Logy

- Server: Render Dashboard → `nove-server` → **Logs**
- Dashboard: statický site neloguje runtime; build logy sú v **Events**

## 9. Rollback

Render Dashboard → `nove-server` → **Manual Deploy** → vyber starší commit.
alebo `git revert` v repo → auto deploy.

## 10. Známe limitácie (zachované z v3.1.0)

- Service account JSON musí byť single-line (Render Secret Files to
  nepodporuje).
- `/var/files` nie je zálohovaný Renderom. Ak ho potrebuješ zálohovať,
  pridaj cron v serveri (s3/r2 upload cez `routes/files.js`).
- Ak presiahneš `sizeGB` na disku, server začne hádzať `ENOSPC` pri
  reassembly. Render pošle alert emailom.
