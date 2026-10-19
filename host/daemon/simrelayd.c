// simrelayd — SIM Relay host audio daemon. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
//
// The only root process of the host. v0 owns the in-call mixer state (PCM streaming comes in M5):
//   CALL_START <session>  apply the profile's routes for that voice session (capture, injection,
//                         host mic + earpiece device mutes), remembering each control's original value
//   CALL_END              restore every touched control and clear both device mutes
// Device-specific names come from the client (the host app reads profiles/<soc>/<model>.json and
// sends SET lines), so nothing here is phone-specific except the Qualcomm device-mute defaults.
//
// Safety: the kernel keeps "Voice Tx/Rx Device Mute" per voice session across calls, so both are
// cleared at start-up, on exit, on CALL_END and when the client disconnects mid-call (fail-safe:
// the phone falls back to normal behaviour rather than staying muted).
// Upkeep: subscribes to ALSA control events and re-applies a managed control the moment someone
// else (the vendor HAL) rewrites it — no polling, no fixed sleeps.
//
// Protocol: newline-terminated text lines over the abstract unix socket "@simrelayd"; one client at
// a time, which must be root or the uid given with --uid (checked with SO_PEERCRED).
//   HELLO | STATUS | SET <key> <value> | CALL_START <session> | CALL_END   ->   OK ... / ERR ...
//   IFSTATS <iface>  -> OK rx=<bytes> tx=<bytes>   (hotspot data counter; sysfs needs root)
//   NEIGH <iface>    -> OK <n> <mac>,<ip>;...      (hotspot devices from the kernel ARP table; Android 13
//                                                   withholds TetheredClient details without NETWORK_SETTINGS)
//   SET keys: capture, rec_config, inject.1, inject.2, mute.tx, mute.rx, mute.vsid, mute.ramp
//   HAS <control>  -> OK count=n | ERR       (auto profiles: confirm a control exists on this card)
//   TUNE [p k per] -> OK prime= keep= period=   (jitter-buffer frames, inject PCM period; live)
//   AUDIO?         -> OK call= session= silence= cap= voiced= sent= played= peak=   (call test totals)
//   Box mode (box.c): SCREEN_OFF | WAKE | POWERKEY 1|0 | ROLE <role> <pkg> | CHARGE <percent> | CHARGE? -> OK <node>|ERR
//                     NETBLOCK <uid> <0|1> (iptables chain tosbisim_block)
//                     + the Volume Up x3 / Volume Down x3 escape gesture, reported to the app by broadcast
// Debug client: simrelayd --client "CMD|SLEEP n|CMD..." (one connection)

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#include <sound/asound.h>

#include "audio.h"
#include "box.h"

#define VERSION "0.1"
#define TAG "simrelayd"
#define SOCK_NAME "simrelayd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define NAME_LEN 44            // SNDRV_CTL_ELEM_ID_NAME_MAXLEN
#define MAX_MANAGED 16
#define MAX_VALUES 4
#define OWN_WRITE_GRACE_NS 100000000LL   // ignore change events within 100 ms of our own write

typedef struct {
    char name[NAME_LEN];
    unsigned numid;
    unsigned count;
    long long want[MAX_VALUES];
    int nwant;
    long long orig[MAX_VALUES];
    int write_only;            // device mutes: no get handler, reads back -1, restore = unmute
    int enumerated;            // ENUM controls use value.enumerated.item, not value.integer.value
    struct timespec last_write;
} managed_t;

static int g_card = 0;
static int g_ctl = -1;
static uid_t g_allowed_uid = (uid_t)-1;
static managed_t g_m[MAX_MANAGED];
static int g_nm = 0;
// Host silence: controls held for the daemon's whole lifetime (e.g. speaker/earpiece amplifier mutes),
// independent of calls. Fail-silent: if the daemon dies they stay muted until reboot.
static managed_t g_perm[MAX_MANAGED];
static int g_np = 0;
static int g_call = 0, g_session = 0;
static unsigned g_remutes = 0;        // device-mute re-asserts this call (see drain_ctl_events)
static volatile sig_atomic_t g_stop = 0;

