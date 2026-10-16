#!/usr/bin/env bash
# Build a release of the TosbiSim Host APK (the one file users sideload): daemon -> embedded Magisk module -> signed
# APK in host/out/. Needs the release key in ~/.tosbisim (see host/android/app/build.gradle.kts).
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
[ -f "$HOME/.tosbisim/signing.properties" ] || { echo "no release key in ~/.tosbisim"; exit 1; }
"$HERE/daemon/build.sh"
"$HERE/magisk/pack-embed.sh"
(cd "$HERE/android" && ./gradlew -q assembleRelease)
VER=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$HERE/android/app/build.gradle.kts")
mkdir -p "$HERE/out"
OUT="$HERE/out/TosbiSim-Host-$VER.apk"
cp "$HERE/android/app/build/outputs/apk/release/app-release.apk" "$OUT"
APKSIGNER="$(ls "$HOME"/Library/Android/sdk/build-tools/*/apksigner | sort -V | tail -1)"
"$APKSIGNER" verify --print-certs "$OUT" | grep -m1 "SHA-256"
(cd "$HERE/out" && shasum -a 256 "$(basename "$OUT")" > "$(basename "$OUT").sha256")
echo "release: $OUT"
