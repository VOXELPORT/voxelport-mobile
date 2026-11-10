#!/usr/bin/env bash
# Build the private DEV app and publish it, so the VoxelPort Dev app on the owner's phone offers it as an update.
#
#   bash tools/publish-dev.sh "What changed in this build"      (Git Bash, from the VoxelPort-Android folder)
#
# What it does:
#   1. builds the "dev" variant (package com.voxelport.android.dev, label "VoxelPort Dev", signed with the release key)
#   2. reads versionCode / package / size / SHA-256 from the APK itself
#   3. uploads the APK and the feed (JSON) to the site server's /dl folder under a SECRET file name:
#        https://voxelport.in/dl/dev-<devToken>.apk   (first install: open this link on the phone)
#        https://voxelport.in/dl/dev-<devToken>.json  (the feed the dev app reads, and only the dev app)
#
# The secret is devToken in dev.properties (not in git). Anyone with the link can download the dev build, so treat it like a password.
# The real app never reads this feed (its feeds are fixed in build.gradle.kts), and the dev app never reads the real one.
# Needs ssh access to the site server (same key as website/deploy/deploy.sh).
set -euo pipefail
cd "$(dirname "$0")/.."

NOTES="${1:-Dev build}"
HOST="${DEPLOY_HOST:-root@129.121.170.249}"
KEY="${DEPLOY_KEY:-$HOME/.ssh/voxelport_vps}"
DIR=/opt/voxelport-web/files
SITE=https://voxelport.in

TOKEN="$(sed -n 's/^devToken=//p' dev.properties 2>/dev/null | tr -d '\r' | head -1)"
[[ "$TOKEN" =~ ^[0-9a-f]{16,}$ ]] || { echo "dev.properties needs a line  devToken=<at least 16 random hex characters>" >&2; exit 1; }

# Always JDK 21: the Android build tools cannot read a newer system JDK (this PC's default JAVA_HOME is 25).
export JAVA_HOME="${ANDROID_JAVA_HOME:-/c/Users/Trazverse/android-build/jdk-21.0.13+9}"
export PATH="$JAVA_HOME/bin:$PATH"
BT="${BUILD_TOOLS:-/c/Users/Trazverse/android-build/sdk/build-tools/35.0.0}"

./gradlew.bat assembleDev --console=plain -q
APK=app/build/outputs/apk/dev/app-dev.apk
[ -f "$APK" ] || { echo "no APK at $APK" >&2; exit 1; }

BADGING="$("$BT/aapt2.exe" dump badging "$APK")"
PKG="$(printf '%s' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
CODE="$(printf '%s' "$BADGING" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
NAME="$(printf '%s' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
[ "$PKG" = "com.voxelport.android.dev" ] || { echo "wrong package: $PKG" >&2; exit 1; }
[ -n "$CODE" ] || { echo "could not read versionCode" >&2; exit 1; }
SHA="$(node -e "const c=require('crypto'),f=require('fs');console.log(c.createHash('sha256').update(f.readFileSync(process.argv[1])).digest('hex'))" "$APK")"
SIZE="$(wc -c < "$APK" | tr -d ' ')"

# Never publish an older build over a newer one.
OLD="$( { curl -fsS "$SITE/dl/dev-$TOKEN.json" 2>/dev/null || true; } | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{try{console.log(JSON.parse(s).versionCode||0)}catch{console.log(0)}})")"
if [ "$CODE" -lt "$OLD" ]; then echo "refusing: build $CODE is older than the published $OLD" >&2; exit 1; fi

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
CODE="$CODE" NAME="$NAME" SHA="$SHA" SIZE="$SIZE" NOTES="$NOTES" TOKEN="$TOKEN" node -e '
  const e = process.env;
  console.log(JSON.stringify({
    versionCode: Number(e.CODE), versionName: e.NAME,
    url: "/dl/dev-" + e.TOKEN + ".apk?v=" + e.SHA.slice(0, 8),
    sha256: e.SHA, size: Number(e.SIZE), notes: e.NOTES,
  }, null, 2));' > "$TMP/feed.json"

scp -i "$KEY" -q "$APK" "$HOST:$DIR/dev-$TOKEN.apk.tmp"
scp -i "$KEY" -q "$TMP/feed.json" "$HOST:$DIR/dev-$TOKEN.json.tmp"
# The APK first, then the feed (atomic renames), so the app never sees a feed that points at a file that is not there yet.
ssh -i "$KEY" "$HOST" "chmod 644 $DIR/dev-$TOKEN.apk.tmp $DIR/dev-$TOKEN.json.tmp && mv $DIR/dev-$TOKEN.apk.tmp $DIR/dev-$TOKEN.apk && mv $DIR/dev-$TOKEN.json.tmp $DIR/dev-$TOKEN.json"

# Check it from the outside, the way the phone will.
GOT="$(curl -fsSL "$SITE/dl/dev-$TOKEN.apk?v=${SHA:0:8}" | node -e "const c=require('crypto');const h=c.createHash('sha256');process.stdin.on('data',d=>h.update(d)).on('end',()=>console.log(h.digest('hex')))")"
[ "$GOT" = "$SHA" ] || { echo "The published APK does not match (got $GOT)." >&2; exit 1; }
echo "Published dev build $CODE ($NAME), $SIZE bytes, sha256 ${SHA:0:12}…"
echo "Install link (first time, open on the phone): $SITE/dl/dev-$TOKEN.apk?v=${SHA:0:8}"
echo "Update feed (the dev app reads this):        $SITE/dl/dev-$TOKEN.json"