static struct {
    char capture[NAME_LEN], rec_config[NAME_LEN], inject1[NAME_LEN], inject2[NAME_LEN];
    char mute_tx[NAME_LEN], mute_rx[NAME_LEN];
    long long vsid, ramp;
    int capture_pcm, capture_rate, capture_channels, inject_pcm, inject_rate, inject_channels, loopback;
} p = { .capture_rate = 16000, .capture_channels = 1, .mute_tx = "Voice Tx Device Mute", .mute_rx = "Voice Rx Device Mute",
        .vsid = 4294967295LL /* ALL_SESSION_VSID */, .ramp = 20,
        .capture_pcm = 0, .inject_pcm = 23, .inject_rate = 48000, .inject_channels = 2 };

// ------------------------------------------------------------------ ALSA controls

static int ctl_info(const char *name, struct snd_ctl_elem_info *info) {
    memset(info, 0, sizeof(*info));
    if (g_ctl < 0 || !name || !*name) return -1;     // no mixer on this phone, or no control named
    info->id.iface = SNDRV_CTL_ELEM_IFACE_MIXER;
    strncpy((char *)info->id.name, name, sizeof(info->id.name) - 1);
    return ioctl(g_ctl, SNDRV_CTL_IOCTL_ELEM_INFO, info);
}

static int ctl_read_t(unsigned numid, long long *out, unsigned count, int enumerated) {
    struct snd_ctl_elem_value v;
    memset(&v, 0, sizeof(v));
    v.id.numid = numid;
    if (g_ctl < 0 || ioctl(g_ctl, SNDRV_CTL_IOCTL_ELEM_READ, &v) < 0) return -1;
    for (unsigned i = 0; i < count && i < MAX_VALUES; i++)
        out[i] = enumerated ? (long long)v.value.enumerated.item[i] : v.value.integer.value[i];
    return 0;
}

static int ctl_write(managed_t *m, const long long *vals, int n) {
    struct snd_ctl_elem_value v;
    memset(&v, 0, sizeof(v));
    v.id.numid = m->numid;
    for (int i = 0; i < n && i < MAX_VALUES; i++) {
        if (m->enumerated) v.value.enumerated.item[i] = (unsigned)vals[i];
        else v.value.integer.value[i] = vals[i];
    }
    clock_gettime(CLOCK_MONOTONIC, &m->last_write);
    if (g_ctl < 0 || ioctl(g_ctl, SNDRV_CTL_IOCTL_ELEM_WRITE, &v) < 0) {
        LOGE("write '%s' failed: %s", m->name, strerror(errno));
        return -1;
    }
    return 0;
}

// Write a control by name without managing it (start-up / exit safety).
static void ctl_set_by_name(const char *name, const long long *vals, int n) {
    struct snd_ctl_elem_info info;
    if (!*name || ctl_info(name, &info) < 0) return;
    managed_t tmp = { .numid = info.id.numid };
    strncpy(tmp.name, name, NAME_LEN - 1);
    if (ctl_write(&tmp, vals, n) == 0) LOGI("set '%s' (%lld ...)", name, vals[0]);
}

static void clear_device_mutes(void) {
    long long off[3] = { 0, p.vsid, p.ramp };
    ctl_set_by_name(p.mute_tx, off, 3);
    ctl_set_by_name(p.mute_rx, off, 3);
}

