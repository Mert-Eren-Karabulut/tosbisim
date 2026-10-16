#!/usr/bin/env bash
# Pack the Magisk module WITHOUT the APK, as an asset of the host app: the app installs it itself once the phone
# is rooted (customize.sh copies the installed APK into the module). Run before building the app.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
DAEMON="$HERE/../daemon/out/arm64-v8a/simrelayd"
OUT="$HERE/../android/app/src/embed/assets/module"
[ -f "$DAEMON" ] || { echo "build host/daemon first"; exit 1; }
STAGE="$(mktemp -d)"
cp -R "$HERE/template/." "$STAGE/"
cp "$DAEMON" "$STAGE/simrelayd"
mkdir -p "$OUT"
rm -f "$OUT/tosbisim-host-module.zip"
(cd "$STAGE" && zip -qr -X "$OUT/tosbisim-host-module.zip" .)
rm -rf "$STAGE"
echo "embedded module: $OUT/tosbisim-host-module.zip ($(wc -c < "$OUT/tosbisim-host-module.zip") bytes)"
