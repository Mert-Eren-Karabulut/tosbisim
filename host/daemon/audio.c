// simrelayd audio pump (M5). Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
//
// While a call is up, two directions run on the sound card's own clock (no sleeps):
//   caller -> client : capture PCM (VOC_REC_DL, 16 kHz mono, 10 ms) -> Opus encode -> audio client
//   client -> caller : audio client -> jitter buffer -> Opus decode (FEC/PLC) -> injection PCM
// The "audio client" is the host app, which bridges these packets to the BLE L2CAP channel; the iPhone
// runs the mirror-image Opus codec. One datagram on the socket = one Opus packet:
//     [u16 seq little-endian][u8 flags][opus payload]
// Opus is sample-rate agnostic at its boundary: we encode 16 kHz (the call-record rate) and decode to
// 48 kHz mono directly (the injection PCM rate), so no handwritten resampler — just duplicate to stereo.
//
// PCM I/O uses the phone's own libtinyalsa via dlopen (see ta_load for why).
// Threads: one always-on I/O thread owns the client socket (accept + read into the jitter buffer); the
// capture and playback threads exist only during a call and block on the PCM, which paces them.
#include "audio.h"

#include <android/log.h>
#include <errno.h>
#include <poll.h>
#include <opus.h>
#include <pthread.h>
#include <sched.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sound/asound.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define TAG "simrelayd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---- PCM I/O through the phone's own libtinyalsa (dlopen) ----
// Upstream tinyalsa 2.0.0, compiled in, misbehaved on SM8250 (15 Oct 2026): in-call capture faulted with
// EFAULT or free-ran at 30x real time, and Incall_Music playback consumed ~20 kHz instead of 48 kHz with
// correct negotiated params. The system /system/lib64/libtinyalsa.so (what stock tinycap/tinyplay use) runs
// both in real time with 10 ms periods and 0 errors. Its pcm_config layout matches; I/O is byte-based.
struct pcm;
struct pcm_config {
    unsigned channels, rate, period_size, period_count;
    int format;
    unsigned start_threshold, stop_threshold, silence_threshold, silence_size;
    int avail_min;
};
#define PCM_OUT 0x00000000u
#define PCM_IN 0x10000000u
#define PCM_FORMAT_S16_LE 0
static struct {
    struct pcm *(*open)(unsigned, unsigned, unsigned, struct pcm_config *);
    int (*is_ready)(struct pcm *);
    const char *(*get_error)(struct pcm *);
    int (*read)(struct pcm *, void *, unsigned);
    int (*write)(struct pcm *, const void *, unsigned);
    int (*close)(struct pcm *);
    int (*prepare)(struct pcm *);
} ta;

static int opus_rate_ok(int r) { return r == 8000 || r == 12000 || r == 16000 || r == 24000 || r == 48000; }

