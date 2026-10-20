# SIM Relay host module installer hook (sourced by Magisk). GPL-3.0-or-later.
[ "$ARCH" = "arm64" ] || abort "! arm64 only for now"
set_perm "$MODPATH/simrelayd" 0 0 0755
# Installed from inside the app (one APK for everyone): the module carries no APK, so take the installed one.
PRIV="$MODPATH/system/priv-app/SimRelayHost"
if [ ! -f "$PRIV/SimRelayHost.apk" ]; then
  APK=$(pm path dev.simrelay.host 2>/dev/null | head -1 | sed 's/^package://')
  [ -f "$APK" ] || abort "! Install the TosbiSim Host app first, then install this module from inside it"
  mkdir -p "$PRIV" && cp "$APK" "$PRIV/SimRelayHost.apk" || abort "! could not copy the app"
  set_perm_recursive "$PRIV" 0 0 0755 0644
  ui_print "- using the installed TosbiSim Host app ($APK)"
fi
ui_print "- TosbiSim Host is installed."
ui_print "- Restart the phone: TosbiSim starts by itself and opens"
ui_print "  its screen, ready to pair with your iPhone."
# Android keeps a parsed copy of system apps in /data/system/package_cache and, for files on the system
# partition, only invalidates it on an OTA (mtime is ignored). Drop ours so an updated APK is re-read.
rm -f /data/system/package_cache/*/SimRelayHost-*