static managed_t *manage_in(managed_t *list, int *count, const char *name, const long long *want, int n, int write_only) {
    struct snd_ctl_elem_info info;
    if (!*name) return NULL;
    if (ctl_info(name, &info) < 0) { LOGW("no control '%s'", name); return NULL; }
    if (*count == MAX_MANAGED) { LOGE("too many managed controls"); return NULL; }
    managed_t *m = &list[(*count)++];
    memset(m, 0, sizeof(*m));
    strncpy(m->name, name, NAME_LEN - 1);
    m->numid = info.id.numid;
    m->count = info.count;
    m->write_only = write_only;
    m->enumerated = info.type == SNDRV_CTL_ELEM_TYPE_ENUMERATED;
    m->nwant = n;
    memcpy(m->want, want, sizeof(long long) * n);
    if (!write_only) ctl_read_t(m->numid, m->orig, m->count, m->enumerated);
    ctl_write(m, want, n);
    LOGI("managed '%s' numid=%u -> %lld (orig %lld)", name, m->numid, want[0], m->orig[0]);
    return m;
}

static managed_t *manage(const char *name, const long long *want, int n, int write_only) {
    return manage_in(g_m, &g_nm, name, want, n, write_only);
}

// "SET call <control name>=<v1>[,<v2>...]": set during a call, restored after. How a non-Qualcomm platform (MediaTek,
// Exynos) routes call audio is then data in its profile, not code.
#define MAX_CALL_CONTROLS 16
static struct { char name[NAME_LEN]; long long v[MAX_VALUES]; int n; } g_cc[MAX_CALL_CONTROLS];
static int g_ncc = 0;
static int call_control_add(const char *spec) {
    const char *eq = strrchr(spec, '=');
    if (!eq || eq == spec || g_ncc >= MAX_CALL_CONTROLS) return -1;
    size_t len = (size_t)(eq - spec);
    if (len >= NAME_LEN) return -1;
    memcpy(g_cc[g_ncc].name, spec, len);
    g_cc[g_ncc].name[len] = 0;
    int n = 0;
    for (const char *q = eq + 1; *q && n < MAX_VALUES; ) {
        char *end;
        g_cc[g_ncc].v[n++] = strtoll(q, &end, 0);
        if (*end != ',') break;
        q = end + 1;
    }
    g_cc[g_ncc++].n = n;
    return 0;
}

// "SET silence <control name>=<value>": hold a control at a value for the daemon's lifetime.
static int silence_add(const char *spec) {
    const char *eq = strrchr(spec, '=');
    if (!eq || eq == spec) return -1;
    char name[NAME_LEN];
    size_t len = (size_t)(eq - spec);
    if (len >= NAME_LEN) return -1;
    memcpy(name, spec, len);
    name[len] = 0;
    long long v = strtoll(eq + 1, NULL, 0);
    for (int i = 0; i < g_np; i++)
        if (strcmp(g_perm[i].name, name) == 0) { g_perm[i].want[0] = v; ctl_write(&g_perm[i], &v, 1); return 0; }
    return manage_in(g_perm, &g_np, name, &v, 1, 0) ? 0 : -1;
}

static void restore_all(const char *why) {
    audio_call_end();
    for (int i = 0; i < g_nm; i++) {
        managed_t *m = &g_m[i];
        if (m->write_only) {
            long long off[3] = { 0, p.vsid, p.ramp };
            ctl_write(m, off, 3);
        } else {
            ctl_write(m, m->orig, (int)(m->count < MAX_VALUES ? m->count : MAX_VALUES));
        }
        LOGI("restored '%s' (%s)", m->name, why);
    }
    g_nm = 0;
    g_call = 0;
    g_session = 0;
    clear_device_mutes();
}

