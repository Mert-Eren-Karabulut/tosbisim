// simrelayd box mode helpers. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
//
// Root-only pieces of "box mode" (the host as a silent, screen-off box). Everything phone-specific is
// probed, not hard-coded:
//   SCREEN_OFF / WAKE      KEYCODE_SLEEP / KEYCODE_WAKEUP through /system/bin/input (the app may not hold
//                          DEVICE_POWER)
//   ROLE <role> <package>  cmd role add-role-holder (the app may not hold MANAGE_ROLE_HOLDERS)
//   CHARGE <percent>       charge cap through the first kernel node this phone has (see CAP_NODES)
//   escape gesture         Volume Up x3 then Volume Down x3 within 5 s, read from /dev/input without grabbing
//                          (volume keys keep working; works with the screen off) -> wake + tell the app

#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/netlink.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#include "box.h"

#define TAG "simrelayd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

extern char **environ;

#define ESCAPE_WINDOW_NS 5000000000LL
#define ESCAPE_ACTION "dev.simrelay.host.BOX_ESCAPE"
#define ESCAPE_RECEIVER "dev.simrelay.host/.DaemonEventReceiver"

// Charge cap nodes, most specific first. Each takes a percentage.
static const char *CAP_NODES[] = {
    "/sys/class/power_supply/battery/batt_full_capacity",              // Samsung (One UI "Protect battery")
    "/sys/class/power_supply/battery/charge_control_end_threshold",    // Linux power_supply ABI
    "/sys/class/power_supply/battery/charge_stop_level",               // some Xiaomi/Google kernels
};

static int g_keys[BOX_MAX_KEYDEVS];
static int g_power[BOX_MAX_KEYDEVS], g_grabbed[BOX_MAX_KEYDEVS], g_volup[BOX_MAX_KEYDEVS], g_voldown[BOX_MAX_KEYDEVS];
static struct timespec g_grab_until;            // dead-man: the block lapses unless the app confirms it again
static int g_nkeys = 0;
static int g_seq[6];          // last six volume presses: +1 up, -1 down
static long long g_seq_t[6];

static long long now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long)t.tv_sec * 1000000000LL + t.tv_nsec;
}

// Fire-and-forget: the main loop reaps it (box_reap).
static int spawn(char *const argv[]) {
    pid_t pid;
    int rc = posix_spawn(&pid, argv[0], NULL, NULL, argv, environ);
    if (rc != 0) { LOGW("spawn %s: %s", argv[0], strerror(rc)); return -1; }
    return 0;
}

int box_screen(int on) {
    char *argv[] = { "/system/bin/input", "keyevent", on ? "KEYCODE_WAKEUP" : "KEYCODE_SLEEP", NULL };
    return spawn(argv);
}

