// Write integer/boolean ALSA mixer controls by name, straight through the control ioctl.
// Exists because stock tinymix range-checks against the reported max, which breaks controls whose
// max is 0xFFFFFFFF (e.g. Qualcomm "Voice Tx/Rx Device Mute" = {mute, vsid, ramp_ms}).
// Usage: alsactl_write <card> "<control name>" <v0> [v1 ...]   (values: decimal, hex, or negative)
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <sound/asound.h>

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <card> \"<control name>\" <v0> [v1 ...]\n", argv[0]);
        return 2;
    }
    char path[64];
    snprintf(path, sizeof(path), "/dev/snd/controlC%s", argv[1]);
    int fd = open(path, O_RDWR);
    if (fd < 0) { fprintf(stderr, "open %s: %s\n", path, strerror(errno)); return 1; }

    struct snd_ctl_elem_info info;
    memset(&info, 0, sizeof(info));
    info.id.iface = SNDRV_CTL_ELEM_IFACE_MIXER;
    strncpy((char *)info.id.name, argv[2], sizeof(info.id.name) - 1);
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &info) < 0) {
        fprintf(stderr, "no control '%s': %s\n", argv[2], strerror(errno)); return 1;
    }
    if (info.type != SNDRV_CTL_ELEM_TYPE_INTEGER && info.type != SNDRV_CTL_ELEM_TYPE_BOOLEAN) {
        fprintf(stderr, "'%s' is not INTEGER/BOOLEAN (type %d)\n", argv[2], info.type); return 1;
    }
    unsigned n = (unsigned)(argc - 3);
    if (n > info.count) { fprintf(stderr, "'%s' takes %u values, got %u\n", argv[2], info.count, n); return 1; }

    struct snd_ctl_elem_value val;
    memset(&val, 0, sizeof(val));
    val.id = info.id;
    for (unsigned i = 0; i < n; i++) val.value.integer.value[i] = strtoll(argv[3 + i], NULL, 0);
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_WRITE, &val) < 0) {
        fprintf(stderr, "write '%s': %s\n", argv[2], strerror(errno)); return 1;
    }
    printf("ok '%s' numid=%u:", argv[2], info.id.numid);
    for (unsigned i = 0; i < n; i++) printf(" %lld", (long long)val.value.integer.value[i]);
    printf("\n");
    close(fd);
    return 0;
}