// What a PCM accepts right now (kernel HW_REFINE on the device node): profiles can be wrong or firmware-specific
// (Redmi Note 8 Pro: the profile said 16 kHz inject, but during a call the DSP pins Playback_2 to 48 kHz and every
// other rate fails with EINVAL; 19 Oct). Keeps the wanted rate/channels when accepted, else the nearest Opus rate
// and a channel count in range. Returns 0 when it could ask (device free), -1 otherwise (then: as wanted).
static int pcm_negotiate(int card, int device, int playback, int want_rate, int want_ch, int *rate, int *ch) {
    *rate = want_rate; *ch = want_ch;
    char path[64];
    snprintf(path, sizeof(path), "/dev/snd/pcmC%dD%d%c", card, device, playback ? 'p' : 'c');
    int fd = open(path, O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;
    struct snd_pcm_hw_params p;
    memset(&p, 0, sizeof(p));
    for (int i = 0; i <= SNDRV_PCM_HW_PARAM_LAST_MASK - SNDRV_PCM_HW_PARAM_FIRST_MASK; i++) memset(p.masks[i].bits, 0xff, sizeof(p.masks[i].bits));
    for (int i = 0; i <= SNDRV_PCM_HW_PARAM_LAST_INTERVAL - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL; i++) { p.intervals[i].min = 0; p.intervals[i].max = ~0u; }
    p.rmask = ~0u;
    int ok = ioctl(fd, SNDRV_PCM_IOCTL_HW_REFINE, &p) == 0;
    close(fd);
    if (!ok) return -1;
    const struct snd_interval *r = &p.intervals[SNDRV_PCM_HW_PARAM_RATE - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL];
    const struct snd_interval *c = &p.intervals[SNDRV_PCM_HW_PARAM_CHANNELS - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL];
    if ((unsigned)want_rate < r->min || (unsigned)want_rate > r->max) {
        static const int opus[] = { 48000, 16000, 24000, 12000, 8000 };
        int best = 0;
        for (size_t i = 0; i < sizeof(opus) / sizeof(opus[0]); i++) {
            if ((unsigned)opus[i] < r->min || (unsigned)opus[i] > r->max) continue;
            if (!best || abs(opus[i] - want_rate) < abs(best - want_rate)) best = opus[i];
        }
        if (best) {
            LOGW("pcm %d %s: profile rate %d not accepted now (%u..%u Hz): using %d", device, playback ? "out" : "in",
                 want_rate, r->min, r->max, best);
            *rate = best;
        }
    }
    if ((unsigned)want_ch < c->min) *ch = (int)c->min;
    else if ((unsigned)want_ch > c->max) *ch = (int)c->max;
    if (*ch != want_ch) LOGW("pcm %d %s: %d channel(s) not accepted: using %d", device, playback ? "out" : "in", want_ch, *ch);
    if (*ch > 2) *ch = 2;
    return 0;
}

static int ta_load(void) {
    if (ta.open) return 0;
    // The system's own tinyalsa (what stock tinycap/tinyplay use); vendors keep it in different partitions.
    static const char *paths[] = { "/system/lib64/libtinyalsa.so", "/vendor/lib64/libtinyalsa.so",
                                   "/system_ext/lib64/libtinyalsa.so", "/odm/lib64/libtinyalsa.so" };
    void *h = NULL;
    for (size_t i = 0; i < sizeof(paths) / sizeof(paths[0]) && !h; i++) h = dlopen(paths[i], RTLD_NOW);
    if (!h) { __android_log_print(ANDROID_LOG_ERROR, "simrelayd", "dlopen libtinyalsa: %s", dlerror()); return -1; }
    ta.open = dlsym(h, "pcm_open"); ta.is_ready = dlsym(h, "pcm_is_ready"); ta.get_error = dlsym(h, "pcm_get_error");
    ta.read = dlsym(h, "pcm_read"); ta.write = dlsym(h, "pcm_write"); ta.close = dlsym(h, "pcm_close");
    ta.prepare = dlsym(h, "pcm_prepare");
    if (!ta.open || !ta.is_ready || !ta.get_error || !ta.read || !ta.write || !ta.close || !ta.prepare) {
        __android_log_print(ANDROID_LOG_ERROR, "simrelayd", "libtinyalsa: missing symbols");
        ta.open = NULL; return -1;
    }
    return 0;
}

#define SOCK_NAME "simrelayd-audio"
#define CAP_RATE 16000
#define CAP_FRAME 160          // 10 ms at 16 kHz (one Opus frame)
#define CAP_PERIOD CAP_FRAME   // 10 ms periods, one per read (one Opus frame)
#define CAP_PERIODS 4
#define CAP_READ CAP_PERIOD
#define CAP_FRAMES_PER_READ (CAP_READ / CAP_FRAME)
#define CAP_MAX_ERRORS 600      // consecutive read errors (~60 s at 100 ms backoff) before giving up for this call
#define CAP_SLOW_AFTER 5        // after this many consecutive errors, retry only every 100 ms (protects the DSP)
#define DEC_RATE 48000
#define DEC_FRAME 480          // 10 ms at 48 kHz
#define MAX_PACKET 400
#define HDR 3                  // [u16 seq][u8 flags]
#define BITRATE 24000
#define LOSS_PERC 10

#define JB_SLOTS 64            // ring size (power of two)
#define JB_MASK (JB_SLOTS - 1)
#define JB_WINDOW 200         // pulls (2 s) per adaptive-latency window
#define JB_MAX 8             // above this (80 ms), drop one frame per pull to keep latency low
// Tunable live (TUNE command) so latency trade-offs can be compared within one call (17 Oct 2026):
static volatile int g_jb_prime = 2;      // frames buffered before playback starts
static volatile int g_jb_keep = 1;       // frames of slack kept at the window's minimum depth
static volatile int g_play_period = 480; // inject PCM period in frames at 48 kHz (480 = 10 ms, 240 = 5 ms); 2 periods
#define JB_PRIME g_jb_prime
#define JB_KEEP g_jb_keep

typedef struct { int len; uint8_t data[MAX_PACKET]; } slot_t;

static audio_cfg_t g_cfg;
static int g_allowed_uid = -1;

// Audio client fd, written by the I/O thread, read+written by the capture thread.
static int g_client = -1;
static pthread_mutex_t g_client_lock = PTHREAD_MUTEX_INITIALIZER;

// Jitter buffer (client -> caller), guarded by g_jb_lock.
static pthread_mutex_t g_jb_lock = PTHREAD_MUTEX_INITIALIZER;
static slot_t g_jb[JB_SLOTS];
static int g_jb_valid[JB_SLOTS];
static uint16_t g_jb_next;      // next seq to play
static uint16_t g_jb_highest;
static int g_jb_started;        // seen first packet
static int g_jb_playing;        // past the prime threshold
static int g_jb_win_min = 1000, g_jb_win_n, g_jb_trim;   // adaptive latency: min depth per window, frames to drop

// Call lifecycle.
static volatile int g_call_running = 0;
static pthread_t g_io_t, g_cap_t, g_play_t;
static int g_io_started = 0;

// Stats (reset each 5 s log line).
static unsigned g_st_sent, g_st_recv, g_st_plc, g_st_fec, g_st_late, g_st_catchup, g_st_caperr;
static int g_st_peak;
// Totals since the call began, for the call test (AUDIO?): did the caller's voice reach us, did we play into the call.
static volatile unsigned g_tot_cap, g_tot_voiced, g_tot_sent, g_tot_played;
static volatile int g_tot_peak;
#define VOICED_PEAK 1000          // |sample| above this in a read = someone is talking (silence/comfort noise stays far below)
static unsigned g_st_played, g_st_depth_max;
// Measured time audio spends in our buffers (sums over the 5 s window): jitter-buffer depth at each pull, frames
// queued in the inject PCM after each write, frames waiting in the capture PCM before each read.
static double g_st_jb_sum;
static unsigned g_st_jb_n, g_st_reopen;
static volatile int g_stall_ms;          // debug: one playback stall (STALL command)   // playback frames written; max jitter depth   // max |sample| captured in the window: 0 = the caller path is silent

static void set_realtime(const char *who) {
    struct sched_param sp = { .sched_priority = 2 };
    if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &sp) != 0) LOGW("%s: SCHED_FIFO refused (ok)", who);
}