static int valid_pkg(const char *s) {
    if (!s || !*s || strlen(s) > 100) return 0;
    for (const char *c = s; *c; c++)
        if (!((*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z') || (*c >= '0' && *c <= '9') || *c == '.' || *c == '_'))
            return 0;
    return 1;
}

int box_role(const char *role, const char *pkg) {
    // Only the roles the host needs (box mode's dialer, the SMS role it guards); the package name is checked so
    // nothing reaches cmd unescaped.
    if (!role || (strcmp(role, "android.app.role.DIALER") != 0 && strcmp(role, "android.app.role.SMS") != 0) ||
        !valid_pkg(pkg)) return -1;
    char *argv[] = { "/system/bin/cmd", "role", "add-role-holder", (char *)role, (char *)pkg, "0", NULL };
    LOGI("role %s -> %s", role, pkg);
    return spawn(argv);
}

// ------------------------------------------------------------------ charge limit
//
// A cap node (Samsung batt_full_capacity, ...) only stops charging at N %: a battery already above N stays there,
// held full on charger power (seen on r8q 16 Oct: cap 80, "Not charging", 100 %). So above the limit we also cut the
// charger input (Samsung batt_slate_mode / Qualcomm input_suspend): the phone runs on its battery down to the limit,
// then the cap takes over. Without a cap node the cut alone holds the limit, charging back from limit-5.
// Driven by power_supply uevents (no polling); the limit is persisted so it is back at boot before the app is.
// Fail-safe: the cut is released at start, at exit, below MIN_ON_BATTERY and whenever no limit is set.

// Each cut node with the value that stops charging (the other value lets it charge again). charging_enabled is the
// most common node in the firmware corpus (422 phones, Qualcomm smb and MediaTek chargers; 19 Oct): it holds the
// battery on charger power instead of running from it, which also holds the limit.
static const struct { const char *path; int stop; } CUT_NODES[] = {
    { "/sys/class/power_supply/battery/batt_slate_mode", 1 },           // Samsung: 1 = run from the battery
    { "/sys/class/power_supply/battery/input_suspend", 1 },             // Qualcomm/MediaTek: 1 = charger input suspended
    { "/sys/class/power_supply/battery/charging_enabled", 0 },          // 0 = don't charge
};
#define BATT_CAPACITY "/sys/class/power_supply/battery/capacity"
#define LIMIT_DIR "/data/adb/tosbisim"
#define LIMIT_FILE LIMIT_DIR "/charge_limit"
#define MIN_ON_BATTERY 15

static int g_limit = 0;                // 0 or 100 = no limit
static int g_cutting = 0;

// access(W_OK) always passes for root: a read-only sysfs node would be picked, hide a working one, and report charge
// control that isn't there (audit, 19 Oct). The node's own write bit says what the kernel allows.
static int node_writable(const char *path) {
    struct stat st;
    return stat(path, &st) == 0 && (st.st_mode & S_IWUSR);
}
static const char *first_writable(const char *const *nodes, size_t n) {
    for (size_t i = 0; i < n; i++) if (node_writable(nodes[i])) return nodes[i];
    return NULL;
}
static const char *cap_node(void) { return first_writable(CAP_NODES, sizeof(CAP_NODES) / sizeof(CAP_NODES[0])); }
/** The first writable cut node (index into CUT_NODES), or -1. */
static int cut_index(void) {
    for (size_t i = 0; i < sizeof(CUT_NODES) / sizeof(CUT_NODES[0]); i++) if (node_writable(CUT_NODES[i].path)) return (int)i;
    return -1;
}
static const char *cut_node(void) { int i = cut_index(); return i < 0 ? NULL : CUT_NODES[i].path; }

static int read_int(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    char b[16] = { 0 };
    ssize_t n = read(fd, b, sizeof(b) - 1);
    close(fd);
    return n > 0 ? atoi(b) : -1;
}

static int write_int(const char *path, int v) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    char b[16];
    int n = snprintf(b, sizeof(b), "%d", v);
    int ok = write(fd, b, (size_t)n) == n;
    close(fd);
    return ok ? 0 : -1;
}

static void set_cut(int on) {
    int i = cut_index();
    if (i < 0) return;
    const char *node = CUT_NODES[i].path;
    if (write_int(node, on ? CUT_NODES[i].stop : !CUT_NODES[i].stop) < 0) return;
    if (g_cutting != on) LOGI("charge limit: %s (%s)", on ? "running on battery down to the limit" : "charger power back", node);
    g_cutting = on;
}

/** "cap+cut", "cap", "cut" or NULL: what this phone offers for a charge limit. */
const char *box_charge_node(void) {
    const char *cap = cap_node(), *cut = cut_node();
    return cap && cut ? "cap+cut" : cap ? "cap" : cut ? "cut" : NULL;
}

void box_charge_check(void) {
    if (!cut_node()) return;
    int level = read_int(BATT_CAPACITY);
    if (level < 0) return;
    if (g_limit <= 0 || g_limit >= 100 || level <= MIN_ON_BATTERY) { if (g_cutting) set_cut(0); return; }
    int with_cap = cap_node() != NULL;
    if (!g_cutting && (with_cap ? level > g_limit : level >= g_limit)) set_cut(1);
    else if (g_cutting && level <= (with_cap ? g_limit : g_limit - 5)) set_cut(0);
}

int box_charge(int percent) {
    if (percent < 50 || percent > 100) return -1;
    if (!box_charge_node()) return -2;
    g_limit = percent;
    const char *cap = cap_node();
    if (cap && write_int(cap, percent) < 0) LOGW("charge cap write failed: %s", strerror(errno));
    mkdir(LIMIT_DIR, 0700);
    FILE *f = fopen(LIMIT_FILE, "we");
    if (f) { fprintf(f, "%d\n", percent); fclose(f); } else LOGW("save %s: %s", LIMIT_FILE, strerror(errno));
    LOGI("charge limit %d%% (%s)", percent, box_charge_node());
    box_charge_check();
    return 0;
}

void box_charge_init(void) {
    if (cut_node()) set_cut(0);                       // a previous run may have died while cutting
    int saved = read_int(LIMIT_FILE);
    if (saved >= 50 && saved <= 100) box_charge(saved);
}

void box_charge_shutdown(void) { if (cut_node()) set_cut(0); }

int box_uevent_open(void) {
    int fd = socket(AF_NETLINK, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, NETLINK_KOBJECT_UEVENT);
    if (fd < 0) { LOGW("uevent socket: %s", strerror(errno)); return -1; }
    struct sockaddr_nl a = { .nl_family = AF_NETLINK, .nl_groups = 1 };
    if (bind(fd, (struct sockaddr *)&a, sizeof(a)) < 0) { LOGW("uevent bind: %s", strerror(errno)); close(fd); return -1; }
    return fd;
}

void box_uevent(int fd) {
    char buf[4096];
    ssize_t n;
    int power = 0;
    while ((n = recv(fd, buf, sizeof(buf) - 1, 0)) > 0) {
        buf[n] = 0;
        for (char *p = buf; p < buf + n; p += strlen(p) + 1)      // NUL-separated KEY=VALUE pairs
            if (strcmp(p, "SUBSYSTEM=power_supply") == 0) power = 1;
    }
    if (power) box_charge_check();
}

// ------------------------------------------------------------------ network block (box mode)
//
// Box mode cuts the network of apps a SIM box never needs (Google Play services: ~7 % of the overnight standby drain
// on r8q, all background data). Android 13 has no per-package switch reachable from shell, and "restrict background
// data" doesn't touch Play services' foreground-service processes, so: our own iptables/ip6tables chain matched on
// the app's uid. Not persistent across reboot by design: box mode re-applies it at boot, and box-off removes it.

#define NB_CHAIN "tosbisim_block"

// Runs a command and returns its exit code (same thread as box_reap, which therefore never takes this child).
void box_reap(void) { while (waitpid(-1, NULL, WNOHANG) > 0) {} }

// The rules change runs as one detached script: iptables waits for the system's xtables lock (-w 5 per call, a dozen
// calls), which inside the main loop could stall the socket, the keys and the escape gesture for up to a minute
// (audit, 19 Oct). The loop's reaper collects it.
int box_netblock(int uid, int on) {
    if (uid < 10000 || uid > 99999) return -1;                     // app uids only
    char script[1024];
    snprintf(script, sizeof(script),
        "for b in /system/bin/iptables /system/bin/ip6tables; do "
        "$b -w 5 -N " NB_CHAIN " 2>/dev/null; "                        // chain + jump from OUTPUT, created once
        "$b -w 5 -C OUTPUT -j " NB_CHAIN " 2>/dev/null || $b -w 5 -I OUTPUT 1 -j " NB_CHAIN "; "
        "%s; done",
        on ? "$b -w 5 -C " NB_CHAIN " -m owner --uid-owner %1$d -j REJECT 2>/dev/null || $b -w 5 -A " NB_CHAIN " -m owner --uid-owner %1$d -j REJECT"
           : "while $b -w 5 -D " NB_CHAIN " -m owner --uid-owner %1$d -j REJECT 2>/dev/null; do :; done");
    char cmd[1200];
    snprintf(cmd, sizeof(cmd), script, uid);
    char *argv[] = { "/system/bin/sh", "-c", cmd, NULL };
    pid_t pid;
    if (posix_spawn(&pid, argv[0], NULL, NULL, argv, environ) != 0) { LOGW("network block: spawn failed"); return -1; }
    LOGI("network %s for uid %d", on ? "blocked" : "unblocked", uid);
    return 0;
}

// ------------------------------------------------------------------ escape gesture

static int has_bit(const unsigned long *bits, int bit) {
    return (bits[bit / (8 * sizeof(long))] >> (bit % (8 * sizeof(long)))) & 1UL;
}

int box_keys_open(void) {
    DIR *d = opendir("/dev/input");
    if (!d) return 0;
    struct dirent *e;
    while ((e = readdir(d)) && g_nkeys < BOX_MAX_KEYDEVS) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        unsigned long keys[(KEY_MAX + 1) / (8 * sizeof(long)) + 1], types[EV_MAX / (8 * sizeof(long)) + 1];
        memset(keys, 0, sizeof(keys));
        memset(types, 0, sizeof(types));
        ioctl(fd, EVIOCGBIT(0, sizeof(types)), types);
        // Touch panels (absolute axes) can report a "power" key for wake gestures: never watched, never grabbed
        if (!has_bit(types, EV_ABS) && ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys) >= 0 &&
            (has_bit(keys, KEY_VOLUMEUP) || has_bit(keys, KEY_VOLUMEDOWN) || has_bit(keys, KEY_POWER))) {
            char name[64] = "?";
            ioctl(fd, EVIOCGNAME(sizeof(name)), name);
            g_power[g_nkeys] = has_bit(keys, KEY_POWER);
            g_volup[g_nkeys] = has_bit(keys, KEY_VOLUMEUP);
            g_voldown[g_nkeys] = has_bit(keys, KEY_VOLUMEDOWN);
            LOGI("box keys: watching %s (%s)%s", path, name, g_power[g_nkeys] ? " with the power key" : "");
            g_keys[g_nkeys++] = fd;
        } else {
            close(fd);
        }
    }
    closedir(d);
    return g_nkeys;
}

