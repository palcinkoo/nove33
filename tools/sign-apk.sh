#!/usr/bin/env bash
# Generate a self-signed keystore (one-time) and sign the Nove debug APK with it.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KEYSTORE="$ROOT/keystore/upload.jks"
APK_IN="${1:-$ROOT/android/app/build/outputs/apk/release/app-release-unsigned.apk}"
APK_OUT="${2:-${APK_IN%.apk}-signed.apk}"
ALIAS="upload"
[[ ! -f "$KEYSTORE" ]] && keytool -genkeypair -v -keystore "$KEYSTORE" -keyalg RSA -keysize 2048 -validity 10000 -alias "$ALIAS" -storepass novepass -keypass novepass -dname "CN=Nove,O=Nove,L=Local,C=US"
$ANDROID_HOME/build-tools/*/apksigner sign --ks "$KEYSTORE" --ks-pass pass:novepass --key-pass pass:novepass --ks-key-alias "$ALIAS" --out "$APK_OUT" "$APK_IN"
$ANDROID_HOME/build-tools/*/apksigner verify --print-certs "$APK_OUT"
echo "Signed: $APK_OUT"