static int log_throttle(struct timespec *last, int secs) {
    struct timespec now; clock_gettime(CLOCK_MONOTONIC, &now);
    if (now.tv_sec - last->tv_sec < secs) return 0;
    *last = now; return 1;
}

// ------------------------------------------------------------------ client socket

static void client_set(int fd) {
    pthread_mutex_lock(&g_client_lock);
    int old = g_client;
    g_client = fd;
    pthread_mutex_unlock(&g_client_lock);
    if (old >= 0 && old != fd) close(old);
}

static void client_send(const uint8_t *buf, int len) {
    pthread_mutex_lock(&g_client_lock);
    int fd = g_client;
    if (fd >= 0 && send(fd, buf, len, MSG_DONTWAIT | MSG_NOSIGNAL) < 0 && errno != EAGAIN)
        LOGW("audio send: %s", strerror(errno));
    pthread_mutex_unlock(&g_client_lock);
}

// ------------------------------------------------------------------ jitter buffer

static void jb_reset(void) {
    pthread_mutex_lock(&g_jb_lock);
    memset(g_jb_valid, 0, sizeof(g_jb_valid));
    g_jb_started = g_jb_playing = 0;
    g_jb_win_min = 1000; g_jb_win_n = 0; g_jb_trim = 0;
    g_jb_next = g_jb_highest = 0;
    pthread_mutex_unlock(&g_jb_lock);
}


// All sequence arithmetic is signed 16-bit (wraparound-safe): "empty" must never look like "65535 queued".
static int16_t jb_depth(void) { return (int16_t)(g_jb_highest - g_jb_next); }   // < 0 = empty

static void jb_put(uint16_t seq, const uint8_t *data, int len) {
    static unsigned consecutive_late;
    if (len <= 0 || len > MAX_PACKET) return;
    pthread_mutex_lock(&g_jb_lock);
    if (!g_jb_started) { g_jb_started = 1; g_jb_next = seq; g_jb_highest = seq - 1; }
    if ((int16_t)(seq - g_jb_next) < 0) {          // older than the next frame to play
        g_st_late++;
        if (++consecutive_late >= 50) {             // sender restarted its numbering: resync
            memset(g_jb_valid, 0, sizeof(g_jb_valid));
            g_jb_next = seq; g_jb_highest = seq - 1; g_jb_playing = 0; consecutive_late = 0;
            LOGW("audio jitter buffer resync at seq %u", seq);
        } else {
            pthread_mutex_unlock(&g_jb_lock);
            return;
        }
    }
    consecutive_late = 0;
    slot_t *s = &g_jb[seq & JB_MASK];
    s->len = len;
    memcpy(s->data, data, (size_t)len);
    g_jb_valid[seq & JB_MASK] = 1;
    if ((int16_t)(seq - g_jb_highest) > 0) g_jb_highest = seq;
    if (!g_jb_playing && jb_depth() + 1 >= JB_PRIME) g_jb_playing = 1;
    g_st_recv++;
    pthread_mutex_unlock(&g_jb_lock);
}

