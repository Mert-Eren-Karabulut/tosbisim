#!/system/bin/sh
# Device side of Phase 0 experiment 4 (root, during an active call, from exp4-incall-rxmute.sh).
# Records uplink+downlink (stereo: ch0 TX, ch1 RX) and polls the speaker-amp state every 2 s.
# After MUTE_AT s sets "Voice Rx Device Mute" = {1, VSID, 20 ms}; after MUTE_FOR more s unmutes.
# Args: <work-dir> <vsid> <mute-at-seconds> <mute-for-seconds>
W="$1"; VSID="$2"; MUTE_AT="$3"; MUTE_FOR="$4"
t() { echo "$(date +%s.%N | cut -c1-14) $*"; }
amp() {
  echo "$(tinymix 'Left AMP Enable Switch' | sed 's/.*: //')/$(tinymix 'Left AMP Mute' | sed 's/.*: //')/pwr $(tinymix 'Right DSP1 Protection cd L_SPK_OUTPUT_POWER' | sed 's/.*: //')"
}

tinymix 'Voc Rec Config' 2
tinymix 'MultiMedia1 Mixer VOC_REC_DL' 1
tinymix 'MultiMedia1 Mixer VOC_REC_UL' 1
tinycap "$W/exp4_rec.wav" -D 0 -d 0 -c 2 -r 16000 -b 16 -T 40 >/dev/null 2>&1 &
REC=$!
t "rec started"
( while kill -0 $REC 2>/dev/null; do t "amp $(amp)"; sleep 2; done ) &
POLL=$!

sleep "$MUTE_AT"
t "rx mute: $("$W/alsactl_write" 0 'Voice Rx Device Mute' 1 "$VSID" 20 2>&1)"
sleep "$MUTE_FOR"
t "rx unmute: $("$W/alsactl_write" 0 'Voice Rx Device Mute' 0 "$VSID" 20 2>&1)"
wait $REC
t "rec done"
kill $POLL 2>/dev/null
