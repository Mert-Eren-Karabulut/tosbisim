#!/system/bin/sh
# SIM Relay host — late_start service (root, magisk context). GPL-3.0-or-later.
MODDIR=${0%/*}
PKG=dev.simrelay.host
LOG=/data/local/tmp/simrelay-service.log
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
{
  echo "$(date) service.sh start (module $(grep ^version= "$MODDIR/module.prop"))"

  # Silence policy: the host sits in a drawer and must never ring, vibrate or click.
  cmd notification set_dnd none                       # Do Not Disturb, total silence
  settings put system vibrate_when_ringing 0
  settings put system haptic_feedback_enabled 0
  settings put system sound_effects_enabled 0
  settings put system lockscreen_sounds_enabled 0
  settings put system dtmf_tone 0
  # Samsung call tones played locally through the earpiece (outside the voice path we mute):
  settings put system call_end_tone 0                 # "call ended" jingle (heard in M3 test, 15 Oct)
  settings put system call_conn_tone 0                # "call connected" beep
  settings put system volume_waiting_tone 0           # call-waiting beep volume
  for s in 1 2 5; do cmd media_session volume --stream $s --set 0 >/dev/null 2>&1; done  # system, ring, notification

  # Runtime permissions and battery-optimisation exemption for the host app.
  for p in READ_PHONE_STATE POST_NOTIFICATIONS CALL_PHONE BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT \
           RECEIVE_SMS SEND_SMS READ_SMS RECEIVE_MMS RECEIVE_WAP_PUSH; do
    pm grant $PKG android.permission.$p
  done
  # Host is the default SMS app: SMS arrive via SMS_DELIVER to us (store-and-forward to the client).
  cmd role add-role-holder android.app.role.SMS $PKG 0
  echo "SMS role: $(dumpsys role | grep -A3 "name=android.app.role.SMS" | grep -o "holders=[^ ]*")"
  dumpsys deviceidle whitelist +$PKG
  appops set $PKG WRITE_SETTINGS allow                 # box mode: AOD / wake-gesture keys live in Settings.System

  # simrelayd: only the host app's uid (and root) may talk to it; restart if it ever exits.
  # (not /data/data/$PKG: that folder only appears after the app first runs)
  APP_UID=$(pm list packages -U $PKG | sed -n "s/.*uid://p" | head -1)
  [ -n "$APP_UID" ] || echo "$(date) WARNING: $PKG uid not found; daemon will accept root only"
  ( while true; do
      "$MODDIR/simrelayd" --uid "$APP_UID"
      echo "$(date) simrelayd exited $?, restarting in 2 s"
      sleep 2
    done ) &
  sleep 1
  am start-foreground-service -n $PKG/.HostService
  echo "$(date) service.sh done (app uid $APP_UID)"
} >> "$LOG" 2>&1