// What to play for g_jb_next: 0 = nothing (emit silence, don't advance); else mode 0 present,
// 1 FEC (recover from the next packet), 2 PLC (gap with later audio present).
static int jb_take(uint8_t *out, int *outlen, int *mode) {
    pthread_mutex_lock(&g_jb_lock);
    if (!g_jb_playing) { pthread_mutex_unlock(&g_jb_lock); return 0; }
    if (jb_depth() < 0) {                            // drained: underrun -> re-prime before playing again
        g_jb_playing = 0;
        pthread_mutex_unlock(&g_jb_lock);
        return 0;
    }
    uint16_t next = g_jb_next;
    if (g_jb_valid[next & JB_MASK]) {
        slot_t *s = &g_jb[next & JB_MASK];
        memcpy(out, s->data, (size_t)s->len);
        *outlen = s->len;
        *mode = 0;
        g_jb_valid[next & JB_MASK] = 0;
    } else if (g_jb_valid[(uint16_t)(next + 1) & JB_MASK]) {
        slot_t *s = &g_jb[(uint16_t)(next + 1) & JB_MASK];
        memcpy(out, s->data, (size_t)s->len);
        *outlen = s->len;
        *mode = 1;
        g_st_fec++;
    } else {
        *mode = 2;                                   // depth >= 0 here, so later audio exists
        g_st_plc++;
    }
    g_jb_next = next + 1;
    // Adaptive latency: if even the window's minimum depth exceeded JB_KEEP, that much buffering was never
    // needed -> trim it one frame per pull. Converges to the jitter BLE actually has.
    int d = jb_depth();
    g_st_jb_sum += d < 0 ? 0 : d; g_st_jb_n++;
    if (d < g_jb_win_min) g_jb_win_min = d;
    if (++g_jb_win_n >= JB_WINDOW) {
        if (g_jb_win_min > JB_KEEP) g_jb_trim = g_jb_win_min - JB_KEEP;
        g_jb_win_min = 1000; g_jb_win_n = 0;
    }
    if (g_jb_trim > 0 && jb_depth() > JB_KEEP) { g_jb_valid[g_jb_next & JB_MASK] = 0; g_jb_next++; g_jb_trim--; g_st_catchup++; }
    // Hard cap: above JB_MAX buffered, drop one frame per pull (gentle; never a jump).
    if (jb_depth() > JB_MAX) {
        g_jb_valid[g_jb_next & JB_MASK] = 0;
        g_jb_next++;
        g_st_catchup++;
    }
    pthread_mutex_unlock(&g_jb_lock);
    return 1;
}

static void *io_main(void *arg) {
    (void)arg;
    struct sockaddr_un a;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    memcpy(a.sun_path + 1, SOCK_NAME, strlen(SOCK_NAME));
    socklen_t alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(SOCK_NAME));

    int ls = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (ls < 0 || bind(ls, (struct sockaddr *)&a, alen) < 0 || listen(ls, 1) < 0) {
        LOGE("audio socket @%s: %s", SOCK_NAME, strerror(errno));
        return NULL;
    }
    LOGI("audio socket @%s listening", SOCK_NAME);

    // One audio client at a time, but a NEW client replaces the current one: a client that went stale (a remote
    // session whose call ended, an L2CAP bridge whose link died) must never keep the next call's audio out.
    uint8_t buf[HDR + MAX_PACKET];
    int fd = -1;
    for (;;) {
        struct pollfd pf[2] = { { .fd = ls, .events = POLLIN }, { .fd = fd, .events = POLLIN } };
        int n = poll(pf, fd >= 0 ? 2 : 1, -1);
        if (n < 0) { if (errno == EINTR) continue; break; }
        if (pf[0].revents & POLLIN) {
            int nfd = accept4(ls, NULL, NULL, SOCK_CLOEXEC);
            if (nfd >= 0) {
                struct ucred cr;
                socklen_t cl = sizeof(cr);
                if (getsockopt(nfd, SOL_SOCKET, SO_PEERCRED, &cr, &cl) < 0 ||
                    (cr.uid != 0 && (int)cr.uid != g_allowed_uid)) {
                    LOGW("audio client uid=%d REJECTED", cl ? (int)cr.uid : -1);
                    close(nfd);
                } else {
                    LOGI("audio client connected (uid=%d)%s", (int)cr.uid, fd >= 0 ? ", replacing the previous one" : "");
                    client_set(nfd);                     // closes the previous client's fd
                    fd = nfd;
                    jb_reset();
                    continue;
                }
            }
        }
        if (fd >= 0 && (pf[1].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t got = recv(fd, buf, sizeof(buf), 0);
            if (got <= 0) {
                LOGI("audio client gone");
                pthread_mutex_lock(&g_client_lock);
                int mine = g_client == fd;
                if (mine) g_client = -1;
                pthread_mutex_unlock(&g_client_lock);
                if (mine) close(fd);
                fd = -1;
            } else if (got >= HDR) {
                uint16_t seq = (uint16_t)(buf[0] | (buf[1] << 8));
                jb_put(seq, buf + HDR, (int)got - HDR);
            }
        }
    }
    close(ls);
    return NULL;
}

