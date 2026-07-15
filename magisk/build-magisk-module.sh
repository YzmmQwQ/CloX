#!/usr/bin/env bash
# Packages the CloX priv-app Magisk module into a flashable zip, bundling a CloX APK.
#
# Usage:
#   magisk/build-magisk-module.sh [path/to/app.apk]
#
# Defaults to the debug APK. Pass the release APK for a shipping module.
set -euo pipefail

MODDIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$MODDIR/.." && pwd)"

APK="${1:-$REPO/app/build/outputs/apk/debug/app-debug.apk}"
OUT="$REPO/CloX-privapp-magisk.zip"

if [ ! -f "$APK" ]; then
  echo "APK not found: $APK" >&2
  echo "Build one first, e.g.: ./gradlew :app:assembleDebug" >&2
  exit 1
fi

echo "- Bundling APK: $APK"
mkdir -p "$MODDIR/system/priv-app/CloX"
cp -f "$APK" "$MODDIR/system/priv-app/CloX/CloX.apk"

echo "- Zipping module -> $OUT"
rm -f "$OUT"
( cd "$MODDIR" && zip -r -q "$OUT" \
    module.prop customize.sh service.sh \
    system META-INF \
    -x '*.DS_Store' )

echo "- Done: $OUT"
echo "  Flash in Magisk (Modules > Install from storage) and reboot."
