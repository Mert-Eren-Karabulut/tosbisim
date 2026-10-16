# Shared helpers for the Phase 0 live-call scripts (source it; needs bash).
# Expects: ADB_SERIAL (optional), OUT (output dir). Provides R, log, call_states,
# wait_ring_and_answer, save_ctls, restore_ctls, stage, run_device, pull.
ADB=(adb); [ -n "${ADB_SERIAL:-}" ] && ADB=(adb -s "$ADB_SERIAL")
LOG="$OUT/run.log"
T0=0

# Run a command line as root on the phone (fed on stdin, so quotes and pipes survive).
R() { printf '%s\n' "$*" | "${ADB[@]}" shell su 2>&1 | tr -d '\r'; }
log() { echo "$(date +%T) $*" | tee -a "$LOG"; }
# dumpsys telecom prints e.g. "[Call id=TC@3, state=RINGING, ..." (old calls stay listed as DISCONNECTED).
call_states() { R "dumpsys telecom | grep -oE 'Call id=TC@[0-9]+, state=[A-Z_]+' | sort -u | tr '\n' ' '"; }

# Wait up to 5 min for RINGING, then answer. Never presses CALL on an idle phone (it can redial).
wait_ring_and_answer() {
  log "waiting for an incoming call (up to 5 min)..."
  local ringing=0
  for _ in $(seq 1 150); do
    case "$(call_states)" in *RINGING*) ringing=1; break ;; esac
    sleep 2
  done
  log "telecom: $(call_states)"
  [ "$ringing" = 1 ] || { log "no ringing call seen; nothing touched"; return 1; }
  "${ADB[@]}" shell input keyevent KEYCODE_CALL
  for _ in $(seq 1 10); do case "$(call_states)" in *ACTIVE*) break ;; esac; sleep 1; done
  T0=$(date +%s)
  log "answered (T0); telecom: $(call_states)"
}

# save_ctls <file> <ctl>...   /   restore_ctls <file> <ctl>...  (first value; BOOL On/Off -> 1/0)
save_ctls() { local f="$1"; shift; : > "$f"; for c in "$@"; do R "tinymix '$c'" >> "$f"; done; }
restore_ctls() {
  local f="$1"; shift; [ -s "$f" ] || return 0
  for c in "$@"; do
    local v; v=$(grep -F "$c:" "$f" | head -1 | sed 's/^[^:]*: //; s/ .*//')
    case "$v" in On) v=1 ;; Off) v=0 ;; esac
    R "tinymix '$c' $v" >/dev/null
  done
  save_ctls "$OUT/restored.txt" "$@"
  if diff -q "$f" "$OUT/restored.txt" >/dev/null; then log "restored OK"
  else log "RESTORE MISMATCH"; diff "$f" "$OUT/restored.txt" | tee -a "$LOG"; fi
}

# stage <device-dir> <file>...: copy files into a root-owned dir on the phone.
stage() {
  local w="$1"; shift
  R "mkdir -p $w && rm -f $w/*.wav $w/*.err"
  for f in "$@"; do
    "${ADB[@]}" push "$f" /data/local/tmp/ >/dev/null && R "mv /data/local/tmp/$(basename "$f") $w/ && chmod 755 $w/$(basename "$f")"
  done
  log "staged: $(R "ls $w" | tr '\n' ' ')"
}

# run_device <command line>: run as root, rewrite device timestamps as T0+seconds.
run_device() {
  R "$*" | while read -r ts msg; do
    case "$ts" in [0-9]*.[0-9]*) printf '%s T0+%5.1fs %s\n' "$(date +%T)" "$(echo "$ts - $T0" | bc)" "$msg" ;;
                  *) printf '%s %s %s\n' "$(date +%T)" "$ts" "$msg" ;; esac
  done | tee -a "$LOG"
}

# pull <device-file> <local-file>: binary-safe copy.
pull() { "${ADB[@]}" exec-out su -c "cat $1" > "$2"; }
