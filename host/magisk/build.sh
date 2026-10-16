#!/usr/bin/env bash
# Package the Magisk module: host app (priv-app) + privapp XML + simrelayd + scripts.
# Refuses to build if the APK requests a privileged permission missing from the whitelist
# (on an enforcing ROM that would stop the phone from booting).
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/../.."
APK="$ROOT/host/android/app/build/outputs/apk/debug/app-debug.apk"
DAEMON="$ROOT/host/daemon/out/arm64-v8a/simrelayd"
XML="$HERE/template/system/etc/permissions/privapp-permissions-dev.simrelay.host.xml"
AAPT="$(ls "$HOME"/Library/Android/sdk/build-tools/*/aapt2 | sort -V | tail -1)"
[ -f "$APK" ] && [ -f "$DAEMON" ] || { echo "build host/android and host/daemon first"; exit 1; }

# Privileged permissions requested vs whitelisted (privileged set taken from the device when connected).
requested=$("$AAPT" dump permissions "$APK" | sed -n "s/^uses-permission: name='\(.*\)'.*/\1/p")
for perm in $requested; do
  prot=$(adb shell "dumpsys package permission $perm 2>/dev/null | grep -m1 -o 'prot=[a-z|]*'" 2>/dev/null | tr -d '\r' || true)
  case "$prot" in *privileged*)
    grep -q "name=\"$perm\"" "$XML" || { echo "REFUSING: privileged $perm not in $(basename "$XML")"; exit 1; } ;;
  esac
  [ -n "$prot" ] || echo "warning: could not check $perm on a device (none connected?)"
done

STAGE="$(mktemp -d)"
cp -R "$HERE/template/." "$STAGE/"
mkdir -p "$STAGE/system/priv-app/SimRelayHost"
cp "$APK" "$STAGE/system/priv-app/SimRelayHost/SimRelayHost.apk"
cp "$DAEMON" "$STAGE/simrelayd"
VER=$(sed -n 's/^version=//p' "$HERE/template/module.prop")
mkdir -p "$HERE/out"
OUT="$HERE/out/simrelay-host-$VER.zip"
rm -f "$OUT"
(cd "$STAGE" && zip -qr -X "$OUT" .)
rm -rf "$STAGE"
echo "built $OUT"
unzip -l "$OUT" | awk 'NR>3 {print $4}' | grep -v '^$' | grep -v -- '----'
