#!/usr/bin/env bash
# Read-only collector of a rooted Android phone's audio routing state over adb.
# Usage: ./dump-audio-info.sh <model-tag> [label]
#   ./dump-audio-info.sh s20fe-r8s          # idle snapshot
#   ./dump-audio-info.sh s20fe-r8s incall   # run again DURING an active call
# Nothing here changes device state. Commit the output under device-dumps/<model-tag>/.
set -u
MODEL="${1:?usage: dump-audio-info.sh <model-tag> [label]}"
LABEL="${2:-idle}"
SERIAL="${ADB_SERIAL:-}"
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
OUT="$(cd "$(dirname "$0")/.." && pwd)/device-dumps/$MODEL"
mkdir -p "$OUT"
sh_() { "${ADB[@]}" shell "$@" 2>&1 | tr -d '\r'; }
# Feed the command to su on stdin so quotes and pipes inside it survive intact.
su_() { printf '%s\n' "$*" | "${ADB[@]}" shell su 2>&1 | tr -d '\r'; }
have_root() { [ "$(su_ id -u)" = "0" ]; }

F="$OUT/${LABEL}.txt"
{
  echo "=== dump $MODEL / $LABEL / $(date -u +%FT%TZ) ==="
  echo "--- props"
  for p in ro.product.model ro.product.device ro.board.platform ro.hardware \
           ro.soc.manufacturer ro.soc.model ro.build.version.release \
           ro.build.version.incremental ro.vendor.build.fingerprint; do
    echo "$p = $(sh_ getprop $p)"
  done
  echo "--- telephony / IMS (VoLTE registration)"
  sh_ "dumpsys telephony.registry | grep -iE 'ims|volte|mPreciseCall|regState' | head -40"
  echo "--- root"
  if have_root; then echo "root: YES"; else echo "root: NO (audio internals below will be empty)"; fi
  echo "--- /proc/asound/cards"; su_ cat /proc/asound/cards
  echo "--- /proc/asound/pcm (PCM device list: card-device -> name)"; su_ cat /proc/asound/pcm
  echo "--- /proc/asound/pcm count"; su_ "cat /proc/asound/pcm | wc -l"
  echo "--- running PCM substreams (state + hw_params; shows the live call path)"
  su_ 'for s in /proc/asound/card*/pcm*/sub*/status; do grep -q RUNNING $s 2>/dev/null || continue; d=${s%/status}; echo "## $d"; cat $s $d/hw_params; done'
  echo "--- vendor audio policy: telephony / incall ports"
  su_ "grep -iE 'telephony|incall|voice_call|VOICE_CALL|mixPort name' /vendor/etc/audio_policy_configuration*.xml 2>/dev/null | head -60"
  echo "--- vendor routing file present?"
  su_ "ls -l /vendor/etc/mixer_paths*.xml /vendor/etc/audio_device.xml 2>/dev/null"
  echo "--- tinymix availability"
  for t in /data/local/tmp/tinymix /vendor/bin/tinymix /system/bin/tinymix; do
    su_ "[ -x $t ] && echo present: $t"
  done
  echo "NOTE: if no tinymix on device, push a static build to /data/local/tmp (see NOTES)."
  echo "--- tinymix full control list (needs tinymix present)"
  su_ "for t in /data/local/tmp/tinymix /vendor/bin/tinymix /system/bin/tinymix; do [ -x \$t ] && \$t && break; done"
} > "$F" 2>&1
echo "wrote $F"
echo "Also copy the live routing file for diffing against the LineageOS one:"
echo "  adb shell su -c 'cat /vendor/etc/mixer_paths.xml' > $OUT/mixer_paths.stock.xml    # Exynos/Qualcomm"
echo "  adb shell su -c 'cat /vendor/etc/audio_device.xml' > $OUT/audio_device.stock.xml  # MediaTek"
