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
static int g_power[BOX_MAX_KEYDEVS], g_grabbed[BOX_MAX_KEYDEVS];
static int g_nkeys = 0;
static int g_seq[6];          // last six volume presses: +1 up, -1 down
static long long g_seq_t[6];

static long long now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long)t.tv_sec * 1000000000LL + t.tv_nsec;
}

// Fire-and-forget (SIGCHLD is ignored, so children are reaped by the kernel).
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

static const char *CUT_NODES[] = {
    "/sys/class/power_supply/battery/batt_slate_mode",                 // Samsung: 1 = run from the battery
    "/sys/class/power_supply/battery/input_suspend",                   // Qualcomm: 1 = charger input suspended
};
#define BATT_CAPACITY "/sys/class/power_supply/battery/capacity"
#define LIMIT_DIR "/data/adb/tosbisim"
#define LIMIT_FILE LIMIT_DIR "/charge_limit"
#define MIN_ON_BATTERY 15

static int g_limit = 0;                // 0 or 100 = no limit
static int g_cutting = 0;

static const char *first_writable(const char *const *nodes, size_t n) {
    for (size_t i = 0; i < n; i++) if (access(nodes[i], W_OK) == 0) return nodes[i];
    return NULL;
}
static const char *cap_node(void) { return first_writable(CAP_NODES, sizeof(CAP_NODES) / sizeof(CAP_NODES[0])); }
static const char *cut_node(void) { return first_writable(CUT_NODES, sizeof(CUT_NODES) / sizeof(CUT_NODES[0])); }

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
    const char *node = cut_node();
    if (!node || write_int(node, on) < 0) return;
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

// Runs a command and returns its exit code. SIGCHLD is normally ignored (fire-and-forget children are auto-reaped),
// which makes waitpid lose the status, so it is set back to default just for this wait.
static int run_wait(char *const argv[]) {
    signal(SIGCHLD, SIG_DFL);
    pid_t pid;
    int rc = -1, st = 0;
    if (posix_spawn(&pid, argv[0], NULL, NULL, argv, environ) == 0 && waitpid(pid, &st, 0) == pid)
        rc = WIFEXITED(st) ? WEXITSTATUS(st) : -1;
    signal(SIGCHLD, SIG_IGN);
    return rc;
}

static int ipt(const char *bin, const char *a1, const char *a2, const char *a3, const char *a4, const char *a5,
               const char *a6, const char *a7, const char *a8, const char *a9) {
    char *argv[] = { (char *)bin, "-w", (char *)a1, (char *)a2, (char *)a3, (char *)a4, (char *)a5, (char *)a6,
                     (char *)a7, (char *)a8, (char *)a9, NULL };
    return run_wait(argv);
}

int box_netblock(int uid, int on) {
    if (uid < 10000 || uid > 99999) return -1;                     // app uids only
    char u[16];
    snprintf(u, sizeof(u), "%d", uid);
    const char *bins[] = { "/system/bin/iptables", "/system/bin/ip6tables" };
    for (int i = 0; i < 2; i++) {
        const char *b = bins[i];
        // Chain + jump from OUTPUT, created once (-N fails harmlessly when it exists).
        ipt(b, "-N", NB_CHAIN, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
        if (ipt(b, "-C", "OUTPUT", "-j", NB_CHAIN, NULL, NULL, NULL, NULL, NULL) != 0)
            ipt(b, "-I", "OUTPUT", "1", "-j", NB_CHAIN, NULL, NULL, NULL, NULL);
        int present = ipt(b, "-C", NB_CHAIN, "-m", "owner", "--uid-owner", u, "-j", "REJECT", NULL) == 0;
        if (on && !present) ipt(b, "-A", NB_CHAIN, "-m", "owner", "--uid-owner", u, "-j", "REJECT", NULL);
        if (!on) while (ipt(b, "-D", NB_CHAIN, "-m", "owner", "--uid-owner", u, "-j", "REJECT", NULL) == 0) {}
    }
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
        unsigned long keys[(KEY_MAX + 1) / (8 * sizeof(long)) + 1];
        memset(keys, 0, sizeof(keys));
        if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys) >= 0 &&
            (has_bit(keys, KEY_VOLUMEUP) || has_bit(keys, KEY_VOLUMEDOWN) || has_bit(keys, KEY_POWER))) {
            char name[64] = "?";
            ioctl(fd, EVIOCGNAME(sizeof(name)), name);
            g_power[g_nkeys] = has_bit(keys, KEY_POWER);
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

// Box mode: the power button must not wake the screen (19 Oct: on the Redmi each press lit the screen for a
// moment before box mode turned it off). Grabbing the key devices gives their events to us alone: Android never sees
// the power key, the escape gesture still works (we read the volume keys from the same fd), and the hardware
// long-press reset bypasses Android anyway. Released by POWERKEY 0, by the escape gesture, and by the kernel if we die.
int box_power_block(int on) {
    int n = 0;
    for (int i = 0; i < g_nkeys; i++) {
        if (!g_power[i] || g_grabbed[i] == on) continue;
        if (ioctl(g_keys[i], EVIOCGRAB, on ? 1 : 0) == 0) { g_grabbed[i] = on; n++; }
        else LOGW("power key %s: %s", on ? "grab" : "release", strerror(errno));
    }
    if (n) LOGI("power key %s (%d device%s)", on ? "blocked" : "released", n, n == 1 ? "" : "s");
    return n;
}
int box_key_count(void) { return g_nkeys; }

static void broadcast(const char *action) {
    char *argv[] = { "/system/bin/am", "broadcast", "--user", "0", "-a", (char *)action, "-n", ESCAPE_RECEIVER, NULL };
    spawn(argv);
}

// Persistent log: logcat's ring buffer (5 MiB) rolls over within hours on a busy day, losing the host's side of
// a trip (16 Oct 2026). logcat itself writes a rotated copy (2 MiB x 5) of our tags + call/voice/hotspot tags.
void box_log_capture(void) {
    mkdir("/data/adb/tosbisim", 0700);
    mkdir("/data/adb/tosbisim/logs", 0700);
    // One capture at a time: stop the previous daemon run's logcat (pid file; a pattern kill would match itself).
    const char *pidf = "/data/adb/tosbisim/logs/logcat.pid";
    int old = read_int(pidf);
    if (old > 1) {
        char comm[64] = "", path[64];
        snprintf(path, sizeof(path), "/proc/%d/comm", old);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) { ssize_t n = read(fd, comm, sizeof(comm) - 1); close(fd); if (n > 0) comm[n] = 0; }
        if (strncmp(comm, "logcat", 6) == 0) kill(old, SIGTERM);
    }
    char *argv[] = { "/system/bin/logcat", "-b", "main,system,crash", "-v", "time",
        "-f", "/data/adb/tosbisim/logs/host.log", "-r", "2048", "-n", "5",
        "-s", "SimRelayHost:I", "simrelayd:I", "voice:D", "Telecom:I", "SemWifiApTimeOutImpl:I", "Tethering:W",
        "AndroidRuntime:E", NULL };
    pid_t pid;
    if (posix_spawn(&pid, argv[0], NULL, NULL, argv, environ) != 0) { LOGW("log capture: spawn failed"); return; }
    FILE *f = fopen(pidf, "we");
    if (f) { fprintf(f, "%d\n", pid); fclose(f); }
    LOGI("log capture -> /data/adb/tosbisim/logs/host.log (pid %d)", pid);
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