// ------------------------------------------------------------------ capture (caller -> client)

static struct pcm *open_pcm_n(int device, unsigned flags, unsigned rate, unsigned channels, unsigned frame, unsigned periods);
static struct pcm *open_pcm(int device, unsigned flags, unsigned rate, unsigned channels, unsigned frame) {
    return open_pcm_n(device, flags, rate, channels, frame, flags == PCM_IN ? CAP_PERIODS : 2);
}

static struct pcm *open_pcm_n(int device, unsigned flags, unsigned rate, unsigned channels, unsigned frame, unsigned periods) {
    struct pcm_config c = {
        .channels = channels, .rate = rate, .period_size = frame, .period_count = periods,
        .format = PCM_FORMAT_S16_LE,
        // Capture: library defaults. Playback: start after one period (low latency), and never let ALSA stop the
        // stream on its own: the DSP pre-fetches, so the ALSA ring can look empty while audio is still playing.
        // A stop_threshold of one buffer turned that into an underrun on every write: 2/3-speed, robotic uplink
        // for whole calls (17 Oct 2026). INT_MAX is what Qualcomm's own audio HAL uses for its outputs.
        .start_threshold = (flags == PCM_IN) ? 0 : frame,
        .stop_threshold = (flags == PCM_IN) ? 0 : 0x7fffffffu, .silence_threshold = 0,
    };
    if (ta_load() < 0) return NULL;
    struct pcm *pcm = ta.open((unsigned)g_cfg.card, (unsigned)device, flags, &c);
    if (!pcm || !ta.is_ready(pcm)) {
        LOGE("pcm_open card %d dev %d (%s): %s", g_cfg.card, device, flags == PCM_IN ? "in" : "out",
             pcm ? ta.get_error(pcm) : "null");
        if (pcm) ta.close(pcm);
        return NULL;
    }
    return pcm;
}

