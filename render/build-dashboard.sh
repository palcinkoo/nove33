#!/usr/bin/env bash
# Render build helper for nove-dashboard (Vite static).
set -euo pipefail

echo "[render-build] dashboard root: $(pwd)"
echo "[render-build] node: $(node -v)"
echo "[render-build] npm:  $(npm -v)"

test -f package.json || { echo "package.json missing"; exit 1; }

npm install
npm run build

test -d dist || { echo "dist/ not produced"; exit 1; }
echo "[render-build] dist/ ready"