static int call_start(int session) {
    if (g_call && session == g_session) {      // duplicate (e.g. host app reconnect): keep the running call
        LOGI("CALL_START session=%d: already active, ignored", session);
        return g_nm;
    }
    if (g_call) restore_all("new CALL_START");
    const char *inject = session == 2 ? p.inject2 : p.inject1;
    long long on = 1, mono = 1;
    long long mute[3] = { 1, p.vsid, p.ramp };
    int applied = 0;
    applied += manage(p.rec_config, &mono, 1, 0) != NULL;
    applied += manage(p.capture, &on, 1, 0) != NULL;
    applied += manage(inject, &on, 1, 0) != NULL;
    applied += manage(p.mute_tx, mute, 3, 1) != NULL;
    applied += manage(p.mute_rx, mute, 3, 1) != NULL;
    for (int i = 0; i < g_ncc; i++) applied += manage(g_cc[i].name, g_cc[i].v, g_cc[i].n, 0) != NULL;
    g_call = 1;
    g_session = session;
    g_remutes = 0;
    audio_cfg_t ac = { .card = g_card, .capture_pcm = p.capture_pcm, .capture_rate = p.capture_rate,
                       .capture_channels = p.capture_channels, .inject_pcm = p.inject_pcm,
                       .inject_rate = p.inject_rate, .inject_channels = p.inject_channels, .loopback = p.loopback };
    audio_call_begin(&ac);
    LOGI("CALL_START session=%d: %d controls applied", session, applied);
    return applied;
}

static long long ns_since(const struct timespec *t) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (now.tv_sec - t->tv_sec) * 1000000000LL + (now.tv_nsec - t->tv_nsec);
}

// A control changed: if it is one of ours and someone else changed it, put our value back.
static void reapply_in(managed_t *list, int count, unsigned numid);
static void on_ctl_event(unsigned numid) {
    reapply_in(g_m, g_nm, numid);
    reapply_in(g_perm, g_np, numid);
}

static void reapply_in(managed_t *list, int count, unsigned numid) {
    for (int i = 0; i < count; i++) {
        managed_t *m = &list[i];
        if (m->numid != numid || ns_since(&m->last_write) < OWN_WRITE_GRACE_NS) continue;
        if (!m->write_only) {
            long long cur[MAX_VALUES] = { 0 };
            if (ctl_read_t(numid, cur, m->count, m->enumerated) == 0 && memcmp(cur, m->want, sizeof(long long) * m->nwant) == 0) return;
        }
        LOGW("'%s' rewritten by someone else, re-applying", m->name);
        ctl_write(m, m->want, m->nwant);
    }
}

// The device mutes are write-only (no read-back, so a reset can't be detected), and the voice session may still be
// starting when CALL_START applies them (audio begins at DIALING): a session start can wipe them, and the host's
// earpiece then plays the caller (heard on r8q, 16 Oct 2026). So during a call they are re-asserted after every
// burst of mixer changes by someone else (voice start, route and device switches all show up as control events).
static int is_device_mute(unsigned numid) {
    for (int i = 0; i < g_nm; i++) if (g_m[i].write_only && g_m[i].numid == numid) return 1;
    return 0;
}

static void reassert_device_mutes(void) {
    for (int i = 0; i < g_nm; i++) if (g_m[i].write_only) ctl_write(&g_m[i], g_m[i].want, g_m[i].nwant);
    if (g_remutes++ < 20 || g_remutes % 100 == 0) LOGI("device mutes re-asserted after a route change (#%u)", g_remutes);
}

static void drain_ctl_events(void) {
    struct snd_ctl_event ev;
    int route_changed = 0;
    while (g_ctl >= 0 && read(g_ctl, &ev, sizeof(ev)) == (ssize_t)sizeof(ev)) {
        if (ev.type == SNDRV_CTL_EVENT_ELEM && (ev.data.elem.mask & SNDRV_CTL_EVENT_MASK_VALUE)
            && ev.data.elem.mask != SNDRV_CTL_EVENT_MASK_REMOVE) {
            on_ctl_event(ev.data.elem.id.numid);
            if (g_call && !is_device_mute(ev.data.elem.id.numid)) route_changed = 1;
        }
    }
    if (route_changed) reassert_device_mutes();
}

// ------------------------------------------------------------------ protocol

static void reply(int fd, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
static void reply(int fd, const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf) - 1, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if (n > (int)sizeof(buf) - 2) n = sizeof(buf) - 2;
    buf[n++] = '\n';
    if (write(fd, buf, n) < 0) LOGW("reply failed: %s", strerror(errno));
}

