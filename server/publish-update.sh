#!/usr/bin/env bash
# Publish a new build as the over-the-air update served by server/server.js on
# THIS device. After editing code and bumping versionCode/versionName in
# app/build.gradle.kts, run:
#
#   bash server/publish-update.sh "What changed…"
#
# It builds the RELEASE APK (com.axzora.os, release-signed with
# axzora-release.keystore), drops it into server/update/, rewrites update.json
# to serve from THIS device (no GitHub url), and the running server immediately
# offers it to installed apps on next launch. Use the release build so updates
# install cleanly (same package + key).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VC="$(sed -nE 's/.*versionCode = ([0-9]+).*/\1/p' app/build.gradle.kts | head -1)"
VN="$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts | head -1)"
VC="${VC:-0}"; VN="${VN:-dev}"
NOTES="${*:-Automatic update hosted from this device.}"
FILE="axzoraos-$VN.apk"

echo "==> Building RELEASE APK (versionCode=$VC, versionName=\"$VN\")"
bash build-on-device.sh :app:assembleRelease

SRC="app/build/outputs/apk/release/app-release.apk"
mkdir -p server/update
cp "$SRC" "server/update/$FILE"
cat > server/update/update.json <<JSON
{
  "versionCode": $VC,
  "versionName": "$VN",
  "notes": "$NOTES",
  "file": "$FILE"
}
JSON

echo "==> Published (served from this device):"
cat server/update/update.json
echo
echo "==> Verify:"; curl -s "http://127.0.0.1:8787/update/check"; echo
echo "Installed apps below versionCode $VC will be offered \"$VN\"."
