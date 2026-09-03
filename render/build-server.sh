#!/usr/bin/env bash
# Render pre-build helper for nove-server.
# Render runs buildCommand with rootDir=server, so we are already there.
set -euo pipefail

echo "[render-build] server root: $(pwd)"
echo "[render-build] node: $(node -v)"
echo "[render-build] npm:  $(npm -v)"

# Ensure package.json + lockfile exist; v3.2.0-ext relies on ../Nove-main/server
# at dev time. For Render we ship them in this folder.
if [ ! -f package.json ]; then
  echo "[render-build] FATAL: package.json missing under server/" >&2
  exit 1
fi

# Install prod deps
npm install --omit=dev

# Sanity-check that entrypoint exists
test -f index-extended.js || { echo "index-extended.js missing"; exit 1; }

echo "[render-build] OK"