int box_key_fd(int i) { return i < g_nkeys ? g_keys[i] : -1; }

/** A watched input device went away: stop polling it (its slot keeps the others' order; -1 is ignored by poll). */
void box_key_drop(int i) {
    if (i >= g_nkeys || g_keys[i] < 0) return;
    LOGW("box keys: input device %d gone", i);
    close(g_keys[i]);
    g_keys[i] = -1;
    g_grabbed[i] = 0;
}

// Box mode: the power button must not wake the screen (19 Oct: on the Redmi each press lit the screen for a
// moment before box mode turned it off). Grabbing the key devices gives their events to us alone: Android never sees
// the power key, the escape gesture still works (we read the volume keys from the same fd), and the hardware
// long-press reset bypasses Android anyway. Released by POWERKEY 0, by the escape gesture, and by the kernel if we die.
//
// Only when the escape gesture can work (both volume keys watched): otherwise blocking the power key, with the screen
// kept off, would lock the owner out (audit, 19 Oct). And a dead-man: the block lapses after 15 min unless the app
// confirms it again (it does every minute while box mode is on), so a stuck app can't leave the phone sealed.
int box_power_block(int on) {
    if (on) {
        int up = 0, down = 0;
        for (int i = 0; i < g_nkeys; i++) if (g_keys[i] >= 0) { up |= g_volup[i]; down |= g_voldown[i]; }
        if (!up || !down) { LOGW("power key not blocked: the volume keys aren't both watched (no escape gesture)"); return -1; }
        clock_gettime(CLOCK_MONOTONIC, &g_grab_until);
        g_grab_until.tv_sec += 15 * 60;
    } else {
        g_grab_until.tv_sec = 0;
    }
    int n = 0;
    for (int i = 0; i < g_nkeys; i++) {
        if (g_keys[i] < 0 || !g_power[i] || g_grabbed[i] == on) continue;
        if (ioctl(g_keys[i], EVIOCGRAB, on ? 1 : 0) == 0) { g_grabbed[i] = on; n++; }
        else LOGW("power key %s: %s", on ? "grab" : "release", strerror(errno));
    }
    if (n) LOGI("power key %s (%d device%s)", on ? "blocked" : "released", n, n == 1 ? "" : "s");
    return n;
}
int box_key_count(void) { return g_nkeys; }

