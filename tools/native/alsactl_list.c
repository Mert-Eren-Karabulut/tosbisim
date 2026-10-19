// List every ALSA mixer control of a card with its type and current value(s), like `tinymix` with no arguments.
// Read-only. For phones that ship without tinymix (e.g. Xiaomi MIUI).
// Usage: alsactl_list [card]      (default card 0)
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <sound/asound.h>

int main(int argc, char **argv) {
    char path[64];
    snprintf(path, sizeof(path), "/dev/snd/controlC%s", argc > 1 ? argv[1] : "0");
    int fd = open(path, O_RDONLY);
    if (fd < 0) { fprintf(stderr, "open %s: %s\n", path, strerror(errno)); return 1; }

    struct snd_ctl_elem_list list;
    memset(&list, 0, sizeof(list));
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) { perror("ELEM_LIST"); return 1; }
    unsigned n = list.count;
    list.space = n;
    list.pids = calloc(n, sizeof(struct snd_ctl_elem_id));
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) { perror("ELEM_LIST"); return 1; }
    printf("Number of controls: %u\nctl\ttype\tnum\tname\tvalue\n", list.used);

    for (unsigned i = 0; i < list.used; i++) {
        struct snd_ctl_elem_info info;
        memset(&info, 0, sizeof(info));
        info.id = list.pids[i];
        if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &info) < 0) continue;
        const char *t = info.type == SNDRV_CTL_ELEM_TYPE_BOOLEAN ? "BOOL" : info.type == SNDRV_CTL_ELEM_TYPE_INTEGER ? "INT"
                      : info.type == SNDRV_CTL_ELEM_TYPE_ENUMERATED ? "ENUM" : info.type == SNDRV_CTL_ELEM_TYPE_BYTES ? "BYTE"
                      : info.type == SNDRV_CTL_ELEM_TYPE_INTEGER64 ? "INT64" : "OTHER";
        printf("%u\t%s\t%u\t%s\t", info.id.numid, t, info.count, (char *)info.id.name);
        struct snd_ctl_elem_value v;
        memset(&v, 0, sizeof(v));
        v.id = info.id;
        int readable = (info.access & SNDRV_CTL_ELEM_ACCESS_READ) && ioctl(fd, SNDRV_CTL_IOCTL_ELEM_READ, &v) == 0;
        unsigned show = info.count > 16 ? 16 : info.count;
        if (!readable) printf("(write-only)");
        else if (info.type == SNDRV_CTL_ELEM_TYPE_ENUMERATED) {
            for (unsigned k = 0; k < show; k++) {
                struct snd_ctl_elem_info e = info;
                e.value.enumerated.item = v.value.enumerated.item[k];
                if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &e) == 0) printf("%s%s", k ? " " : "", e.value.enumerated.name);
            }
            printf("  [options:");
            for (unsigned item = 0; item < info.value.enumerated.items && item < 24; item++) {
                struct snd_ctl_elem_info e = info;
                e.value.enumerated.item = item;
                if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &e) == 0) printf(" %s", e.value.enumerated.name);
            }
            printf("]");
        } else if (info.type == SNDRV_CTL_ELEM_TYPE_BOOLEAN) {
            for (unsigned k = 0; k < show; k++) printf("%s%s", k ? " " : "", v.value.integer.value[k] ? "On" : "Off");
        } else if (info.type == SNDRV_CTL_ELEM_TYPE_INTEGER) {
            for (unsigned k = 0; k < show; k++) printf("%s%ld", k ? " " : "", v.value.integer.value[k]);
            printf("  (range %ld..%ld)", info.value.integer.min, info.value.integer.max);
        } else if (info.type == SNDRV_CTL_ELEM_TYPE_BYTES) {
            printf("%u bytes", info.count);
        }
        printf("\n");
    }
    return 0;
}