static void *capture_main(void *arg) {
    (void)arg;
    set_realtime("capture");
    int err = 0;
    // Rate and channels from the profile (Opus: 8/12/16/24/48 kHz); a stereo capture is mixed down to mono.
    int crate = opus_rate_ok(g_cfg.capture_rate) ? g_cfg.capture_rate : CAP_RATE;
    int cch = g_cfg.capture_channels == 2 ? 2 : 1;
    pcm_negotiate(g_cfg.card, g_cfg.capture_pcm, 0, crate, cch, &crate, &cch);
    const int cframe = crate / 100;                  // 10 ms
    OpusEncoder *enc = opus_encoder_create(crate, 1, OPUS_APPLICATION_VOIP, &err);
    if (!enc) { LOGE("opus encoder: %d", err); return NULL; }
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(BITRATE));
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(LOSS_PERC));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));

    struct pcm *pcm = open_pcm(g_cfg.capture_pcm, PCM_IN, (unsigned)crate, (unsigned)cch, (unsigned)cframe);
    if (!pcm) { opus_encoder_destroy(enc); return NULL; }

    int16_t pcmbuf[480 * 2];
    uint8_t pkt[HDR + MAX_PACKET];
    uint16_t seq = 0;
    struct timespec t0;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    unsigned frames = 0;

    struct timespec err_log = { 0 };
    struct timespec backoff = { .tv_nsec = 10 * 1000 * 1000 };    // 10 ms: don't spin when the backend isn't streaming
    // One 10 ms period per read, paced by the DSP. Errors are rare with the system tinyalsa; the cap below
    // still stops a failing stream from storming the audio DSP (a storm rebooted the phone once).
    unsigned consecutive_err = 0;
    while (g_call_running) {
        if (!pcm) {
            struct timespec slow = { .tv_nsec = 100 * 1000 * 1000 };
            pcm = open_pcm(g_cfg.capture_pcm, PCM_IN, (unsigned)crate, (unsigned)cch, (unsigned)cframe);
            if (!pcm) {
                if (++consecutive_err >= CAP_MAX_ERRORS) { LOGE("capture: cannot reopen, giving up for this call"); break; }
                nanosleep(&slow, NULL);
                continue;
            }
        }
        int got = ta.read(pcm, pcmbuf, (unsigned)(cframe * cch) * sizeof(int16_t)) == 0 ? cframe : -1;
        if (got != cframe) {                  // error: don't encode a partial/garbage frame
            if (log_throttle(&err_log, 2)) LOGW("capture read failed: %s", ta.get_error(pcm));
            if (++consecutive_err >= CAP_MAX_ERRORS) {
                LOGE("capture: %u consecutive errors, giving up for this call (protecting the audio DSP)", consecutive_err);
                break;
            }
            g_st_caperr++;
            if (consecutive_err >= CAP_SLOW_AFTER) {     // e.g. outgoing call still dialing: voice path not up yet
                struct timespec slow = { .tv_nsec = 100 * 1000 * 1000 };
                nanosleep(&slow, NULL);
            }
            // Isolated fault: that 10 ms is lost; advance seq so the receiver conceals it.
            seq += CAP_FRAMES_PER_READ;
            if (consecutive_err == 1) continue;
            // Repeated faults: reopen the stream (fresh hw params).
            ta.close(pcm);
            nanosleep(&backoff, NULL);
            pcm = open_pcm(g_cfg.capture_pcm, PCM_IN, (unsigned)crate, (unsigned)cch, (unsigned)cframe);
            continue;                                   // NULL pcm is retried at the top of the loop
        }
        consecutive_err = 0;
        if (cch == 2) for (int i = 0; i < cframe; i++) pcmbuf[i] = (int16_t)(((int)pcmbuf[2 * i] + pcmbuf[2 * i + 1]) / 2);
        int rpeak = 0;
        for (int i = 0; i < cframe; i++) { int a = pcmbuf[i] < 0 ? -pcmbuf[i] : pcmbuf[i]; if (a > rpeak) rpeak = a; }
        if (rpeak > g_st_peak) g_st_peak = rpeak;
        if (rpeak > g_tot_peak) g_tot_peak = rpeak;
        g_tot_cap += CAP_FRAMES_PER_READ;
        if (rpeak > VOICED_PEAK) g_tot_voiced += CAP_FRAMES_PER_READ;
        for (int f = 0; f < CAP_FRAMES_PER_READ; f++) {
        int len = opus_encode(enc, pcmbuf + f * cframe, cframe, pkt + HDR, MAX_PACKET);
        if (len < 0) { LOGW("opus encode: %d", len); continue; }
        if (len <= 2) { seq++; continue; }    // DTX: nothing to send, but keep seq = time
        pkt[0] = (uint8_t)(seq & 0xff);
        pkt[1] = (uint8_t)(seq >> 8);
        pkt[2] = 0;
        client_send(pkt, HDR + len);
        if (g_cfg.loopback) jb_put(seq, pkt + HDR, len);
        seq++;
        g_st_sent++;
        g_tot_sent++;
        }
        frames += CAP_FRAMES_PER_READ;
        if (frames >= 500) {     // ~5 s
            struct timespec now; clock_gettime(CLOCK_MONOTONIC, &now);
            double s = (now.tv_sec - t0.tv_sec) + (now.tv_nsec - t0.tv_nsec) / 1e9;
            LOGI("audio 5s: sent=%u recv=%u fec=%u plc=%u late=%u catchup=%u caperr=%u peak=%d played=%u depthmax=%u (%.1f fps) "
                 "| held: jb=%.1fms reopen=%u (tune prime=%d keep=%d period=%d)",
                 g_st_sent, g_st_recv, g_st_fec, g_st_plc, g_st_late, g_st_catchup, g_st_caperr, g_st_peak,
                 g_st_played, g_st_depth_max, frames / s,
                 g_st_jb_n ? g_st_jb_sum / g_st_jb_n * 10.0 : -1.0, g_st_reopen,
                 g_jb_prime, g_jb_keep, g_play_period);
            g_st_jb_sum = 0; g_st_jb_n = 0; g_st_reopen = 0;
            g_st_sent = g_st_recv = g_st_fec = g_st_plc = g_st_late = g_st_catchup = g_st_caperr = 0;
            g_st_peak = 0; g_st_played = 0; g_st_depth_max = 0;
            frames = 0; t0 = now;
        }
    }
    if (pcm) ta.close(pcm);
    opus_encoder_destroy(enc);
    return NULL;
}

// ------------------------------------------------------------------ playback (client -> caller)

