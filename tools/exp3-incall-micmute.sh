#!/usr/bin/env bash
# Phase 0 experiment 3 (Qualcomm legacy HAL): remove the host mic from the uplink, keep injection.
# Waits for an incoming call, answers it, checks the silence guard, runs tools/exp3-device.sh as root
# (tone injected throughout; "Voice Tx Device Mute" set after MUTE_AT s), then ALWAYS unmutes and
# restores every touched control (the kernel keeps device-mute state for later calls).
# Usage: ADB_SERIAL=... ./exp3-incall-micmute.sh <out-dir> <tone.wav>
#          [inject-mixer] [vsid] [mute-at-s] [playback-pcm-device]
#        ADB_SERIAL=... ./exp3-incall-micmute.sh --states
set -u
OUT="${1:?usage: exp3-incall-micmute.sh <out-dir> <tone.wav> [inject-mixer] [vsid] [mute-at-s] [pcm-device]}"
TONE="${2:-}"
INJ="${3:-Incall_Music_2 Audio Mixer MultiMedia9}"   # exp 2: VoiceMMode2 call needs Incall_Music_2
VSID="${4:-4294967295}"                               # ALL_SESSION_VSID
MUTE_AT="${5:-15}"
PDEV="${6:-23}"                                       # MultiMedia9 = card 0 device 23
FE="${INJ##* }"
W=/data/local/tmp/exp3
HERE="$(cd "$(dirname "$0")" && pwd)"
ADB=(adb); [ -n "${ADB_SERIAL:-}" ] && ADB=(adb -s "$ADB_SERIAL")
LOG="$OUT/run.log"
R() { printf '%s\n' "$*" | "${ADB[@]}" shell su 2>&1 | tr -d '\r'; }
log() { echo "$(date +%T) $*" | tee -a "$LOG"; }
call_states() { R "dumpsys telecom | grep -oE 'Call id=TC@[0-9]+, state=[A-Z_]+' | sort -u | tr '\n' ' '"; }
[ "$OUT" = "--states" ] && { call_states; echo; exit 0; }
[ -f "$TONE" ] || { echo "tone file missing: $TONE"; exit 2; }
[ -x "$HERE/native/out/arm64-v8a/alsactl_write" ] || { echo "build tools/native first"; exit 2; }
mkdir -p "$OUT"

CTLS=("Voc Rec Config" "MultiMedia1 Mixer VOC_REC_DL" "MultiMedia1 Mixer VOC_REC_UL" "$INJ")

R "mkdir -p $W && rm -f $W/*.wav $W/*.err"
for f in "$TONE" "$HERE/exp3-device.sh" "$HERE/native/out/arm64-v8a/alsactl_write"; do
  "${ADB[@]}" push "$f" /data/local/tmp/ >/dev/null && R "mv /data/local/tmp/$(basename "$f") $W/"
done
R "mv $W/$(basename "$TONE") $W/tone600_st48.wav 2>/dev/null; chmod 755 $W/exp3-device.sh $W/alsactl_write; ls $W" \
  | tr '\n' ' ' | sed 's/^/staged: /' | tee -a "$LOG"; echo

log "waiting for an incoming call (up to 5 min)..."
ringing=0
for _ in $(seq 1 150); do
  case "$(call_states)" in *RINGING*) ringing=1; break ;; esac
  sleep 2
done
log "telecom: $(call_states)"
# Only press CALL on a ringing phone: on an idle one it can redial the last number.
[ "$ringing" = 1 ] || { log "no ringing call seen; exiting without touching anything"; exit 1; }

ORIG="$OUT/orig.txt"
restore() {
  log "restore: $(R "$W/alsactl_write 0 'Voice Tx Device Mute' 0 $VSID 20")"
  [ -s "$ORIG" ] || return
  for c in "${CTLS[@]}"; do
    v=$(grep -F "$c:" "$ORIG" | head -1 | sed 's/^[^:]*: //; s/ .*//')
    case "$v" in On) v=1 ;; Off) v=0 ;; esac
    R "tinymix '$c' $v" >/dev/null
  done
  : > "$OUT/restored.txt"
  for c in "${CTLS[@]}"; do R "tinymix '$c'" >> "$OUT/restored.txt"; done
  if diff -q "$ORIG" "$OUT/restored.txt" >/dev/null; then log "restored OK"; else log "RESTORE MISMATCH"; diff "$ORIG" "$OUT/restored.txt" | tee -a "$LOG"; fi
}
trap restore EXIT

"${ADB[@]}" shell input keyevent KEYCODE_CALL
for _ in $(seq 1 10); do case "$(call_states)" in *ACTIVE*) break ;; esac; sleep 1; done
T0=$(date +%s)
log "answered (T0); telecom: $(call_states)"

: > "$ORIG"
for c in "${CTLS[@]}"; do R "tinymix '$c'" >> "$ORIG"; done
log "saved originals: $(tr '\n' '|' < "$ORIG")"

other=$(R "tinymix" | grep -E "Mixer $FE[[:space:]]" | grep -vE 'Incall_Music|VOC_REC' | grep -E '[[:space:]]On' || true)
[ -z "$other" ] || { log "ABORT: $FE is routed elsewhere (host would make sound): $other"; exit 3; }

log "running device script (mute at +$MUTE_AT s after inject)"
R "sh $W/exp3-device.sh $W $PDEV '$INJ' $VSID $MUTE_AT" | while read -r ts msg; do
  printf '%s T0+%5.1fs %s\n' "$(date +%T)" "$(echo "$ts - $T0" | bc)" "$msg"
done | tee -a "$LOG"
log "after: telecom: $(call_states)"

"${ADB[@]}" exec-out su -c "cat $W/exp3_rec.wav" > "$OUT/exp3_rec.wav"
R "cat $W/tinyplay.err 2>/dev/null" > "$OUT/tinyplay.err"
log "pulled: $(ls -l "$OUT"/*.wav | awk '{print $5, $NF}' | tr '\n' ' ')"
