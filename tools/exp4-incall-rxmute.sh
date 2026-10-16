#!/usr/bin/env bash
# Phase 0 experiment 4 (Qualcomm legacy HAL): silence the host's speaker/earpiece mid-call while the
# caller's voice is still captured digitally. Waits for an incoming call, answers, runs
# tools/exp4-device.sh as root, then ALWAYS clears both device mutes and restores the record controls.
# Usage: ADB_SERIAL=... ./exp4-incall-rxmute.sh <out-dir> [vsid] [mute-at-s] [mute-for-s]
set -u
OUT="${1:?usage: exp4-incall-rxmute.sh <out-dir> [vsid] [mute-at-s] [mute-for-s]}"
VSID="${2:-4294967295}"   # ALL_SESSION_VSID
MUTE_AT="${3:-15}"
MUTE_FOR="${4:-18}"
HERE="$(cd "$(dirname "$0")" && pwd)"
W=/data/local/tmp/exp4
mkdir -p "$OUT"
. "$HERE/lib/incall.sh"
[ -x "$HERE/native/out/arm64-v8a/alsactl_write" ] || { echo "build tools/native first"; exit 2; }

CTLS=("Voc Rec Config" "MultiMedia1 Mixer VOC_REC_DL" "MultiMedia1 Mixer VOC_REC_UL")
ORIG="$OUT/orig.txt"

stage "$W" "$HERE/exp4-device.sh" "$HERE/native/out/arm64-v8a/alsactl_write"
wait_ring_and_answer || exit 1

cleanup() {
  log "clear rx mute: $(R "$W/alsactl_write 0 'Voice Rx Device Mute' 0 $VSID 20")"
  log "clear tx mute: $(R "$W/alsactl_write 0 'Voice Tx Device Mute' 0 $VSID 20")"
  restore_ctls "$ORIG" "${CTLS[@]}"
}
trap cleanup EXIT

save_ctls "$ORIG" "${CTLS[@]}"
log "saved originals: $(tr '\n' '|' < "$ORIG")"
log "running device script (rx mute at +$MUTE_AT s for $MUTE_FOR s)"
run_device "sh $W/exp4-device.sh $W $VSID $MUTE_AT $MUTE_FOR"
log "after: telecom: $(call_states)"
pull "$W/exp4_rec.wav" "$OUT/exp4_rec.wav"
log "pulled: $(ls -l "$OUT"/*.wav | awk '{print $5, $NF}' | tr '\n' ' ')"