static void *playback_main(void *arg) {
    (void)arg;
    set_realtime("playback");
    int err = 0;
    // Decode straight at the inject PCM's rate (Opus: 8/12/16/24/48 kHz), 10 ms per write at any rate.
    const int want = opus_rate_ok(g_cfg.inject_rate) ? g_cfg.inject_rate : DEC_RATE;
    if (want != g_cfg.inject_rate) LOGW("inject rate %d isn't an Opus rate: playing at %d", g_cfg.inject_rate, want);
    int drate = want, ich = g_cfg.inject_channels == 2 ? 2 : 1;
    pcm_negotiate(g_cfg.card, g_cfg.inject_pcm, 1, want, ich, &drate, &ich);
    int dframe = drate / 100;
    OpusDecoder *dec = opus_decoder_create(drate, 1, &err);
    if (!dec) { LOGE("opus decoder: %d", err); return NULL; }

    int base = g_play_period;                          // TUNE periods are given in 48 kHz frames
    int period = base * drate / 48000;
    struct pcm *pcm = open_pcm(g_cfg.inject_pcm, PCM_OUT, (unsigned)drate, (unsigned)ich, (unsigned)period);
    for (int tries = 1; !pcm && g_call_running; tries++) {     // the voice path may not be up yet: retry, don't give up
        struct timespec wait = { .tv_nsec = 200 * 1000 * 1000 };
        nanosleep(&wait, NULL);
        // The voice path's constraints appear once the modem side runs: ask again, follow a changed rate.
        int r2 = drate, c2 = ich;
        if (pcm_negotiate(g_cfg.card, g_cfg.inject_pcm, 1, want, ich, &r2, &c2) == 0 && (r2 != drate || c2 != ich)) {
            OpusDecoder *d2 = opus_decoder_create(r2, 1, &err);
            if (d2) { opus_decoder_destroy(dec); dec = d2; drate = r2; ich = c2; dframe = drate / 100; period = base * drate / 48000; }
        }
        pcm = open_pcm_n(g_cfg.inject_pcm, PCM_OUT, (unsigned)drate, (unsigned)ich, (unsigned)period, 2);
        if (pcm) LOGI("inject PCM opened after %d tries", tries + 1);
    }
    if (!pcm) { opus_decoder_destroy(dec); return NULL; }

    int16_t mono[480];
    int16_t out[480 * 2];
    uint8_t pkt[MAX_PACKET];
    unsigned periods = 2, win_frames = 0, slow_windows = 0;
    struct timespec win_t0;
    clock_gettime(CLOCK_MONOTONIC, &win_t0);
    struct timespec werr_log = { 0 };
    struct timespec wbackoff = { .tv_nsec = 10 * 1000 * 1000 };

    while (g_call_running) {
        if (base != g_play_period) {                    // TUNE changed the period: reopen with the new size
            ta.close(pcm);
            base = g_play_period;
            period = base * drate / 48000;
            pcm = open_pcm_n(g_cfg.inject_pcm, PCM_OUT, (unsigned)drate, (unsigned)ich, (unsigned)period, periods);
            LOGI("inject PCM reopened with %d-frame periods: %s", period, pcm ? "ok" : "FAILED");
            if (!pcm) {                                 // fall back to the proven 10 ms
                g_play_period = base = 480; period = dframe;
                pcm = open_pcm(g_cfg.inject_pcm, PCM_OUT, (unsigned)drate, (unsigned)ich, (unsigned)dframe);
                if (!pcm) break;
            }
        }
        int len = 0, mode = 2;
        int have = jb_take(pkt, &len, &mode);
        int samples;
        if (!have) {
            // Nothing to play yet (priming / idle): emit one frame of silence to keep the clock.
            samples = dframe;
            memset(mono, 0, sizeof(mono));
        } else if (mode == 0) {
            samples = opus_decode(dec, pkt, len, mono, dframe, 0);
        } else if (mode == 1) {
            samples = opus_decode(dec, pkt, len, mono, dframe, 1);   // FEC from next packet
        } else {
            samples = opus_decode(dec, NULL, 0, mono, dframe, 0);    // PLC
        }
        if (samples < 0) { samples = dframe; memset(mono, 0, sizeof(mono)); }

        g_st_played++;
        g_tot_played++;
        { pthread_mutex_lock(&g_jb_lock); int d = jb_depth();
          if (g_jb_playing && d > (int)g_st_depth_max) g_st_depth_max = (unsigned)d; pthread_mutex_unlock(&g_jb_lock); }
        if (ich == 2) {
            for (int i = 0; i < samples; i++) { out[2 * i] = mono[i]; out[2 * i + 1] = mono[i]; }
            if (ta.write(pcm, out, (unsigned)samples * 2 * sizeof(int16_t)) != 0) { if (log_throttle(&werr_log, 2)) LOGW("inject write: %s", ta.get_error(pcm)); ta.prepare(pcm); nanosleep(&wbackoff, NULL); }
        } else {
            if (ta.write(pcm, mono, (unsigned)samples * sizeof(int16_t)) != 0) { if (log_throttle(&werr_log, 2)) LOGW("inject write: %s", ta.get_error(pcm)); ta.prepare(pcm); nanosleep(&wbackoff, NULL); }
        }
        // Self-healing: the write loop is paced by the DSP, so a healthy stream runs at 100 frames/s. Below 90/s
        // for two seconds running means the stream is stuck in a bad state: reopen it, with one more period of
        // headroom each time it happens again in this call (2 -> 3 -> 4 periods).
        if (++win_frames >= 100) {
            struct timespec now; clock_gettime(CLOCK_MONOTONIC, &now);
            double secs = (now.tv_sec - win_t0.tv_sec) + (now.tv_nsec - win_t0.tv_nsec) / 1e9;
            slow_windows = (secs > 100 / 90.0) ? slow_windows + 1 : 0;
            if (slow_windows >= 2 && pcm) {
                if (periods < 4) periods++;
                LOGW("inject stream running at %.0f frames/s: reopening with %u periods", 100 / secs, periods);
                ta.close(pcm);
                pcm = open_pcm_n(g_cfg.inject_pcm, PCM_OUT, (unsigned)drate, (unsigned)ich,
                                 (unsigned)period, periods);
                g_st_reopen++;
                slow_windows = 0;
                if (!pcm) break;
            }
            win_frames = 0;
            clock_gettime(CLOCK_MONOTONIC, &win_t0);
        }
        if (g_stall_ms > 0) {                            // debug (STALL): reproduce a scheduling hiccup
            struct timespec st = { .tv_sec = g_stall_ms / 1000, .tv_nsec = (g_stall_ms % 1000) * 1000000L };
            LOGW("debug stall %d ms", g_stall_ms);
            g_stall_ms = 0;
            nanosleep(&st, NULL);
        }
    }
    if (pcm) ta.close(pcm);
    opus_decoder_destroy(dec);
    return NULL;
}

