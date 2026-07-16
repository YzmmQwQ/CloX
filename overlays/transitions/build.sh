#!/usr/bin/env bash
# Builds the CloX Transitions RRO (zeroed wallpaper app-transition anims) and drops the signed APK
# into the Magisk module at magisk/system/product/overlay/CloXTransitions.apk. Static RRO targeting
# the framework (android) package; ships via the module's build-magisk-module.sh (zips whole system).
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$SDK/build-tools/35.0.0"
AJAR="$SDK/platforms/android-35/android.jar"
KS="$HOME/.android/debug.keystore"
OUT="$DIR/build"
DEST="$DIR/../../magisk/system/product/overlay/CloXTransitions.apk"

rm -rf "$OUT"; mkdir -p "$OUT/compiled" "$(dirname "$DEST")"

"$BT/aapt2" compile --dir "$DIR/res" -o "$OUT/compiled.zip"
"$BT/aapt2" link \
    -I "$AJAR" \
    --manifest "$DIR/AndroidManifest.xml" \
    --min-sdk-version 26 --target-sdk-version 35 \
    -o "$OUT/unsigned.apk" \
    "$OUT/compiled.zip"
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
# v4 signing OFF: a Magisk-mounted /system overlay can't provide the fs-verity .idsig that v4
# requires, so PackageManager fails to collect certs and skips the overlay ("Failed to collect
# certificates ... using APK Signature Scheme v4"). v2+v3 are enough for a preinstalled overlay.
"$BT/apksigner" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
    --v4-signing-enabled false \
    --out "$DEST" "$OUT/aligned.apk"
rm -rf "$OUT"
echo "built -> $DEST"
