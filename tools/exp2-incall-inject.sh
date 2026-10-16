#!/usr/bin/env bash
# Phase 0 experiment 2 (Qualcomm legacy HAL): inject audio into the call uplink.
# Waits for an incoming call, answers it, refuses to play if the playback front end is routed
# anywhere except the in-call music mixers (host must stay silent), runs tools/exp2-device.sh
# as root, restores every touched control, then pulls the recording.
# Usage: ADB_SERIAL=... ./exp2-incall-inject.sh <out-dir> <clips-dir> [playback-pcm-device] [FE]
#        ADB_SERIAL=... ./exp2-incall-inject.sh --states
set -u
OUT="${1:?usage: exp2-incall-inject.sh <out-dir> <clips-dir> [pcm-device] [FE]}"
CLIPS="${2:-}"
PDEV="${3:-23}"                    # MultiMedia9 = card 0 device 23 on SM8250 (asound-pcm.txt)
FE="${4:-MultiMedia9}"
W=/data/local/tmp/exp2
ADB=(adb); [ -n "${ADB_SERIAL:-}" ] && ADB=(adb -s "$ADB_SERIAL")
LOG="$OUT/run.log"
R() { printf '%s\n' "$*" | "${ADB[@]}" shell su 2>&1 | tr -d '\r'; }
log() { echo "$(date +%T) $*" | tee -a "$LOG"; }
call_states() { R "dumpsys telecom | grep -oE 'Call id=TC@[0-9]+, state=[A-Z_]+' | sort -u | tr '\n' ' '"; }
[ "$OUT" = "--states" ] && { call_states; echo; exit 0; }
[ -d "$CLIPS" ] || { echo "clips dir missing: $CLIPS"; exit 2; }
mkdir -p "$OUT"

CTLS=("Voc Rec Config" "MultiMedia1 Mixer VOC_REC_DL" "MultiMedia1 Mixer VOC_REC_UL"
      "Incall_Music Audio Mixer $FE" "Incall_Music_2 Audio Mixer $FE")

# Stage clips + device script before the call.
R "mkdir -p $W && rm -f $W/*.wav $W/*.err"
for f in "$CLIPS"/t1_st48.wav "$CLIPS"/t1_st16.wav "$CLIPS"/t2_st48.wav "$CLIPS"/t2_st16.wav \
         "$(dirname "$0")/exp2-device.sh"; do
  "${ADB[@]}" push "$f" /data/local/tmp/ >/dev/null && R "mv /data/local/tmp/$(basename "$f") $W/"
done
R "chmod 755 $W/exp2-device.sh; ls $W" | tr '\n' ' ' | sed 's/^/staged: /' | tee -a "$LOG"; echo

log "waiting for an incoming call (up to 5 min)..."
ringing=0
for _ in $(seq 1 150); do
  case "$(call_states)" in *RINGING*) ringing=1; break ;; esac
  sleep 2
done
log "telecom: $(call_states)"
# Only press CALL on a ringing phone: on an idle one it can redial the last number.
[ "$ringing" = 1 ] || { log "no ringing call seen; exiting without touching anything"; exit 1; }
"${ADB[@]}" shell input keyevent KEYCODE_CALL
for _ in $(seq 1 10); do case "$(call_states)" in *ACTIVE*) break ;; esac; sleep 1; done
T0=$(date +%s)
log "answered (T0); telecom: $(call_states)"

ORIG="$OUT/orig.txt"; : > "$ORIG"
for c in "${CTLS[@]}"; do R "tinymix '$c'" >> "$ORIG"; done
log "saved originals: $(tr '\n' '|' < "$ORIG")"

# Silence guard: the playback FE must not be routed to any real output.
other=$(R "tinymix" | grep -E "Mixer $FE[[:space:]]" | grep -vE 'Incall_Music|VOC_REC' | grep -E '[[:space:]]On' || true)
if [ -n "$other" ]; then
  log "ABORT: $FE is routed elsewhere (host would make sound): $other"
  exit 3
fi

log "running device script"
R "sh $W/exp2-device.sh $W $PDEV $FE" | while read -r ts msg; do
  printf '%s T0+%5.1fs %s\n' "$(date +%T)" "$(echo "$ts - $T0" | bc)" "$msg"
done | tee -a "$LOG"
log "after: telecom: $(call_states)"

# Restore originals (first value of each control; BOOL On/Off -> 1/0).
for c in "${CTLS[@]}"; do
  v=$(grep -F "$c:" "$ORIG" | head -1 | sed 's/^[^:]*: //; s/ .*//')
  case "$v" in On) v=1 ;; Off) v=0 ;; esac
  R "tinymix '$c' $v" >/dev/null
done
: > "$OUT/restored.txt"
for c in "${CTLS[@]}"; do R "tinymix '$c'" >> "$OUT/restored.txt"; done
if diff -q "$ORIG" "$OUT/restored.txt" >/dev/null; then log "restored OK"; else log "RESTORE MISMATCH"; diff "$ORIG" "$OUT/restored.txt" | tee -a "$LOG"; fi

"${ADB[@]}" exec-out su -c "cat $W/exp2_rec.wav" > "$OUT/exp2_rec.wav"
R "cat $W/*.err 2>/dev/null" > "$OUT/tinyplay.err"
log "pulled: $(ls -l "$OUT"/*.wav | awk '{print $5, $NF}' | tr '\n' ' ')"
