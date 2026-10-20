#!/usr/bin/env bash
# Build a release of TosbiSim Host: daemon -> embedded Magisk module -> signed APK -> the Magisk zip, in host/out/.
# Users install ONLY the Magisk zip (20 Oct 2026: Play Protect blocks the sideloaded APK); the APK itself is what
# hosts download for over-the-air self-updates (--publish). Needs the release key in ~/.tosbisim.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
# --publish: hosts get it over the air (api.tosbisim.com). --github: a GitHub release on the public repo with the Magisk
# zip, the file users download (tosbisim.com links to releases/latest/download/TosbiSim-Host-Magisk.zip).
PUBLISH=0; GITHUB=0
for a in "$@"; do case "$a" in --publish) PUBLISH=1;; --github) GITHUB=1;; *) echo "unknown option $a"; exit 2;; esac; done
VER=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$HERE/android/app/build.gradle.kts")
CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$HERE/android/app/build.gradle.kts")
# Every release ships with notes in English and Turkish (host/changelog.json): no notes, no release.
NOTES="$HERE/out/notes-$VER.json"
mkdir -p "$HERE/out"
python3 - "$HERE/changelog.json" "$VER" "$CODE" "$NOTES" <<'PY' || { echo "add $VER ($CODE) with en + tr to host/changelog.json"; exit 1; }
import json, sys
log, ver, code, out = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
e = next(x for x in json.load(open(log, encoding="utf-8")) if x["version"] == ver and x["code"] == code)
assert e["en"].strip() and e["tr"].strip()
json.dump({"en": e["en"], "tr": e["tr"]}, open(out, "w", encoding="utf-8"), ensure_ascii=False)
PY
[ -f "$HOME/.tosbisim/signing.properties" ] || { echo "no release key in ~/.tosbisim"; exit 1; }
"$HERE/daemon/build.sh"
"$HERE/magisk/pack-embed.sh"
(cd "$HERE/android" && ./gradlew -q assembleRelease)
OUT="$HERE/out/TosbiSim-Host-$VER.apk"
cp "$HERE/android/app/build/outputs/apk/release/app-release.apk" "$OUT"
APKSIGNER="$(ls "$HOME"/Library/Android/sdk/build-tools/*/apksigner | sort -V | tail -1)"
"$APKSIGNER" verify --print-certs "$OUT" | grep -m1 "SHA-256"
(cd "$HERE/out" && shasum -a 256 "$(basename "$OUT")" > "$(basename "$OUT").sha256")
echo "release: $OUT"

# The same host as a Magisk module that carries the app: installed from the Magisk app (Modules -> Install from
# storage) it puts TosbiSim on as a system app, which Play Protect's sideloading block never sees. That block stops the
# plain APK cold on some phones ("App blocked to protect your device", no way past it: SMS permissions; Redmi Note 8
# Pro, 20 Oct 2026). customize.sh uses the APK it finds in the module.
ZIP="$HERE/out/TosbiSim-Host-$VER-Magisk.zip"
STAGE="$(mktemp -d)"
unzip -q "$HERE/android/app/src/embed/assets/module/tosbisim-host-module.zip" -d "$STAGE"
mkdir -p "$STAGE/system/priv-app/SimRelayHost"
cp "$OUT" "$STAGE/system/priv-app/SimRelayHost/SimRelayHost.apk"
sed -i '' "s/^version=.*/version=$VER/; s/^versionCode=.*/versionCode=$CODE/" "$STAGE/module.prop"
rm -f "$ZIP"
(cd "$STAGE" && zip -qr -X "$ZIP" .)
rm -rf "$STAGE"
(cd "$HERE/out" && shasum -a 256 "$(basename "$ZIP")" > "$(basename "$ZIP").sha256")
echo "magisk: $ZIP"

# --publish: hand it to api.tosbisim.com, where hosts pick it up (they ask the iPhone before installing).
if [ "$PUBLISH" = 1 ]; then
  SRV=root@82.112.242.53; SITE=/home/tosbisim-api/htdocs/api.tosbisim.com
  scp -q "$OUT" "$NOTES" "$SRV:/tmp/"
  ssh "$SRV" "chown tosbisim-api /tmp/$(basename "$OUT") /tmp/$(basename "$NOTES") && cd $SITE && sudo -u tosbisim-api php8.4 artisan tosbisim:release host '$VER' $CODE /tmp/$(basename "$NOTES") --apk=/tmp/$(basename "$OUT") && rm -f /tmp/$(basename "$OUT") /tmp/$(basename "$NOTES")"
fi

# --github: the public release users download. The asset name never changes, so "latest" always serves the newest zip.
if [ "$GITHUB" = 1 ]; then
  REPO=Mert-Eren-Karabulut/tosbisim; TAG="host-v$VER"
  gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 && { echo "GitHub release $TAG exists already"; exit 1; }
  STAGE="$(mktemp -d)"
  cp "$ZIP" "$STAGE/TosbiSim-Host-Magisk.zip"
  (cd "$STAGE" && shasum -a 256 TosbiSim-Host-Magisk.zip > TosbiSim-Host-Magisk.zip.sha256)
  python3 - "$NOTES" "$STAGE/notes.md" <<'PYN'
import json, sys
n = json.load(open(sys.argv[1], encoding="utf-8"))
open(sys.argv[2], "w", encoding="utf-8").write(
    n["en"] + "\n\n**Install:** in Magisk, Modules → Install from storage → TosbiSim-Host-Magisk.zip → Reboot. "
    "Guide: https://tosbisim.com\n\n---\n\n" + n["tr"] +
    "\n\n**Kurulum:** Magisk → Modüller → Depolamadan yükle → TosbiSim-Host-Magisk.zip → Yeniden başlat. Rehber: https://tosbisim.com\n")
PYN
  gh release create "$TAG" --repo "$REPO" --title "TosbiSim Host $VER" --notes-file "$STAGE/notes.md" --latest \
    "$STAGE/TosbiSim-Host-Magisk.zip" "$STAGE/TosbiSim-Host-Magisk.zip.sha256"
  rm -rf "$STAGE"
  echo "github: https://github.com/$REPO/releases/tag/$TAG"
fi
