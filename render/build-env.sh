#!/usr/bin/env bash
# Render helper — generates server/.env from a service account JSON file.
# Usage: ./build-env.sh <path-to-service-account.json> [db-url]
set -euo pipefail

SA_FILE="${1:-}"
DB_URL="${2:-https://nove33-a56e7-default-rtdb.europe-west1.firebasedatabase.app}"

if [ -z "$SA_FILE" ] || [ ! -f "$SA_FILE" ]; then
  echo "Usage: $0 <service-account.json> [db-url]" >&2
  exit 1
fi

# Minify to single-line JSON
SINGLE_LINE=$(node -e "console.log(JSON.stringify(JSON.parse(require('fs').readFileSync(process.argv[1],'utf8'))))" "$SA_FILE")

OUT="server/.env"
mkdir -p server
cat > "$OUT" <<ENVEOF
NODE_ENV=production
PORT=3000
FILES_DIR=./var/files
ALLOWED_ORIGINS=http://localhost:5173
FIREBASE_DATABASE_URL=$DB_URL
FIREBASE_SERVICE_ACCOUNT_JSON=$SINGLE_LINE
ENVEOF

# Generate ENCRYPTION_KEY only if not present
if ! grep -q '^ENCRYPTION_KEY=' "$OUT" 2>/dev/null; then
  KEY=$(node -e "console.log(require('crypto').randomBytes(32).toString('hex'))")
  printf "ENCRYPTION_KEY=%s\n" "$KEY" >> "$OUT"
fi

echo "[build-env] wrote $OUT"
echo "[build-env] FIREBASE_SERVICE_ACCOUNT_JSON length: ${#SINGLE_LINE}"
