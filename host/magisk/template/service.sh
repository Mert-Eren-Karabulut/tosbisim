#!/system/bin/sh
# SIM Relay host — late_start service (root, magisk context). GPL-3.0-or-later.
MODDIR=${0%/*}
PKG=dev.simrelay.host
LOG=/data/local/tmp/simrelay-service.log
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
[ "$(stat -c %s "$LOG" 2>/dev/null || echo 0)" -gt 1000000 ] && mv -f "$LOG" "$LOG.1"     # one generation kept
{
  echo "$(date) service.sh start (module $(grep ^version= "$MODDIR/module.prop"))"

  # Our logs must show: MediaTek ROMs set log.tag=M, which hides app logs (Redmi Note 8 Pro, 18 Oct).
  setprop log.tag.SimRelayHost I; setprop log.tag.simrelayd I

  # Silence policy: the host sits in a drawer and must never ring, vibrate or click.
  cmd notification set_dnd none                       # Do Not Disturb, total silence
  cmd notification allow_dnd $PKG                     # DND access: the app may set ring/notification volume to 0
  settings put system vibrate_when_ringing 0
  settings put system haptic_feedback_enabled 0
  settings put system sound_effects_enabled 0
  settings put system lockscreen_sounds_enabled 0
  settings put system dtmf_tone 0
  # Samsung call tones played locally through the earpiece (outside the voice path we mute):
  if [ "$(getprop ro.product.manufacturer | tr A-Z a-z)" = samsung ]; then
    settings put system call_end_tone 0               # "call ended" jingle (heard in M3 test, 15 Oct)
    settings put system call_conn_tone 0              # "call connected" beep
    settings put system volume_waiting_tone 0         # call-waiting beep volume
  fi
  # Stream volumes are set by the host app (HostService, with the DND access above), never with `cmd media_session volume` here: on Android 11
  # that command runs ActivityThread.systemMain() inside system_server and crashes it ~25 s later (Redmi Note 8 Pro).

  # Runtime permissions and battery-optimisation exemption for the host app.
  for p in READ_PHONE_STATE POST_NOTIFICATIONS CALL_PHONE BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT \
           RECEIVE_SMS SEND_SMS READ_SMS RECEIVE_MMS RECEIVE_WAP_PUSH; do
    pm grant $PKG android.permission.$p
  done
  # Default SMS app (SMS arrive via SMS_DELIVER to us): the app claims and guards the role itself (Relay
  # guardSmsRole, through simrelayd's time-limited ROLE), once the role service is up.
  dumpsys deviceidle whitelist +$PKG
  appops set $PKG WRITE_SETTINGS allow                 # box mode: AOD / wake-gesture keys live in Settings.System

  # simrelayd: only the host app's uid (and root) may talk to it; restart if it ever exits.
  # (not /data/data/$PKG: that folder only appears after the app first runs)
  APP_UID=$(pm list packages -U $PKG | sed -n "s/.*uid://p" | head -1)
  [ -n "$APP_UID" ] || echo "$(date) WARNING: $PKG uid not found; daemon will accept root only"
  # Root for the host app (self-updates, daemon swap). The phone has no screen to answer Magisk's prompt, which a
  # headless phone records as a denial (seen 18 Oct): grant it here, as the module that installed the app.
  [ -n "$APP_UID" ] && magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($APP_UID,2,0,1,0)" \
    && echo "root granted to $PKG ($APP_UID)"
  # A daemon that died while cutting the charge would leave the battery to drain to 0 % (the cut is released only by a
  # running daemon): put every cut node back to "charge" after any exit. These are the normal values.
  release_charge() {
    for n in batt_slate_mode:0 input_suspend:0 charging_enabled:1; do
      f=/sys/class/power_supply/battery/${n%%:*}
      [ -e "$f" ] && echo "${n##*:}" > "$f" 2>/dev/null
    done
  }
  # Quick failures back off (2 s up to 60 s); after a self-update that won't start, the previous build comes back; a
  # daemon that keeps failing runs in safe mode (no mixer writes) rather than restart for ever (audit, 19 Oct).
  ( fails=0; delay=2
    while true; do
      t0=$(date +%s)
      SAFE=""; [ $fails -ge 5 ] && SAFE=--safe
      "$MODDIR/simrelayd" --uid "$APP_UID" $SAFE; rc=$?
      release_charge
      if [ $(( $(date +%s) - t0 )) -lt 30 ]; then fails=$((fails + 1)); else fails=0; delay=2; fi
      if [ $fails -eq 3 ] && [ -x "$MODDIR/simrelayd.prev" ] && ! cmp -s "$MODDIR/simrelayd.prev" "$MODDIR/simrelayd"; then
        cp "$MODDIR/simrelayd.prev" "$MODDIR/simrelayd.tmp" && chmod 755 "$MODDIR/simrelayd.tmp" && mv -f "$MODDIR/simrelayd.tmp" "$MODDIR/simrelayd" \
          && echo "$(date) simrelayd keeps failing: back to the previous build"
      fi
      [ $fails -gt 1 ] && delay=$(( delay * 2 > 60 ? 60 : delay * 2 ))
      echo "$(date) simrelayd exited $rc (quick failures: $fails), restarting in $delay s"
      sleep $delay
    done ) &
  # Start the host app ourselves, and keep it running: some OEMs (MIUI) block boot broadcasts to apps without their own
  # "autostart" permission, and once Android stops restarting a crashed service nothing else brings it back. Every
  # 5 s for the first minute, then every minute for good (audit, 19 Oct).
  ( n=0
    while true; do
      if [ $n -lt 12 ]; then sleep 5; else sleep 60; fi
      n=$((n + 1))
      pidof $PKG >/dev/null && continue
      am start-foreground-service -n $PKG/.HostService >/dev/null 2>&1
      [ $n -gt 12 ] && echo "$(date) host app was not running: started it"
    done ) &
  echo "$(date) service.sh done (app uid $APP_UID)"
} >> "$LOG" 2>&1
