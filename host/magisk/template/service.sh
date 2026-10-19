#!/system/bin/sh
# SIM Relay host — late_start service (root, magisk context). GPL-3.0-or-later.
MODDIR=${0%/*}
PKG=dev.simrelay.host
LOG=/data/local/tmp/simrelay-service.log
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
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
  ( while true; do
      "$MODDIR/simrelayd" --uid "$APP_UID"
      echo "$(date) simrelayd exited $?, restarting in 2 s"
      sleep 2
    done ) &
  # Start the host app ourselves, until it runs: some OEMs (MIUI) block boot broadcasts to apps without their own
  # "autostart" permission, so BOOT_COMPLETED alone isn't enough.
  ( for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
      sleep 5
      pidof $PKG >/dev/null && break
      am start-foreground-service -n $PKG/.HostService >/dev/null 2>&1
    done
    echo "$(date) host app $(pidof $PKG >/dev/null && echo running || echo NOT running)" ) &
  echo "$(date) service.sh done (app uid $APP_UID)"
} >> "$LOG" 2>&1