/** The dead-man check (main loop): an unconfirmed block is released. */
void box_power_check(void) {
    if (g_grab_until.tv_sec == 0) return;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (now.tv_sec < g_grab_until.tv_sec) return;
    LOGW("power key block not confirmed for 15 min: released");
    box_power_block(0);
}

static void broadcast(const char *action) {
    char *argv[] = { "/system/bin/am", "broadcast", "--user", "0", "-a", (char *)action, "-n", ESCAPE_RECEIVER, NULL };
    spawn(argv);
}

// Persistent log: logcat's ring buffer (5 MiB) rolls over within hours on a busy day, losing the host's side of
// a trip (16 Oct 2026). logcat itself writes a rotated copy (2 MiB x 5) of our tags + call/voice/hotspot tags.
#define LOG_FILE "/data/adb/tosbisim/logs/host.log"
#define LOG_PIDF "/data/adb/tosbisim/logs/logcat.pid"

static void log_stop(void) {
    // One capture at a time: stop the running logcat (pid file; a pattern kill would match itself).
    int old = read_int(LOG_PIDF);
    if (old <= 1) return;
    char comm[64] = "", path[64];
    snprintf(path, sizeof(path), "/proc/%d/comm", old);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) { ssize_t n = read(fd, comm, sizeof(comm) - 1); close(fd); if (n > 0) comm[n] = 0; }
    if (strncmp(comm, "logcat", 6) == 0) kill(old, SIGTERM);
}

/** since: "MM-DD hh:mm:ss.mmm" to resume after the file's last line (no copy of what it already holds), or NULL. */
static struct timespec g_log_started;            // the running capture's start (monotonic)