static int valid_iface(const char *iface) {
    if (!iface || !*iface || strlen(iface) > 15) return 0;
    for (const char *c = iface; *c; c++)
        if (!((*c >= 'a' && *c <= 'z') || (*c >= '0' && *c <= '9') || *c == '_' || *c == '-')) return 0;
    return 1;
}

// Interface byte counters from sysfs. The name is validated: this runs as root.
static int ifstats(const char *iface, long long *rx, long long *tx) {
    if (!valid_iface(iface)) return -1;
    const char *names[2] = { "rx_bytes", "tx_bytes" };
    long long *out[2] = { rx, tx };
    for (int i = 0; i < 2; i++) {
        char path[96];
        snprintf(path, sizeof(path), "/sys/class/net/%s/statistics/%s", iface, names[i]);
        FILE *f = fopen(path, "re");
        if (!f) return -1;
        int ok = fscanf(f, "%lld", out[i]) == 1;
        fclose(f);
        if (!ok) return -1;
    }
    return 0;
}

// Complete (flags 0x2) ARP entries on an interface: the devices on the hotspot. A device that left stays
// until the kernel expires the entry (about a minute), which is fine for a once-a-minute check.
static int neigh(const char *iface, char *out, size_t outlen, int *count) {
    if (!valid_iface(iface)) return -1;
    FILE *f = fopen("/proc/net/arp", "re");
    if (!f) return -1;
    char line[256], ip[64], hw[64], dev[32];
    unsigned type, flags;
    *count = 0;
    out[0] = 0;
    if (!fgets(line, sizeof(line), f)) { fclose(f); return -1; }            // header
    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "%63s 0x%x 0x%x %63s %*s %31s", ip, &type, &flags, hw, dev) != 5) continue;
        if (strcmp(dev, iface) != 0 || !(flags & 0x2)) continue;
        size_t used = strlen(out);
        snprintf(out + used, outlen - used, "%s%s,%s", *count ? ";" : "", hw, ip);
        (*count)++;
    }
    fclose(f);
    return 0;
}

static int set_key(const char *key, const char *val) {
    struct { const char *k; char *dst; } names[] = {
        { "capture", p.capture }, { "rec_config", p.rec_config }, { "inject.1", p.inject1 },
        { "inject.2", p.inject2 }, { "mute.tx", p.mute_tx }, { "mute.rx", p.mute_rx },
    };
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); i++) {
        if (strcmp(key, names[i].k) == 0) {
            if (strcmp(val, "-") == 0) { names[i].dst[0] = 0; return 0; }     // not used on this platform
            strncpy(names[i].dst, val, NAME_LEN - 1);
            names[i].dst[NAME_LEN - 1] = 0;
            return 0;
        }
    }
    if (strcmp(key, "mute.vsid") == 0) { p.vsid = strtoll(val, NULL, 0); return 0; }
    if (strcmp(key, "mute.ramp") == 0) { p.ramp = strtoll(val, NULL, 0); return 0; }
    if (strcmp(key, "capture.pcm") == 0) { p.capture_pcm = atoi(val); return 0; }
    if (strcmp(key, "inject.pcm") == 0) { p.inject_pcm = atoi(val); return 0; }
    if (strcmp(key, "capture.rate") == 0) { p.capture_rate = atoi(val); return 0; }
    if (strcmp(key, "capture.channels") == 0) { p.capture_channels = atoi(val); return 0; }
    if (strcmp(key, "call") == 0) return call_control_add(val);
    if (strcmp(key, "call.clear") == 0) { g_ncc = 0; return 0; }
    if (strcmp(key, "inject.rate") == 0) { p.inject_rate = atoi(val); return 0; }
    if (strcmp(key, "inject.channels") == 0) { p.inject_channels = atoi(val); return 0; }
    if (strcmp(key, "loopback") == 0) { p.loopback = atoi(val); return 0; }
    if (strcmp(key, "silence") == 0) return silence_add(val);
    return -1;
}

