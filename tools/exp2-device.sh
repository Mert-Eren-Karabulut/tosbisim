#!/system/bin/sh
# Device side of Phase 0 experiment 2 (run as root during an active call, from exp2-incall-inject.sh).
# Records uplink+downlink (stereo: ch0 TX, ch1 RX) while injecting two clips into the call:
#   clip 1 via "Incall_Music Audio Mixer <FE>", clip 2 via "Incall_Music_2 Audio Mixer <FE>".
# Each clip is tried at 48 kHz then 16 kHz (stereo, per the incall_music_uplink mixPort).
# Args: <work-dir> <playback-pcm-device> <playback-FE-name>
W="$1"; PDEV="$2"; FE="$3"
t() { echo "$(date +%s.%N | cut -c1-14) $*"; }

tinymix 'Voc Rec Config' 2
tinymix 'MultiMedia1 Mixer VOC_REC_DL' 1
tinymix 'MultiMedia1 Mixer VOC_REC_UL' 1
tinycap "$W/exp2_rec.wav" -D 0 -d 0 -c 2 -r 16000 -b 16 -T 34 >/dev/null 2>&1 &
REC=$!
t "rec started pid $REC"
sleep 2

play() {  # <mixer-name> <clip-tag>
  tinymix "$1" 1
  t "on: $1"
  for r in 48 16; do
    if tinyplay "$W/$2_st$r.wav" -D 0 -d "$PDEV" >/dev/null 2>"$W/tinyplay_$2_$r.err"; then
      t "played $2 at ${r}k"; break
    fi
    t "tinyplay $2 at ${r}k failed: $(head -c 200 "$W/tinyplay_$2_$r.err")"
  done
  tinymix "$1" 0
  t "off: $1"
}

play "Incall_Music Audio Mixer $FE" t1
sleep 3
play "Incall_Music_2 Audio Mixer $FE" t2

wait $REC
t "rec done"
