#!/usr/bin/env bash
# Publish a built APK so installed copies of the app offer it as an update.
#
#   sudo bash publish-android.sh path/to/app-release.apk "What's new in this build"
#
# Run on the home server (needs root to write /opt/voxelport-web/files). It reads the
# versionCode / versionName / size / SHA-256 from the APK itself, so they can't be typed
# wrong, then installs the APK and the manifest (android.json) that the app checks.
# The APK URL carries ?v=<first 8 hex of the SHA-256>, so every build has its own cache
# entry and a fresh manifest can never point at a stale cached APK.
set -euo pipefail

APK=${1:?usage: publish-android.sh <apk> "<release notes>"}
NOTES=${2:-}
DIR=/opt/voxelport-web/files
AAPT=$(ls /home/*/android-tools/sdk/build-tools/*/aapt 2>/dev/null | tail -1)
[ -n "$AAPT" ] || { echo "aapt not found"; exit 1; }
[ -f "$APK" ] || { echo "no such file: $APK"; exit 1; }

BADGING=$("$AAPT" dump badging "$APK")
PKG=$(printf '%s' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
CODE=$(printf '%s' "$BADGING" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
NAME=$(printf '%s' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c%s "$APK")
[ "$PKG" = "com.voxelport.android" ] || { echo "wrong package: $PKG"; exit 1; }
[ -n "$CODE" ] || { echo "couldn't read versionCode"; exit 1; }

# Never publish an older build over a newer one.
if [ -f "$DIR/android.json" ]; then
  OLD=$(node -e "try{console.log(JSON.parse(require('fs').readFileSync('$DIR/android.json','utf8')).versionCode||0)}catch{console.log(0)}")
  if [ "$CODE" -lt "$OLD" ]; then echo "refusing: build $CODE is older than the published $OLD"; exit 1; fi
fi

install -d -m 755 "$DIR"
install -m 644 "$APK" "$DIR/voxelport-android.apk"
MANIFEST=$(CODE="$CODE" NAME="$NAME" SHA="$SHA" SIZE="$SIZE" NOTES="$NOTES" node -e '
  const e = process.env;
  console.log(JSON.stringify({
    versionCode: Number(e.CODE), versionName: e.NAME,
    url: "/dl/voxelport-android.apk?v=" + e.SHA.slice(0, 8),
    sha256: e.SHA, size: Number(e.SIZE), notes: e.NOTES,
  }, null, 2));')
printf '%s\n' "$MANIFEST" > "$DIR/android.json.tmp"
chmod 644 "$DIR/android.json.tmp"
mv "$DIR/android.json.tmp" "$DIR/android.json"   # atomic: the app never reads a half-written file
echo "published build $CODE ($NAME), $SIZE bytes, sha256 ${SHA:0:12}…"
printf '%s\n' "$MANIFEST"