static void handle_line(int fd, char *line) {
    char *cmd = strtok(line, " ");
    if (!cmd) return;
    if (strcmp(cmd, "HELLO") == 0) {
        reply(fd, "OK simrelayd %s card=%d call=%d session=%d", VERSION, g_card, g_call, g_session);
    } else if (strcmp(cmd, "STATUS") == 0) {
        char names[400] = "";
        for (int i = 0; i < g_nm; i++) {
            strncat(names, g_m[i].name, sizeof(names) - strlen(names) - 2);
            strncat(names, ";", sizeof(names) - strlen(names) - 1);
        }
        reply(fd, "OK call=%d session=%d managed=%d silence=%d %s", g_call, g_session, g_nm, g_np, names);
    } else if (strcmp(cmd, "SET") == 0) {
        char *key = strtok(NULL, " ");
        char *val = strtok(NULL, "");          // rest of line: control names contain spaces
        if (!key || !val || set_key(key, val) < 0) reply(fd, "ERR bad SET");
        else reply(fd, "OK");
    } else if (strcmp(cmd, "CALL_START") == 0) {
        char *s = strtok(NULL, " ");
        int session = s ? atoi(s) : 1;
        if (session != 1 && session != 2) { reply(fd, "ERR session must be 1 or 2"); return; }
        int n = call_start(session);
        reply(fd, "OK applied=%d", n);
    } else if (strcmp(cmd, "IFSTATS") == 0) {
        long long rx = 0, tx = 0;
        if (ifstats(strtok(NULL, " "), &rx, &tx) < 0) reply(fd, "ERR no such interface");
        else reply(fd, "OK rx=%lld tx=%lld", rx, tx);
    } else if (strcmp(cmd, "NEIGH") == 0) {
        char list[400];
        int n = 0;
        if (neigh(strtok(NULL, " "), list, sizeof(list), &n) < 0) reply(fd, "ERR bad interface");
        else reply(fd, "OK %d %s", n, list);
    } else if (strcmp(cmd, "CALL_END") == 0) {
        restore_all("CALL_END");
        reply(fd, "OK");
    } else if (strcmp(cmd, "HAS") == 0) {               // does this mixer control exist? (auto profiles)
        char *name = strtok(NULL, "");
        struct snd_ctl_elem_info info;
        if (name && ctl_info(name, &info) == 0) reply(fd, "OK count=%u", info.count); else reply(fd, "ERR no such control");
    } else if (strcmp(cmd, "AUDIO?") == 0) {            // call test: did audio flow both ways
        char t[160];
        audio_totals(t, sizeof(t));
        reply(fd, "OK call=%d session=%d silence=%d %s", g_call, g_session, g_np, t);
    } else if (strcmp(cmd, "STALL") == 0) {             // STALL <ms>: debug, one inject-loop stall
        char *v = strtok(NULL, " ");
        audio_stall(v ? atoi(v) : 0);
        reply(fd, "OK");
    } else if (strcmp(cmd, "TUNE") == 0) {              // TUNE [prime keep period]: audio latency trade-offs
        char *a = strtok(NULL, " "), *b = strtok(NULL, " "), *c = strtok(NULL, " ");
        char t[80];
        if (a && b && c && audio_tune(atoi(a), atoi(b), atoi(c)) < 0) { reply(fd, "ERR bad values"); return; }
        audio_tune_get(t, sizeof(t));
        reply(fd, "OK %s", t);
    } else if (strcmp(cmd, "POWERKEY") == 0) {          // POWERKEY 1|0: box mode blocks the power button
        char *v = strtok(NULL, " ");
        reply(fd, "OK %d", box_power_block(v && atoi(v) == 1));
    } else if (strcmp(cmd, "SCREEN_OFF") == 0 || strcmp(cmd, "WAKE") == 0) {
        reply(fd, box_screen(strcmp(cmd, "WAKE") == 0) == 0 ? "OK" : "ERR spawn failed");
    } else if (strcmp(cmd, "ROLE") == 0) {
        char *role = strtok(NULL, " "), *pkg = strtok(NULL, " ");
        reply(fd, box_role(role, pkg) == 0 ? "OK" : "ERR bad role or package");
    } else if (strcmp(cmd, "NETBLOCK") == 0) {          // NETBLOCK <uid> <0|1>
        char *u = strtok(NULL, " "), *on = strtok(NULL, " ");
        reply(fd, u && on && box_netblock(atoi(u), atoi(on)) == 0 ? "OK" : "ERR bad uid");
    } else if (strcmp(cmd, "CHARGE?") == 0) {
        const char *node = box_charge_node();
        if (node) reply(fd, "OK %s", node); else reply(fd, "ERR unsupported");
    } else if (strcmp(cmd, "CHARGE") == 0) {
        char *s = strtok(NULL, " ");
        int rc = box_charge(s ? atoi(s) : -1);
        reply(fd, rc == 0 ? "OK" : rc == -2 ? "ERR unsupported" : "ERR failed");
    } else {
        reply(fd, "ERR unknown command");
    }
}

