# Nove v3.2.0-ext — Threat Model & Architecture

## What this is

A three-tier device monitoring stack:

```
┌────────────────┐    RTDB     ┌──────────────────┐    HTTPS+JWT    ┌────────────────┐
│  Android app   │ <─────────> │  Firebase RTDB   │ <─────────────> │  Nove server   │
│  (gradle,      │  heartbeat  │  (commands)      │  telemetry,     │  (Node 20)     │
│   AES-256-GCM, │             │                  │   files,        │  express, RTDB │
│   Hilt)        │             │                  │   results,      │  admin, AES    │
│                │ ──────────> │                  │   SSE live      │                │
│  exfil,        │   /api/v2/  │                  │                 │  writes        │
│  panic,        │   telemetry │                  │                 │  var/files/    │
│  live-shell,   │   /api/v2/  │                  │                 │  <deviceId>/   │
│  command-reg   │   data      │                  │                 │                │
└────────────────┘             └──────────────────┘                 └────────────────┘
                                                                              │
                                                                              ▼
                                                                       ┌────────────────┐
                                                                       │  Dashboard     │
                                                                       │  (Vite + React │
                                                                       │   + Firebase   │
                                                                       │   Auth +       │
                                                                       │   Leaflet)     │
                                                                       └────────────────┘
```

## Trust boundaries

| Boundary | Direction | Auth | Notes |
|----------|-----------|------|-------|
| Device → Firebase | outbound | n/a (RTDB rules via app-check / database.rules.json) | Heartbeat, command fanout, pairing handshake |
| Device → Nove server | outbound | `X-Device-Id` header (paired check) | Telemetry, binary files, command results |
| Dashboard → Firebase | outbound | Firebase ID token (Google + email) | Sign-in |
| Dashboard → Nove server | outbound | `Authorization: Bearer <Firebase ID token>` | All v2 endpoints |

## Data classification

| Class | Examples | At-rest encryption | In-transit |
|-------|----------|-------------------|------------|
| Hot | SMS body, call log, contacts, photos, keylog | AES-256-GCM, AndroidKeyStore (telemetry_key) + SQLCipher (db_key) | TLS 1.2+ |
| Warm | GPS coords, Wi-Fi BSSID/SSID, battery | same | TLS |
| Cold | Device manufacturer/model, app usage | same | TLS |
| Metadata | `lastSeen`, `status`, `config` | RTDB at-rest (Firebase) | TLS |
| Files (binary) | photo, video, audio, arbitrary pulls | AES-256-GCM with a per-file raw key, encrypted client-side, re-encrypted at rest on the server | TLS |

## Failure modes & responses

| Failure | Response |
|---------|----------|
| Firebase RTDB unavailable | The app still POSTs to the Nove server; the server mirrors `devices/<id>/commands` reads from RTDB to its own queue (out of scope for v3.2.0; log + retry). |
| Server unreachable | All outbound traffic is cached in the local Room DB; the sync-cursor pattern means nothing is lost. The WatchdogService re-arms the executor. |
| Device reboots | BootReceiver + WatchdogService exact alarm re-launches CoreService within 1s. WorkManager periodic worker is a deep-doze fallback. |
| Accessibility disabled | The `LiveShell` and accessibility-derived keylog stop; other collectors continue. The dashboard surfaces this as a permission_lost event. |
| Notification listener disabled | The Dashboard activity timeline shows the gap. The `checkPermissions` loop emits `permission_lost` on the next 5-min tick. |
| App force-stopped (OEM) | WorkManager re-launches within 15 minutes. The next heartbeat re-advertises the pairing code with a fresh 5-min TTL. |
| User uninstalls | The next heartbeat fails (pairedTo lookup returns 404). The server's `pairing_requests/<id>` TTL prunes in 5 min. No data persists. |
| Server disk fills | Files are capped to 720 battery + 200 events per device + 100 raw_batches + MODULE_CAPS. Operator sets up log rotation on the host. |
| Operator types `PANIC` | Device wipes its DB, captures dir, prefs, keys. Then optionally device-owner factory-reset and silent uninstall. |

## Defense-in-depth (against the operator losing the device)

1. **AndroidKeyStore-bound keys** — keys never leave the secure element; wiping the keystore aliases renders the DB and prefs unrecoverable.
2. **EncryptedSharedPreferences** — the per-file raw key material is wrapped by an additional layer.
3. **Pairing code TTL** — 5 min; the app refreshes on every heartbeat. Brute force is rate-limited to 20/15min.
4. **Tied to a single user** — the moment pairing succeeds, `pairing_requests/<id>` is removed; the device will not re-advertise.
5. **AES-256-GCM with a 96-bit IV** — every message has a fresh IV; auth tag is checked server-side. Server-side encryption at rest for `raw_batches` is in addition to client-side encryption of `content` strings.
6. **Stealth** — no foreground service notification; launcher icon is an alias that is disabled after pairing. App label is "UI_service" and the launcher is the same.

## What is **not** in scope for v3.2.0-ext

- OTA APK self-update (the `ConfigManager.auto_update: true` flag exists but the actual update mechanism is left to the operator's build pipeline; an `ota.json` endpoint is the natural next step).
- DNS-over-HTTPS transport (Scaffolded, not wired).
- Binary file *download* over the Firebase RTDB (only HTTP). The Firebase transport is the fallback.
- Audio/Video call recording (MediaProjection + AudioPlaybackCapture) — left as a follow-up; the `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` is in the manifest but not yet started by `CameraCapture`.
- Per-user API keys (the `dashboard/src/firebase.ts` embeds the public Firebase web config; the operator should override via env at build time).
- The `EXEC` command is constrained to the foreground app; the accessibility service cannot reach into a backgrounded process without an injected event.

## Operator runbook

```bash
# 1. Provision Firebase
firebase projects:create nove-prod
firebase database:instances:create nove-prod --region europe-west1
# enable auth (email + Google) and grab the web app config + service account JSON

# 2. Build dashboard
cd Nove-main/dashboard
cat > .env <<EOF
VITE_FIREBASE_API_KEY=...
VITE_FIREBASE_AUTH_DOMAIN=nove-prod.firebaseapp.com
VITE_FIREBASE_PROJECT_ID=nove-prod
VITE_FIREBASE_STORAGE_BUCKET=nove-prod.appspot.com
VITE_FIREBASE_MESSAGING_SENDER_ID=...
VITE_FIREBASE_APP_ID=...
EOF
npm install leaflet
npm install -D @types/leaflet
npm run build

# 3. Build server
cd ../server
cat > .env <<EOF
FIREBASE_SERVICE_ACCOUNT_JSON=$(cat ~/nove-prod-firebase-adminsdk.json)
FIREBASE_DATABASE_URL=https://nove-prod-default-rtdb.europe-west1.firebasedatabase.app
ENCRYPTION_KEY=$(openssl rand -hex 32)
ALLOWED_ORIGINS=https://dashboard.nove.example.com
PORT=3000
EOF
node index-extended.js

# 4. Build APK
cd ../android
cp ../google-services.json.template app/google-services.json
./gradlew assembleRelease
# sign with the deploy keystore
make sign-apk

# 5. Distribute the APK outside the Play Store
# (sideload, MDM, packaging under a different label, etc.)
```
