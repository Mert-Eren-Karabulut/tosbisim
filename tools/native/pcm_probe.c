// What a PCM device accepts: the kernel's hw_params ranges (HW_REFINE on an open device), then real tinyalsa opens
// for common call-audio configs. Read-only apart from opening/closing the PCM (no audio written).
// Usage: pcm_probe <card> <device> <p|c>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <sound/asound.h>

static struct snd_interval *iv(struct snd_pcm_hw_params *p, int v) { return &p->intervals[v - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL]; }
static void any(struct snd_pcm_hw_params *p) {
    memset(p, 0, sizeof *p);
    for (int i = 0; i <= SNDRV_PCM_HW_PARAM_LAST_MASK - SNDRV_PCM_HW_PARAM_FIRST_MASK; i++) memset(p->masks[i].bits, 0xff, sizeof p->masks[i].bits);
    for (int i = 0; i <= SNDRV_PCM_HW_PARAM_LAST_INTERVAL - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL; i++) { p->intervals[i].min = 0; p->intervals[i].max = ~0u; }
    p->rmask = ~0u; p->info = ~0u;
}

struct pcm_config { unsigned channels, rate, period_size, period_count; int format;
    unsigned start_threshold, stop_threshold, silence_threshold, silence_size; int avail_min; };

int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage: pcm_probe card device p|c\n"); return 2; }
    int card = atoi(argv[1]), dev = atoi(argv[2]); int play = argv[3][0] == 'p';
    char path[64]; snprintf(path, sizeof path, "/dev/snd/pcmC%dD%d%c", card, dev, play ? 'p' : 'c');
    int fd = open(path, O_RDWR | O_NONBLOCK);
    if (fd < 0) { perror(path); } else {
        struct snd_pcm_hw_params p; any(&p);
        if (ioctl(fd, SNDRV_PCM_IOCTL_HW_REFINE, &p) < 0) perror("HW_REFINE");
        else {
            const char *n[] = {"sample_bits","frame_bits","channels","rate","period_time","period_size","period_bytes","periods","buffer_time","buffer_size","buffer_bytes","tick_time"};
            for (int v = SNDRV_PCM_HW_PARAM_FIRST_INTERVAL; v <= SNDRV_PCM_HW_PARAM_LAST_INTERVAL && v - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL < 12; v++)
                printf("%-13s %u..%u%s\n", n[v - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL], iv(&p, v)->min, iv(&p, v)->max, iv(&p, v)->integer ? " (integer)" : "");
            printf("formats mask 0x%08x (S16_LE=bit2, S24_LE=bit6, S32_LE=bit10)\n", p.masks[0].bits[0]);
        }
        close(fd);
    }
    // real opens through the system tinyalsa (as simrelayd does)
    const char *libs[] = {"libtinyalsa.so", "/system/lib64/libtinyalsa.so", "/vendor/lib64/libtinyalsa.so"};
    void *h = NULL; for (int i = 0; i < 3 && !h; i++) h = dlopen(libs[i], RTLD_NOW);
    if (!h) { printf("no tinyalsa: %s\n", dlerror()); return 0; }
    void *(*popen_)(unsigned, unsigned, unsigned, struct pcm_config *) = dlsym(h, "pcm_open");
    int (*ready)(void *) = dlsym(h, "pcm_is_ready"); const char *(*err)(void *) = dlsym(h, "pcm_get_error"); int (*pclose_)(void *) = dlsym(h, "pcm_close");
    unsigned rates[] = {8000, 16000, 32000, 48000}, chs[] = {1, 2}, ms[] = {5, 10, 20}, cnts[] = {2, 4};
    for (int a = 0; a < 4; a++) for (int b = 0; b < 2; b++) for (int c = 0; c < 3; c++) for (int d = 0; d < 2; d++) {
        struct pcm_config cfg = { chs[b], rates[a], rates[a] * ms[c] / 1000, cnts[d], 0, 0, 0, 0, 0, 0 };
        void *pcm = popen_(card, dev, play ? 0 : 0x10000000, &cfg);
        int ok = pcm && ready(pcm);
        printf("%5u Hz %u ch period %2u ms (%4u fr) x%u: %s\n", rates[a], chs[b], ms[c], cfg.period_size, cnts[d], ok ? "OK" : (pcm ? err(pcm) : "null"));
        if (pcm) pclose_(pcm);
    }
    return 0;
}
