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
#include <sys/system_properties.h>
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
#define MAX_MANAGED 64           // a MediaTek profile holds ~20 links during a call; Qualcomm adds its own few
#define MAX_VALUES 4
#define OWN_WRITE_GRACE_NS 100000000LL   // write-only controls: ignore change events within 100 ms of our own write

typedef struct {
    char name[NAME_LEN];
    unsigned numid;
    unsigned count;
    long long want[MAX_VALUES];
    int nwant;
    struct snd_ctl_elem_value orig;   // the whole value before we touched it (every channel), restored as is
    int have_orig;
    int write_only;            // device mutes: no get handler, reads back -1, restore = unmute
    int enumerated;            // ENUM controls use value.enumerated.item, not value.integer.value
    struct timespec last_write;
    struct snd_ctl_elem_info info;   // type, count and range: every write is checked against it
    struct timespec reapply_window;  // re-apply budget: a control that won't keep our value is left alone
    int reapplies;
    struct timespec paused_until;
} managed_t;

static int g_card = 0;
static int g_ctl = -1;
static uid_t g_allowed_uid = (uid_t)-1;
static managed_t g_m[MAX_MANAGED];
static int g_nm = 0;
// Host silence: controls held for the daemon's whole lifetime (e.g. speaker/earpiece amplifier mutes),
// independent of calls. Fail-silent: if the daemon dies they stay muted until reboot.
static int g_exit_code = 0;
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
        .capture_pcm = 0, .inject_pcm = 0 /* every profile sets inject.pcm */, .inject_rate = 48000, .inject_channels = 2 };

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