static void log_spawn(const char *since) {
    char *argv[24];
    int a = 0;
    const char *head[] = { "/system/bin/logcat", "-b", "main,system,crash", "-v", "time", "-f", LOG_FILE, "-r", "2048", "-n", "5" };
    for (size_t i = 0; i < sizeof(head) / sizeof(head[0]); i++) argv[a++] = (char *)head[i];
    if (since) { argv[a++] = "-T"; argv[a++] = (char *)since; }
    const char *tags[] = { "-s", "SimRelayHost:I", "simrelayd:I", "voice:D", "Telecom:I", "SemWifiApTimeOutImpl:I", "Tethering:W",
        "AndroidRuntime:E" };
    for (size_t i = 0; i < sizeof(tags) / sizeof(tags[0]); i++) argv[a++] = (char *)tags[i];
    argv[a] = NULL;
    pid_t pid;
    if (posix_spawn(&pid, argv[0], NULL, NULL, argv, environ) != 0) { LOGW("log capture: spawn failed"); return; }
    FILE *f = fopen(LOG_PIDF, "we");
    if (f) { fprintf(f, "%d\n", pid); fclose(f); }
    clock_gettime(CLOCK_MONOTONIC, &g_log_started);
    LOGI("log capture -> " LOG_FILE " (pid %d%s%s)", pid, since ? ", from " : "", since ? since : "");
}

void box_log_capture(void) {
    mkdir("/data/adb/tosbisim", 0700);
    mkdir("/data/adb/tosbisim/logs", 0700);
    log_stop();
    log_spawn(NULL);
}

// logcat can stay alive while logd no longer feeds it (S20 FE, 19 Oct 2026: nothing written after 19:32 although
// the app kept logging), and diagnostics read this file. Nothing written for 10 min: restart the capture from the
// last line's time. A quiet host only costs a restart every 10 min.
void box_log_watch(void) {
    static struct timespec last;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (last.tv_sec && now.tv_sec - last.tv_sec < 60) return;
    last = now;
    if (now.tv_sec - g_log_started.tv_sec < 600) return;   // a capture gets 10 min before it is judged
    struct stat st;
    if (stat(LOG_FILE, &st) != 0) return;
    long age = (long)(time(NULL) - st.st_mtime);
    if (age < 600) return;                        // (negative after a clock change: also left alone)
    char tail[2048] = "", since[19] = "";
    int fd = open(LOG_FILE, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        off_t off = st.st_size > (off_t)sizeof(tail) - 1 ? st.st_size - (off_t)sizeof(tail) + 1 : 0;
        ssize_t n = pread(fd, tail, sizeof(tail) - 1, off);
        close(fd);
        if (n > 0) {
            tail[n] = 0;
            // The newest whole line with a "-v time" stamp ("MM-DD hh:mm:ss.mmm"); the last one may be cut short.
            for (char *p = tail + n; p > tail; ) {
                *p = 0;
                char *line = p;
                while (line > tail && line[-1] != '\n') line--;
                if (line > tail && strlen(line) >= 18 && line[2] == '-' && line[5] == ' ' && line[8] == ':' && line[14] == '.') {
                    memcpy(since, line, 18); since[18] = 0; break;
                }
                p = line > tail ? line - 1 : tail;
            }
        }
    }
    LOGW("log capture: nothing written for %ld s, restarting it", age);
    log_stop();
    log_spawn(since[0] ? since : NULL);
}

void box_announce(void) { broadcast("dev.simrelay.host.DAEMON_UP"); }

static void escape(void) {
    box_power_block(0);                  // the person at the phone gets their buttons back at once
    LOGI("escape gesture: waking the screen and telling the app");
    box_screen(1);
    broadcast(ESCAPE_ACTION);
}

void box_key_event(int fd) {
    struct input_event ev[16];
    ssize_t n;
    while ((n = read(fd, ev, sizeof(ev))) > 0) {
        for (size_t i = 0; i < (size_t)n / sizeof(ev[0]); i++) {
            if (ev[i].type != EV_KEY || ev[i].value != 1) continue;           // key down only
            if (ev[i].code != KEY_VOLUMEUP && ev[i].code != KEY_VOLUMEDOWN) continue;
            memmove(g_seq, g_seq + 1, 5 * sizeof(g_seq[0]));
            memmove(g_seq_t, g_seq_t + 1, 5 * sizeof(g_seq_t[0]));
            g_seq[5] = ev[i].code == KEY_VOLUMEUP ? 1 : -1;
            g_seq_t[5] = now_ns();
            static const int want[6] = { 1, 1, 1, -1, -1, -1 };
            if (memcmp(g_seq, want, sizeof(want)) == 0 && g_seq_t[5] - g_seq_t[0] <= ESCAPE_WINDOW_NS) {
                memset(g_seq, 0, sizeof(g_seq));
                escape();
            }
        }
    }
}