static int peer_allowed(int fd) {
    struct ucred cred;
    socklen_t len = sizeof(cred);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &len) < 0) return 0;
    int ok = cred.uid == 0 || cred.uid == g_allowed_uid;
    LOGI("client pid=%d uid=%d %s", cred.pid, cred.uid, ok ? "accepted" : "REJECTED");
    return ok;
}

static socklen_t abstract_addr(struct sockaddr_un *a) {
    memset(a, 0, sizeof(*a));
    a->sun_family = AF_UNIX;
    memcpy(a->sun_path + 1, SOCK_NAME, strlen(SOCK_NAME));     // sun_path[0] = 0: abstract namespace
    return (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(SOCK_NAME));
}

// Debug client: "CMD|SLEEP n|CMD..." over one connection (a call stays up between commands).
static int run_client(const char *script) {
    struct sockaddr_un a;
    socklen_t len = abstract_addr(&a);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0 || connect(fd, (struct sockaddr *)&a, len) < 0) { perror("connect @simrelayd"); return 1; }
    char copy[1024];
    strncpy(copy, script, sizeof(copy) - 1);
    copy[sizeof(copy) - 1] = 0;
    int rc = 0;
    for (char *save = NULL, *cmd = strtok_r(copy, "|", &save); cmd; cmd = strtok_r(NULL, "|", &save)) {
        if (strncmp(cmd, "SLEEP ", 6) == 0) { sleep((unsigned)atoi(cmd + 6)); continue; }
        dprintf(fd, "%s\n", cmd);
        char buf[512];
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        if (n <= 0) { rc = 1; break; }
        buf[n] = 0;
        printf("%s -> %s", cmd, buf);
        if (strncmp(buf, "OK", 2) != 0) rc = 1;
    }
    close(fd);
    return rc;
}

static void on_signal(int sig) { (void)sig; g_stop = 1; }

