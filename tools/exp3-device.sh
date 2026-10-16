#!/system/bin/sh
# Device side of Phase 0 experiment 3 (root, during an active call, from exp3-incall-micmute.sh).
# Injects a steady tone for the whole run and records uplink+downlink (stereo: ch0 TX, ch1 RX).
# After MUTE_AT seconds it sets "Voice Tx Device Mute" = {1, VSID, 20 ms}; unmutes at the end.
# Args: <work-dir> <playback-pcm-device> <in-call-music mixer name> <vsid> <mute-at-seconds>
W="$1"; PDEV="$2"; INJ="$3"; VSID="$4"; MUTE_AT="$5"
t() { echo "$(date +%s.%N | cut -c1-14) $*"; }

tinymix 'Voc Rec Config' 2
tinymix 'MultiMedia1 Mixer VOC_REC_DL' 1
tinymix 'MultiMedia1 Mixer VOC_REC_UL' 1
tinycap "$W/exp3_rec.wav" -D 0 -d 0 -c 2 -r 16000 -b 16 -T 40 >/dev/null 2>&1 &
REC=$!
t "rec started"
sleep 1

tinymix "$INJ" 1
tinyplay "$W/tone600_st48.wav" -D 0 -d "$PDEV" >/dev/null 2>"$W/tinyplay.err" &
PLAY=$!
t "inject on ($INJ), tone playing"

sleep "$MUTE_AT"
t "mute: $("$W/alsactl_write" 0 'Voice Tx Device Mute' 1 "$VSID" 20 2>&1)"

wait $PLAY
t "tone done rc=$?"
t "unmute: $("$W/alsactl_write" 0 'Voice Tx Device Mute' 0 "$VSID" 20 2>&1)"
tinymix "$INJ" 0
t "inject off"
wait $REC
t "rec done"