// ------------------------------------------------------------------ lifecycle

void audio_init(int allowed_uid) {
    g_allowed_uid = allowed_uid;
    if (pthread_create(&g_io_t, NULL, io_main, NULL) == 0) g_io_started = 1;
    else LOGE("audio io thread: %s", strerror(errno));
}

int audio_tune(int prime, int keep, int period) {
    if (prime < 1 || prime > 8 || keep < 0 || keep > 6 || (period != 240 && period != 480 && period != 960)) return -1;
    g_jb_prime = prime; g_jb_keep = keep; g_play_period = period;
    LOGI("audio tune: prime=%d keep=%d period=%d", prime, keep, period);
    return 0;
}

void audio_stall(int ms) { if (ms > 0 && ms <= 500) g_stall_ms = ms; }

void audio_tune_get(char *buf, size_t n) {
    snprintf(buf, n, "prime=%d keep=%d period=%d", g_jb_prime, g_jb_keep, g_play_period);
}

void audio_totals(char *buf, size_t n) {
    snprintf(buf, n, "cap=%u voiced=%u sent=%u played=%u peak=%d", g_tot_cap, g_tot_voiced, g_tot_sent, g_tot_played, g_tot_peak);
}

void audio_call_begin(const audio_cfg_t *cfg) {
    g_tot_cap = g_tot_voiced = g_tot_sent = g_tot_played = 0;
    g_tot_peak = 0;
    if (g_call_running) audio_call_end();
    g_cfg = *cfg;
    jb_reset();
    g_call_running = 1;
    if (pthread_create(&g_cap_t, NULL, capture_main, NULL) != 0) LOGE("capture thread: %s", strerror(errno));
    if (pthread_create(&g_play_t, NULL, playback_main, NULL) != 0) LOGE("playback thread: %s", strerror(errno));
    LOGI("audio call begin (loopback=%d)", g_cfg.loopback);
}

void audio_call_end(void) {
    if (!g_call_running) return;
    g_call_running = 0;
    pthread_join(g_cap_t, NULL);
    pthread_join(g_play_t, NULL);
    char t[160];
    audio_totals(t, sizeof(t));            // what flowed each way, for every call (diagnosis without a client)
    LOGI("audio call end: %s", t);
}

void audio_shutdown(void) {
    audio_call_end();
}