// ------------------------------------------------------------------ hardware inventory
// -1: no mixer, -2: the file couldn't be written (nothing replaced).
// Every mixer control's description (ELEM_LIST + ELEM_INFO, enum item names): never a value read or write, so it can't
// disturb the hardware. What the phone's own sound card offers is what lets a family be built and proven from users'
// phones instead of donor phones (docs/10-proving-voice-paths.md). One line per control:
//   numid<TAB>name<TAB>type<TAB>count<TAB>min..max | enum item|item|...
#define INVENTORY_FILE "/data/adb/tosbisim/inventory.txt"
static int inventory(const char *path) {
    if (g_ctl < 0) return -1;
    struct snd_ctl_elem_list list;
    memset(&list, 0, sizeof(list));
    if (ioctl(g_ctl, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) return -1;
    unsigned total = list.count;
    struct snd_ctl_elem_id *ids = calloc(total ? total : 1, sizeof(*ids));
    if (!ids) return -1;
    list.space = total;
    list.pids = ids;
    if (ioctl(g_ctl, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) { free(ids); return -1; }
    char tmp[128];
    snprintf(tmp, sizeof(tmp), "%s.tmp", path);
    FILE *f = fopen(tmp, "we");
    if (!f) { free(ids); return -2; }
    static const char *types[] = { "none", "bool", "int", "enum", "bytes", "iec958", "int64" };
    int n = 0;
    for (unsigned i = 0; i < list.used; i++) {
        struct snd_ctl_elem_info in;
        memset(&in, 0, sizeof(in));
        in.id.numid = ids[i].numid;
        if (ioctl(g_ctl, SNDRV_CTL_IOCTL_ELEM_INFO, &in) < 0) continue;
        int t = (int)in.type;
        fprintf(f, "%u\t%s\t%s\t%u\t", in.id.numid, (const char *)in.id.name, t >= 0 && t <= 6 ? types[t] : "?", in.count);
        if (in.type == SNDRV_CTL_ELEM_TYPE_INTEGER) fprintf(f, "%ld..%ld", in.value.integer.min, in.value.integer.max);
        else if (in.type == SNDRV_CTL_ELEM_TYPE_INTEGER64) fprintf(f, "%lld..%lld", (long long)in.value.integer64.min, (long long)in.value.integer64.max);
        else if (in.type == SNDRV_CTL_ELEM_TYPE_ENUMERATED) {
            unsigned items = in.value.enumerated.items;
            for (unsigned k = 0; k < items && k < 64; k++) {
                struct snd_ctl_elem_info e = in;
                e.value.enumerated.item = k;
                if (ioctl(g_ctl, SNDRV_CTL_IOCTL_ELEM_INFO, &e) == 0) fprintf(f, "%s%s", k ? "|" : "", e.value.enumerated.name);
            }
            if (items > 64) fprintf(f, "|...(%u)", items);
        }
        fputc('\n', f);
        n++;
    }
    // A full disk must not rename a truncated list into place (audit, 20 Oct 2026)
    int ok = !ferror(f) && fflush(f) == 0;
    if (fclose(f) != 0) ok = 0;
    free(ids);
    if (!ok || rename(tmp, path) != 0) { LOGE("inventory: writing %s failed", tmp); unlink(tmp); return -2; }
    LOGI("inventory: %d controls -> %s", n, path);
    return n;
}

// ------------------------------------------------------------------ write safety
// A value is written only if the control says it can hold it. Production kernels don't range-check mixer writes and
// vendor put() handlers index tables with the raw value: a profile matched by family (or built automatically) naming a
// control that exists on another phone with another type or range could crash the kernel, on every boot (audit,
// 19 Oct). Only boolean, integer and enum controls are written; n <= count; integers in [min, max] (a driver that
// declares max <= min gives no range to check); enum items < items.
static int value_ok(const char *name, const struct snd_ctl_elem_info *in, const long long *v, int n) {
    if (n < 1 || (unsigned)n > in->count) { LOGE("'%s': %d value(s) for a %u-value control: not written", name, n, in->count); return 0; }
    for (int i = 0; i < n; i++) {
        int ok;
        switch (in->type) {
        case SNDRV_CTL_ELEM_TYPE_BOOLEAN: ok = v[i] == 0 || v[i] == 1; break;
        case SNDRV_CTL_ELEM_TYPE_INTEGER:
            ok = in->value.integer.max <= in->value.integer.min || (v[i] >= in->value.integer.min && v[i] <= in->value.integer.max);
            break;
        case SNDRV_CTL_ELEM_TYPE_ENUMERATED: ok = v[i] >= 0 && v[i] < (long long)in->value.enumerated.items; break;
        default: LOGE("'%s': control type %d is never written", name, (int)in->type); return 0;
        }
        if (!ok) { LOGE("'%s': value %lld is outside the control's range: not written", name, v[i]); return 0; }
    }
    return 1;
}

// Crash breadcrumb: the boot id goes to a file before a mixer write reaches the kernel and is removed CRUMB_LINGER_S
// after the last one (and on exit). Found at start with another boot's id, the phone went down while we were writing:
// unless the boot reason says it was an ordinary reboot or shutdown, this boot writes nothing (safe mode) and says so
// in STATUS, so a write that crashes a kernel can't become a reboot loop. The linger was 60 s and the crumb outlived
// clean exits: a normal reboot right after a call put the next boot in safe mode, without its silence controls
// (audit, 20 Oct 2026). A kernel that dies on a write dies within moments of it.
#define CRUMB "/data/adb/tosbisim/applying"
#define CRUMB_LINGER_S 5
#define SAFE_FILE "/data/adb/tosbisim/safe"     // the boot id safe mode belongs to: it lasts the whole boot
static char g_boot_id[48] = "";
static int g_safe = 0;
static struct timespec g_crumb_until;

// The last boot ended in an ordinary reboot/shutdown (not a panic, a watchdog, a hardware reset or anything unknown)?
// Android's canonical reason ("reboot,userrequested", "shutdown,battery", "kernel_panic", "watchdog,...") comes from
// bootstat (sys.boot.reason), the bootloader's own from ro.boot.bootreason. Conservative: any crash word, or no reason
// at all, counts as a crash.
static int boot_was_normal(char *why, size_t whylen) {
    char r[PROP_VALUE_MAX] = "";
    if (__system_property_get("sys.boot.reason", r) <= 0) __system_property_get("ro.boot.bootreason", r);
    snprintf(why, whylen, "%s", *r ? r : "unknown");
    if (!*r) return 0;
    char low[PROP_VALUE_MAX];
    size_t i = 0;
    for (; r[i] && i < sizeof(low) - 1; i++) low[i] = (char)(r[i] >= 'A' && r[i] <= 'Z' ? r[i] + 32 : r[i]);
    low[i] = 0;
    static const char *crash[] = { "panic", "watchdog", "wdog", "wdt", "crash", "oops", "kernel", "hard", "hw_reset",
                                   "abnormal", "fault", "bug", "sdi", "ramdump", "unknown", "warm", "reset" };
    for (size_t k = 0; k < sizeof(crash) / sizeof(crash[0]); k++) if (strstr(low, crash[k])) return 0;
    return strncmp(low, "reboot", 6) == 0 || strncmp(low, "shutdown", 8) == 0 || strncmp(low, "cold", 4) == 0 ||
           strstr(low, "userrequested") != NULL;
}

static void crumb_start(void) {
    int fd = open("/proc/sys/kernel/random/boot_id", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) { ssize_t n = read(fd, g_boot_id, sizeof(g_boot_id) - 1); close(fd); if (n > 0) g_boot_id[n] = 0; }
    char prev[48] = "";
    fd = open(CRUMB, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) { ssize_t n = read(fd, prev, sizeof(prev) - 1); close(fd); if (n > 0) prev[n] = 0; }
    if (*prev && strcmp(prev, g_boot_id) != 0) {
        char why[PROP_VALUE_MAX + 8];
        if (boot_was_normal(why, sizeof(why))) {
            LOGW("the last boot ended while audio controls were being written, but by an ordinary '%s': not safe mode", why);
        } else {
            g_safe = 1;
            LOGE("SAFE MODE: the last boot ended ('%s') while audio controls were being written; none are written this boot", why);
            fd = open(SAFE_FILE, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
            if (fd >= 0) { (void)!write(fd, g_boot_id, strlen(g_boot_id)); close(fd); }
        }
    }
    unlink(CRUMB);
    char safe[48] = "";                          // a restart within the same boot stays safe
    fd = open(SAFE_FILE, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) { ssize_t n = read(fd, safe, sizeof(safe) - 1); close(fd); if (n > 0) safe[n] = 0; }
    if (*safe && strcmp(safe, g_boot_id) == 0) { if (!g_safe) LOGE("SAFE MODE (this boot): no mixer writes"); g_safe = 1; }
    else if (*safe) unlink(SAFE_FILE);
}

static void crumb_arm(void) {
    if (!*g_boot_id) return;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (g_crumb_until.tv_sec == 0) {
        int fd = open(CRUMB, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (fd >= 0) { (void)!write(fd, g_boot_id, strlen(g_boot_id)); fsync(fd); close(fd); }
    }
    g_crumb_until.tv_sec = now.tv_sec + CRUMB_LINGER_S;
    g_crumb_until.tv_nsec = now.tv_nsec;
}

static void crumb_clear(void) { if (g_crumb_until.tv_sec) unlink(CRUMB); g_crumb_until.tv_sec = 0; }

// ms until the breadcrumb is due to go (for poll), -1 if none
static int crumb_tick(void) {
    if (g_crumb_until.tv_sec == 0) return -1;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    long long ms = (g_crumb_until.tv_sec - now.tv_sec) * 1000LL + (g_crumb_until.tv_nsec - now.tv_nsec) / 1000000;
    if (ms <= 0) { crumb_clear(); return -1; }
    return (int)ms;
}

static int ctl_write_value(managed_t *m, struct snd_ctl_elem_value *v) {
    if (g_safe) return -1;
    v->id.numid = m->numid;
    clock_gettime(CLOCK_MONOTONIC, &m->last_write);
    if (g_ctl < 0) { LOGE("write '%s': no mixer", m->name); return -1; }
    crumb_arm();                                 // only a write that really goes to the kernel
    if (ioctl(g_ctl, SNDRV_CTL_IOCTL_ELEM_WRITE, v) < 0) {
        LOGE("write '%s' failed: %s", m->name, strerror(errno));
        return -1;
    }
    return 0;
}

// Set the first n channels and leave the others as they are (read-modify-write): "X=1" on a 2-channel switch must not
// zero channel 2. Write-only controls (no get handler) are written from zero.
static int ctl_write(managed_t *m, const long long *vals, int n) {
    if (m->info.count && !value_ok(m->name, &m->info, vals, n)) return -1;
    struct snd_ctl_elem_value v;
    memset(&v, 0, sizeof(v));
    v.id.numid = m->numid;
    if (!m->write_only && g_ctl >= 0 && ioctl(g_ctl, SNDRV_CTL_IOCTL_ELEM_READ, &v) < 0) memset(&v.value, 0, sizeof(v.value));
    for (int i = 0; i < n && i < MAX_VALUES; i++) {
        if (m->enumerated) v.value.enumerated.item[i] = (unsigned)vals[i];
        else v.value.integer.value[i] = vals[i];
    }
    return ctl_write_value(m, &v);
}

static void ctl_restore(managed_t *m) {
    if (m->have_orig) ctl_write_value(m, &m->orig);
}

// Write a control by name without managing it (start-up / exit safety).
static void ctl_set_by_name(const char *name, const long long *vals, int n) {
    struct snd_ctl_elem_info info;
    if (!*name || ctl_info(name, &info) < 0) return;
    managed_t tmp = { .numid = info.id.numid, .info = info, .enumerated = info.type == SNDRV_CTL_ELEM_TYPE_ENUMERATED };
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
    if (!value_ok(name, &info, want, n)) return NULL;
    // One owner per control: held by silence and by a call with other values, they'd overwrite each other for ever
    managed_t *other = list == g_m ? g_perm : g_m;
    int nother = list == g_m ? g_np : g_nm;
    for (int i = 0; i < nother; i++)
        if (other[i].numid == info.id.numid && memcmp(other[i].want, want, sizeof(long long) * n) != 0) {
            LOGE("'%s' is already held at another value: not managed twice", name);
            return NULL;
        }
    managed_t *m = &list[(*count)++];
    memset(m, 0, sizeof(*m));
    strncpy(m->name, name, NAME_LEN - 1);
    m->numid = info.id.numid;
    m->count = info.count;
    m->write_only = write_only;
    m->enumerated = info.type == SNDRV_CTL_ELEM_TYPE_ENUMERATED;
    m->nwant = n;
    m->info = info;
    memcpy(m->want, want, sizeof(long long) * n);
    if (!write_only) {
        m->orig.id.numid = m->numid;
        m->have_orig = g_ctl >= 0 && ioctl(g_ctl, SNDRV_CTL_IOCTL_ELEM_READ, &m->orig) == 0;
    }
    ctl_write(m, want, n);
    LOGI("managed '%s' numid=%u -> %lld (orig %lld)", name, m->numid, want[0],
         !m->have_orig ? -1LL : m->enumerated ? (long long)m->orig.value.enumerated.item[0] : m->orig.value.integer.value[0]);
    return m;
}

static managed_t *manage(const char *name, const long long *want, int n, int write_only) {
    return manage_in(g_m, &g_nm, name, want, n, write_only);
}

// "SET call <control name>=<v1>[,<v2>...]": set during a call, restored after. How a non-Qualcomm platform (MediaTek,
// Exynos) routes call audio is then data in its profile, not code.
#define MAX_CALL_CONTROLS MAX_MANAGED
static struct { char name[NAME_LEN]; long long v[MAX_VALUES]; int n; } g_cc[MAX_CALL_CONTROLS];
static int g_ncc = 0;
static int call_control_add(const char *spec) {
    const char *eq = strrchr(spec, '=');
    if (!eq || eq == spec) return -1;
    if (g_ncc >= MAX_CALL_CONTROLS) { LOGE("too many call controls: '%s' dropped", spec); return -1; }
    size_t len = (size_t)(eq - spec);
    if (len >= NAME_LEN) return -1;
    memcpy(g_cc[g_ncc].name, spec, len);
    g_cc[g_ncc].name[len] = 0;
    int n = 0;
    for (const char *q = eq + 1; *q; ) {
        char *end;
        if (n == MAX_VALUES) { LOGE("call control '%s': more than %d values", spec, MAX_VALUES); return -1; }
        g_cc[g_ncc].v[n++] = strtoll(q, &end, 0);
        if (end == q || (*end && *end != ',')) { LOGE("call control '%s': not a number", spec); return -1; }   // "On" isn't 0
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
    char *end;
    long long v = strtoll(eq + 1, &end, 0);
    if (end == eq + 1 || *end) { LOGE("silence '%s': not a number", spec); return -1; }
    for (int i = 0; i < g_np; i++)
        if (strcmp(g_perm[i].name, name) == 0) { if (ctl_write(&g_perm[i], &v, 1) == 0) g_perm[i].want[0] = v; return 0; }
    return manage_in(g_perm, &g_np, name, &v, 1, 0) ? 0 : -1;
}

// A call's originals on disk: they lived only in memory, so a daemon killed mid-call (crash, SIGKILL, OOM) left the
// profile's call controls in call state until reboot (audit, 20 Oct 2026). Written when a call applies them, removed
// when they are restored; found at start (same boot only), they are put back. One control per line,
// "<name>\t<v1>,<v2>...", after a "boot <id>" line; a line without its newline (cut short) is ignored.
#define CALL_ORIG "/data/adb/tosbisim/call-originals"

static void call_orig_save(void) {
    FILE *f = fopen(CALL_ORIG ".tmp", "we");
    if (!f) { LOGW("save " CALL_ORIG ": %s", strerror(errno)); return; }
    fprintf(f, "boot %.*s\n", (int)strcspn(g_boot_id, "\n"), g_boot_id);
    int n = 0;
    for (int i = 0; i < g_nm; i++) {
        managed_t *m = &g_m[i];
        if (m->write_only || !m->have_orig) continue;
        unsigned c = m->count < MAX_VALUES ? m->count : MAX_VALUES;
        fprintf(f, "%s\t", m->name);
        for (unsigned k = 0; k < c; k++)
            fprintf(f, "%s%lld", k ? "," : "", m->enumerated ? (long long)m->orig.value.enumerated.item[k] : m->orig.value.integer.value[k]);
        fputc('\n', f);
        n++;
    }
    int ok = !ferror(f) && fflush(f) == 0 && fsync(fileno(f)) == 0;
    if (fclose(f) != 0) ok = 0;
    if (!ok || n == 0 || rename(CALL_ORIG ".tmp", CALL_ORIG) != 0) {
        if (!ok) LOGW("save " CALL_ORIG " failed");
        unlink(CALL_ORIG ".tmp");
    }
}

// Start-up (not in safe mode): a previous run died mid-call. Each value goes through ctl_write's value_ok check.
static void call_orig_restore(void) {
    FILE *f = fopen(CALL_ORIG, "re");
    if (!f) return;
    char line[256], boot[64];
    snprintf(boot, sizeof(boot), "boot %.*s\n", (int)strcspn(g_boot_id, "\n"), g_boot_id);
    if (!fgets(line, sizeof(line), f) || strcmp(line, boot) != 0) {
        LOGI("call originals from another boot: dropped");    // a reboot has put every control back already
    } else {
        // Every line first, then restored last to first (the order restore_all uses)
        char names[MAX_MANAGED][NAME_LEN];
        long long vals[MAX_MANAGED][MAX_VALUES];
        int nv[MAX_MANAGED], n = 0;
        while (n < MAX_MANAGED && fgets(line, sizeof(line), f)) {
            size_t len = strlen(line);
            if (len == 0 || line[len - 1] != '\n') break;            // cut short: the file ends here
            line[len - 1] = 0;
            char *tab = strrchr(line, '\t');
            if (!tab || tab == line || (size_t)(tab - line) >= NAME_LEN) continue;
            *tab = 0;
            int k = 0, bad = 0;
            for (char *q = tab + 1; *q && k < MAX_VALUES; ) {
                char *end;
                vals[n][k++] = strtoll(q, &end, 10);
                if (end == q || (*end && *end != ',')) { bad = 1; break; }
                if (!*end) break;
                q = end + 1;
            }
            if (bad || k == 0) continue;
            snprintf(names[n], NAME_LEN, "%s", line);
            nv[n++] = k;
        }
        LOGW("a previous run died mid-call: restoring %d call control(s)", n);
        for (int i = n - 1; i >= 0; i--) ctl_set_by_name(names[i], vals[i], nv[i]);
    }
    fclose(f);
    unlink(CALL_ORIG);
}

static void restore_all(const char *why) {
    audio_call_end();
    // Last to first: a control managed twice (a call control naming a route control too) ends at its first original
    for (int i = g_nm - 1; i >= 0; i--) {
        managed_t *m = &g_m[i];
        if (m->write_only) {
            long long off[3] = { 0, p.vsid, p.ramp };
            ctl_write(m, off, 3);
        } else {
            ctl_restore(m);
        }
        LOGI("restored '%s' (%s)", m->name, why);
    }
    g_nm = 0;
    g_call = 0;
    g_session = 0;
    clear_device_mutes();
    if (g_ctl >= 0 && !g_safe) unlink(CALL_ORIG);   // restored (in safe mode nothing was, nor could be)
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
    call_orig_save();                            // after the audio starts: the file's fsync must not delay it
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
        if (m->numid != numid) continue;
        if (m->write_only) {
            // No read-back: our own write's event can't be told apart, so only events after a grace period count.
            if (ns_since(&m->last_write) < OWN_WRITE_GRACE_NS) continue;
        } else {
            // Read back and compare, at any time: a HAL write right after ours (call start) must not slip through.
            long long cur[MAX_VALUES] = { 0 };
            if (ctl_read_t(numid, cur, m->count, m->enumerated) == 0 && memcmp(cur, m->want, sizeof(long long) * m->nwant) == 0) continue;
        }
        // Budget: 5 re-applies a second. A driver that normalises our value, or another writer that insists, would
        // otherwise keep the daemon re-writing for ever and starve everything else (audit, 19 Oct).
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        if (now.tv_sec < m->paused_until.tv_sec) continue;
        if (now.tv_sec != m->reapply_window.tv_sec) { m->reapply_window = now; m->reapplies = 0; }
        if (++m->reapplies > 5) {
            m->paused_until.tv_sec = now.tv_sec + 10;
            LOGE("'%s' won't keep our value: left alone for 10 s", m->name);
            continue;
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
    // At most 64 events per turn: the main loop (socket, keys, escape gesture) gets its turn between bursts
    for (int k = 0; k < 64 && g_ctl >= 0 && read(g_ctl, &ev, sizeof(ev)) == (ssize_t)sizeof(ev); k++) {
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
    char buf[2048];
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
        int n = snprintf(out + used, outlen - used, "%s%s,%s", *count ? ";" : "", hw, ip);
        if (n < 0 || (size_t)n >= outlen - used) { out[used] = 0; break; }    // full: whole entries only
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
        // Whole names only, until the buffer is full (strncat's size arithmetic overflowed it once full; audit, 20 Oct 2026)
        char names[1800] = "";
        size_t used = 0;
        for (int i = 0; i < g_nm; i++) {
            int w = snprintf(names + used, sizeof(names) - used, "%s;", g_m[i].name);
            if (w < 0 || (size_t)w >= sizeof(names) - used) { names[used] = 0; break; }
            used += (size_t)w;
        }
        reply(fd, "OK call=%d session=%d managed=%d silence=%d safe=%d %s", g_call, g_session, g_nm, g_np, g_safe, names);
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
        char list[1400];                                // ~45 devices (a hotspot allows 10-32)
        int n = 0;
        if (neigh(strtok(NULL, " "), list, sizeof(list), &n) < 0) reply(fd, "ERR bad interface");
        else reply(fd, "OK %d %s", n, list);
    } else if (strcmp(cmd, "CALL_END") == 0) {
        restore_all("CALL_END");
        reply(fd, "OK");
    } else if (strcmp(cmd, "INVENTORY") == 0) {         // read-only: every control's description, to a root-only file
        // Not during a call: hundreds of ioctls on the mixer the call's routing and re-applies are using
        if (g_call) { reply(fd, "ERR in call"); return; }
        int n = inventory(INVENTORY_FILE);
        if (n == -1) reply(fd, "ERR no mixer");
        else if (n < 0) reply(fd, "ERR write failed");
        else reply(fd, "OK %d controls in " INVENTORY_FILE, n);
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
        int n = box_power_block(v && atoi(v) == 1);
        if (n < 0) reply(fd, "ERR no escape gesture on this phone"); else reply(fd, "OK %d", n);
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

// The peer's uid if it may talk to us (root or the host app), else -1.
static int peer_uid(int fd) {
    struct ucred cred;
    socklen_t len = sizeof(cred);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &len) < 0) return -1;
    int ok = cred.uid == 0 || cred.uid == g_allowed_uid;
    LOGI("client pid=%d uid=%d %s", cred.pid, cred.uid, ok ? "accepted" : "REJECTED");
    return ok ? (int)cred.uid : -1;
}

// Two connections: the host app's (the owner: a new one replaces a stale one, and losing it mid-call restores the
// mixer) and one for root tools (simrelayd --client, adb). A tool's query never displaces the app nor, by closing,
// ends a call's routing: it did both on 20 Oct 2026 (an AUDIO? during a call restored every control).
typedef struct { int fd; int owner; size_t used; char buf[1024]; } conn_t;

static void conn_close(conn_t *c) { if (c->fd >= 0) close(c->fd); c->fd = -1; c->used = 0; }

// Read what's there and run its lines; 0 when the peer has gone. Never blocks: a client whose readiness was stale
// (POLLHUP, a replaced slot) must not stall the main loop (audit, 20 Oct 2026).
static int conn_read(conn_t *c) {
    ssize_t n = recv(c->fd, c->buf + c->used, sizeof(c->buf) - 1 - c->used, MSG_DONTWAIT);
    if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) return 1;   // nothing yet
    if (n <= 0) return 0;
    c->used += (size_t)n;
    c->buf[c->used] = 0;
    char *start = c->buf, *nl;
    while ((nl = strchr(start, '\n'))) {
        *nl = 0;
        if (nl > start && nl[-1] == '\r') nl[-1] = 0;
        handle_line(c->fd, start);
        start = nl + 1;
    }
    c->used = strlen(start);
    memmove(c->buf, start, c->used + 1);
    if (c->used == sizeof(c->buf) - 1) c->used = 0;   // overlong line: drop it
    return 1;
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

// SIGTERM/SIGINT: stop and give the phone its sound back (module disabled or removed). SIGUSR1: restart for an update
// (Updater.kt): the silence controls and the charge cut stay held, the new daemon takes them over at once.
// These signals are blocked except inside the main loop's ppoll: one arriving between the g_stop check and the wait
// would otherwise sleep through to the 10 min timeout (audit, 20 Oct 2026). SIGCHLD only wakes the loop (the
// network-block queue starts its next script). Threads inherit the mask, so these all reach the main thread there.
// An update restart exits EXIT_UPDATE: service.sh then leaves the charge cut held for the new daemon.
#define EXIT_UPDATE 10
static volatile sig_atomic_t g_keep_silence = 0;
static void on_signal(int sig) { if (sig == SIGUSR1) g_keep_silence = 1; g_stop = 1; }
static void on_child(int sig) { (void)sig; }

// --uid: digits only. atoi("") is 0, which made root the owner when service.sh found no app uid (audit, 20 Oct 2026).
static int parse_uid(const char *s, uid_t *out) {
    if (!s || !*s || strlen(s) > 9) return -1;
    for (const char *c = s; *c; c++) if (*c < '0' || *c > '9') return -1;
    *out = (uid_t)strtoul(s, NULL, 10);
    return 0;
}

int main(int argc, char **argv) {
    int card_given = 0;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--client") == 0 && i + 1 < argc) return run_client(argv[i + 1]);
        if (strcmp(argv[i], "--uid") == 0 && i + 1 < argc) {
            if (parse_uid(argv[++i], &g_allowed_uid) < 0) { LOGE("--uid '%s' is not a uid: root only", argv[i]); g_allowed_uid = (uid_t)-1; }
        }
        else if (strcmp(argv[i], "--card") == 0 && i + 1 < argc) { g_card = atoi(argv[++i]); card_given = 1; }
        else if (strcmp(argv[i], "--loopback") == 0) p.loopback = 1;
        else if (strcmp(argv[i], "--safe") == 0) { g_safe = 1; LOGE("SAFE MODE: started after repeated failures; no mixer writes"); }
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

    sigset_t block, waitmask;
    sigemptyset(&block);
    sigaddset(&block, SIGTERM); sigaddset(&block, SIGINT); sigaddset(&block, SIGUSR1); sigaddset(&block, SIGCHLD);
    sigprocmask(SIG_BLOCK, &block, &waitmask);
    sigdelset(&waitmask, SIGTERM); sigdelset(&waitmask, SIGINT); sigdelset(&waitmask, SIGUSR1); sigdelset(&waitmask, SIGCHLD);
    struct sigaction sa = { .sa_handler = on_signal };
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGUSR1, &sa, NULL);
    // SIGCHLD gets a handler, never SIG_IGN: the loop reaps fire-and-forget children (the network-block script, am
    // broadcasts) and ignoring SIGCHLD made waitpid lose exit codes. Children start with an empty mask (box.c spawn).
    struct sigaction sc = { .sa_handler = on_child };
    sigaction(SIGCHLD, &sc, NULL);
    signal(SIGPIPE, SIG_IGN);

    box_charge_init();         // first: a previous run may have died while cutting the charge (the battery would drain)
    crumb_start();             // before any mixer write: did the last boot end while we were writing?
    clear_device_mutes();      // a previous run may have died mid-call
    if (!g_safe) call_orig_restore();   // ... and left its call controls in call state
    audio_init((int)g_allowed_uid);
    box_keys_open();
    int uev = box_uevent_open();
    box_announce();            // the app reconnects at once (silence controls back within a second)
    box_log_capture();
    LOGI("simrelayd %s up: card %d, socket @%s, allowed uid %d", VERSION, g_card, SOCK_NAME, (int)g_allowed_uid);

    static conn_t conns[2] = { { .fd = -1, .owner = 1 }, { .fd = -1, .owner = 0 } };   // the app's, a root tool's
    // 10 min heartbeat: re-check the charge limit even if a phone sends no battery uevents. Kept on its own clock: the
    // crumb's shorter timeouts used to stand in for it, and a crumb kept alive by a call skipped it (audit, 20 Oct 2026).
    struct timespec now, heartbeat;
    clock_gettime(CLOCK_MONOTONIC, &heartbeat);
    heartbeat.tv_sec += 600;
    while (!g_stop) {
        struct pollfd fds[5 + BOX_MAX_KEYDEVS] = {
            { .fd = ls, .events = POLLIN }, { .fd = g_ctl, .events = POLLIN }, { .fd = conns[0].fd, .events = POLLIN },
            { .fd = uev, .events = POLLIN }, { .fd = conns[1].fd, .events = POLLIN },
        };
        int nk = box_key_count();
        for (int i = 0; i < nk; i++) fds[5 + i] = (struct pollfd){ .fd = box_key_fd(i), .events = POLLIN };
        box_reap();
        box_log_watch();
        box_power_check();
        clock_gettime(CLOCK_MONOTONIC, &now);
        if (now.tv_sec >= heartbeat.tv_sec) { box_charge_check(); heartbeat.tv_sec = now.tv_sec + 600; }
        long long wait_ms = (heartbeat.tv_sec - now.tv_sec) * 1000LL;
        int crumb = crumb_tick();
        if (crumb >= 0 && crumb + 50 < wait_ms) wait_ms = crumb + 50;
        struct timespec to = { .tv_sec = (time_t)(wait_ms / 1000), .tv_nsec = (long)(wait_ms % 1000) * 1000000L };
        int pr = ppoll(fds, (nfds_t)(5 + nk), &to, &waitmask);
        if (pr < 0) { if (errno == EINTR) continue; LOGE("poll: %s", strerror(errno)); break; }
        if (pr == 0) continue;

        // A device that went away polls ready for ever (an audio DSP restart re-registers the sound card): the mixer's
        // ids are stale too, so start over (the module restarts us, the app re-sends its profile); a vanished key
        // device is dropped. Otherwise the loop would spin at 100 % CPU (audit, 19 Oct).
        if (fds[1].revents & (POLLERR | POLLHUP | POLLNVAL)) {
            LOGE("sound card gone: restarting");
            close(g_ctl); g_ctl = -1;        // its ids may name other controls now: nothing is written on the way out
            g_exit_code = 3;
            break;
        }
        for (int i = 0; i < nk; i++) if (fds[5 + i].revents & (POLLERR | POLLHUP | POLLNVAL)) box_key_drop(i);

        if (fds[1].revents & POLLIN) drain_ctl_events();
        if (fds[3].revents & POLLIN) box_uevent(uev);
        for (int i = 0; i < nk; i++) if (fds[5 + i].revents & POLLIN) box_key_event(fds[5 + i].fd);

        if (fds[0].revents & POLLIN) {
            int c = accept4(ls, NULL, NULL, SOCK_CLOEXEC);
            int uid = c >= 0 ? peer_uid(c) : -1;
            if (c >= 0 && uid < 0) { close(c); c = -1; }
            if (c >= 0) {
                // Without --uid (development) root is the owner, as before
                int owner = g_allowed_uid == (uid_t)-1 || (uid_t)uid == g_allowed_uid;
                conn_t *slot = owner ? &conns[0] : &conns[1];
                if (slot->fd >= 0) { reply(slot->fd, "ERR replaced by a new client"); conn_close(slot); }
                slot->fd = c;
            }
        }

        for (int k = 0; k < 2; k++) {
            conn_t *c = &conns[k];
            struct pollfd *pf = &fds[k == 0 ? 2 : 4];
            // A slot replaced above holds a new fd: the old one's readiness says nothing about it (fd numbers are reused)
            if (c->fd < 0 || pf->fd != c->fd || !(pf->revents & (POLLIN | POLLHUP | POLLERR))) continue;
            if (conn_read(c)) continue;
            LOGI("%s gone", c->owner ? "client" : "tool client");
            if (c->owner && g_call) restore_all("client disconnected mid-call");
            conn_close(c);
        }
    }

    restore_all("exit");
    if (g_keep_silence) {
        LOGI("restart for an update: silence controls (%d) and charge limit left held", g_np);
    } else {
        box_charge_shutdown();
        for (int i = 0; i < g_np; i++) {        // clean exit only: give the phone its sound back
            ctl_restore(&g_perm[i]);
            LOGI("restored silence control '%s'", g_perm[i].name);
        }
    }
    audio_shutdown();
    crumb_clear();             // every write is done: a reboot from here on is no crash of ours
    if (g_keep_silence && g_exit_code == 0) g_exit_code = EXIT_UPDATE;
    LOGI("simrelayd stopped");
    return g_exit_code;
}