int main(int argc, char **argv) {
    int card_given = 0;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--client") == 0 && i + 1 < argc) return run_client(argv[i + 1]);
        if (strcmp(argv[i], "--uid") == 0 && i + 1 < argc) g_allowed_uid = (uid_t)atoi(argv[++i]);
        else if (strcmp(argv[i], "--card") == 0 && i + 1 < argc) { g_card = atoi(argv[++i]); card_given = 1; }
        else if (strcmp(argv[i], "--loopback") == 0) p.loopback = 1;
    }

    // The mixer: the given card, else the first one that opens. Without any, keep running (box mode, charge limit,
    // network block need the daemon too); call audio is simply unavailable.
    char path[64];
    for (int c = card_given ? g_card : 0; c < (card_given ? g_card + 1 : 8) && g_ctl < 0; c++) {
        snprintf(path, sizeof(path), "/dev/snd/controlC%d", c);
        g_ctl = open(path, O_RDWR | O_NONBLOCK | O_CLOEXEC);
        if (g_ctl >= 0) g_card = c;
    }
    if (g_ctl < 0) LOGE("no sound card mixer (%s): running without call audio", strerror(errno));
    else {
        int on = 1;
        if (ioctl(g_ctl, SNDRV_CTL_IOCTL_SUBSCRIBE_EVENTS, &on) < 0) LOGW("event subscribe failed: %s", strerror(errno));
    }

    struct sockaddr_un a;
    socklen_t alen = abstract_addr(&a);
    int ls = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (ls < 0 || bind(ls, (struct sockaddr *)&a, alen) < 0 || listen(ls, 2) < 0) {
        LOGE("socket @%s: %s", SOCK_NAME, strerror(errno));
        return 1;
    }

    struct sigaction sa = { .sa_handler = on_signal };
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);
    signal(SIGCHLD, SIG_IGN);  // box.c spawns input/cmd/am and never waits for them

    clear_device_mutes();      // a previous run may have died mid-call
    audio_init((int)g_allowed_uid);
    box_keys_open();
    box_charge_init();
    int uev = box_uevent_open();
    box_announce();            // the app reconnects at once (silence controls back within a second)
    box_log_capture();
    LOGI("simrelayd %s up: card %d, socket @%s, allowed uid %d", VERSION, g_card, SOCK_NAME, (int)g_allowed_uid);

    int client = -1;
    char buf[1024];
    size_t used = 0;
    while (!g_stop) {
        struct pollfd fds[4 + BOX_MAX_KEYDEVS] = {
            { .fd = ls, .events = POLLIN }, { .fd = g_ctl, .events = POLLIN }, { .fd = client, .events = POLLIN },
            { .fd = uev, .events = POLLIN },
        };
        int nk = box_key_count();
        for (int i = 0; i < nk; i++) fds[4 + i] = (struct pollfd){ .fd = box_key_fd(i), .events = POLLIN };
        // 10 min heartbeat: re-check the charge limit even if a phone sends no battery uevents.
        int pr = poll(fds, (nfds_t)(4 + nk), 600000);
        if (pr < 0) { if (errno == EINTR) continue; break; }
        if (pr == 0) { box_charge_check(); continue; }

        if (fds[1].revents & POLLIN) drain_ctl_events();
        if (fds[3].revents & POLLIN) box_uevent(uev);
        for (int i = 0; i < nk; i++) if (fds[4 + i].revents & POLLIN) box_key_event(fds[4 + i].fd);

        if (fds[0].revents & POLLIN) {
            int c = accept4(ls, NULL, NULL, SOCK_CLOEXEC);
            if (c >= 0 && !peer_allowed(c)) { close(c); c = -1; }
            if (c >= 0) {
                if (client >= 0) { reply(client, "ERR replaced by a new client"); close(client); }
                client = c;
                used = 0;
            }
        }

        if (client >= 0 && (fds[2].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t n = read(client, buf + used, sizeof(buf) - 1 - used);
            if (n <= 0) {
                LOGI("client gone");
                if (g_call) restore_all("client disconnected mid-call");
                close(client);
                client = -1;
                continue;
            }
            used += (size_t)n;
            buf[used] = 0;
            char *start = buf, *nl;
            while ((nl = strchr(start, '\n'))) {
                *nl = 0;
                if (nl > start && nl[-1] == '\r') nl[-1] = 0;
                handle_line(client, start);
                start = nl + 1;
            }
            used = strlen(start);
            memmove(buf, start, used + 1);
            if (used == sizeof(buf) - 1) used = 0;   // overlong line: drop it
        }
    }

    restore_all("exit");
    box_charge_shutdown();
    for (int i = 0; i < g_np; i++) {            // clean exit only: give the phone its sound back
        ctl_write(&g_perm[i], g_perm[i].orig, 1);
        LOGI("restored silence control '%s'", g_perm[i].name);
    }
    audio_shutdown();
    LOGI("simrelayd stopped");
    return 0;
}
