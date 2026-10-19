// simrelayd audio pump (M5). Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
#include <stddef.h>
#ifndef SIMRELAYD_AUDIO_H
#define SIMRELAYD_AUDIO_H

// Configuration passed from the loaded device profile (host app -> SET lines -> here).
typedef struct {
    int card;
    int capture_pcm;        // e.g. 0  (VOC_REC_DL front end)
    int capture_rate;       // e.g. 16000 (Opus rates only); 0 = 16000
    int capture_channels;   // 1, or 2 (mixed down)
    int inject_pcm;         // e.g. 23 (Incall_Music front end)
    int inject_rate;        // e.g. 48000
    int inject_channels;    // e.g. 2
    int loopback;           // self-test: captured caller audio is also played back into the call
} audio_cfg_t;

// Start the always-on audio I/O thread and open the audio client socket "@simrelayd-audio".
// allowed_uid mirrors the control socket's SO_PEERCRED check. Call once from main().
void audio_init(int allowed_uid);

// Begin / end streaming for a call. Safe to call repeatedly; begin on an already-running pump
// restarts it. These run from the control thread (call_start / restore_all).
void audio_call_begin(const audio_cfg_t *cfg);
void audio_call_end(void);
void audio_totals(char *buf, size_t n);     // since audio_call_begin: frames captured/voiced/sent/played, peak
// Latency trade-offs, live: jitter-buffer prime / keep frames and the inject PCM period (240/480/960 frames).
int audio_tune(int prime, int keep, int period);
void audio_tune_get(char *buf, size_t n);
void audio_stall(int ms);                   // debug: stall the inject loop once (tests self-healing)

// Stop everything and free resources (daemon exit).
void audio_shutdown(void);

#endif
