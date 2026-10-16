#!/usr/bin/env bash
# Phase 0 experiment 1 (Qualcomm legacy HAL): caller-only in-call capture.
# Waits for an incoming call, answers it, records two clips from the in-call record path,
# restores every touched mixer control, then pulls the WAVs.
#   A: VOC_REC_DL + VOC_REC_UL, Voc Rec Config=2  -> stereo, TX and RX on separate channels
#   B: VOC_REC_DL only,         Voc Rec Config=1  -> mono, caller only
# Kernel semantics (msm-pcm-voice-v2.c, q6voice.c): Voc Rec Config = channel count;
# direction comes from the VOC_REC_DL/UL routing switches; Voc VSID -1 = all sessions.
# Usage: ADB_SERIAL=... ./exp1-incall-capture.sh <out-dir> [capture-pcm-device]
#        ADB_SERIAL=... ./exp1-incall-capture.sh --states   # just print telecom call states
set -u
OUT="${1:?usage: exp1-incall-capture.sh <out-dir> [pcm-device]}"
DEV="${2:-0}"                      # MultiMedia1 = card 0 device 0 on SM8250 (asound-pcm.txt)
FE="MultiMedia1"
ADB=(adb); [ -n "${ADB_SERIAL:-}" ] && ADB=(adb -s "$ADB_SERIAL")
LOG="$OUT/run.log"
R() { printf '%s\n' "$*" | "${ADB[@]}" shell su 2>&1 | tr -d '\r'; }
log() { echo "$(date +%T) $*" | tee -a "$LOG"; }
# dumpsys telecom prints e.g. "[Call id=TC@3, state=RINGING, ..." (old calls stay listed as DISCONNECTED).
call_states() { R "dumpsys telecom | grep -oE 'Call id=TC@[0-9]+, state=[A-Z_]+' | sort -u | tr '\n' ' '"; }
[ "${1:-}" = "--states" ] && { call_states; echo; exit 0; }
mkdir -p "$OUT"

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
log "answered; telecom: $(call_states)"

ORIG="$OUT/orig.txt"
R "tinymix 'Voc Rec Config'; tinymix '$FE Mixer VOC_REC_DL'; tinymix '$FE Mixer VOC_REC_UL'; tinymix 'Voc VSID'" > "$ORIG"
log "saved originals: $(tr '\n' '|' < "$ORIG")"

log "A: stereo DL+UL 35 s"
R "tinymix 'Voc Rec Config' 2; tinymix '$FE Mixer VOC_REC_DL' 1; tinymix '$FE Mixer VOC_REC_UL' 1;
   tinycap /data/local/tmp/exp1_a.wav -D 0 -d $DEV -c 2 -r 16000 -b 16 -T 35" | tee -a "$LOG"
log "after A telecom: $(call_states)"

log "B: mono DL only 30 s"
R "tinymix '$FE Mixer VOC_REC_UL' 0; tinymix 'Voc Rec Config' 1;
   tinycap /data/local/tmp/exp1_b.wav -D 0 -d $DEV -c 1 -r 16000 -b 16 -T 30" | tee -a "$LOG"
log "after B telecom: $(call_states)"

# Restore: values come from the saved originals (first value of each control).
rc=$(sed -n 's/^Voc Rec Config: \([0-9]*\).*/\1/p' "$ORIG")
onoff() { case "$1" in On) echo 1 ;; *) echo 0 ;; esac; }
dl=$(onoff "$(sed -n "s/^$FE Mixer VOC_REC_DL: \([A-Za-z]*\).*/\1/p" "$ORIG")")
ul=$(onoff "$(sed -n "s/^$FE Mixer VOC_REC_UL: \([A-Za-z]*\).*/\1/p" "$ORIG")")
R "tinymix '$FE Mixer VOC_REC_DL' $dl; tinymix '$FE Mixer VOC_REC_UL' $ul; tinymix 'Voc Rec Config' $rc"
R "tinymix 'Voc Rec Config'; tinymix '$FE Mixer VOC_REC_DL'; tinymix '$FE Mixer VOC_REC_UL'" > "$OUT/restored.txt"
if diff -q <(head -3 "$ORIG") "$OUT/restored.txt" >/dev/null; then log "restored OK"; else log "RESTORE MISMATCH"; diff <(head -3 "$ORIG") "$OUT/restored.txt" | tee -a "$LOG"; fi

# Binary pull: exec-out, no \r stripping.
for c in a b; do "${ADB[@]}" exec-out su -c "cat /data/local/tmp/exp1_$c.wav" > "$OUT/exp1_$c.wav"; done
log "pulled: $(ls -l "$OUT"/*.wav | awk '{print $5, $NF}' | tr '\n' ' ')"
