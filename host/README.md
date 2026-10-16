# TosbiSim Host — developer notes (GPL-3.0-or-later)

Runs on the rooted spare phone that holds the SIM (package `dev.simrelay.host`; "SIM Relay" is the code name).
Users install one APK (`host/release.sh` builds it): before root it is a compatibility check, after root it installs
its own Magisk module. The steps below are the developer loop over adb.

| Part | What it does |
| --- | --- |
| `daemon/` — `simrelayd` (C, NDK) | Only root process. Owns in-call mixer state: `CALL_START <session>` applies the profile's capture route, the session's in-call music mixer and both device mutes; `CALL_END` restores. Re-applies a control when the vendor HAL rewrites it (ALSA control events). Clears device mutes at start, exit and on client loss. Socket `@simrelayd` (root + app uid only). |
| `android/` — host app (Kotlin, priv-app) | Non-UI `InCallService` (CONTROL_INCALL_EXPERIENCE) maps the active call's SIM slot to the voice session and drives the daemon; `HostService` keeps the daemon connection and sends the device profile (`profiles/`); BLE GATT + L2CAP link to the iPhone (`BleLink`, `AudioBridge`, `Relay`); box mode, charge limit, SIMs, guided call test (`VoiceGate`). |
| `magisk/` — module | Installs the app as a privileged system app + privapp whitelist, sepolicy rule, starts the daemon with the app's uid, silence policy at boot (DND total silence, no haptics/system sounds, Samsung call end/connect/waiting tones off, zero ring/notification/system volume), runtime permissions, Doze exemption. |

Build and install (phone on USB, rooted with Magisk):
```
host/daemon/build.sh
(cd host/android && ./gradlew assembleDebug)
host/magisk/build.sh            # refuses if the APK asks for a non-whitelisted privileged permission
adb push host/magisk/out/simrelay-host-<ver>.zip /data/local/tmp/
adb shell su -c 'magisk --install-module /data/local/tmp/simrelay-host-<ver>.zip' && adb reboot
```
Logs: `adb logcat -s simrelayd SimRelayHost`, boot script log `/data/local/tmp/simrelay-service.log`.

Daemon commands (debug client `--client "A|SLEEP n|B"`): `HELLO`, `STATUS`, `SET <key> <value>`,
`CALL_START <session>`, `CALL_END`, `IFSTATS <iface>` (hotspot data counter), `NEIGH <iface>` (hotspot devices).
Host service debug actions (root only, the service is not exported): `PAIR`, `STATUS`, `INJECT_SMS --es from --es body`.

## Hard-won lessons (S20 FE SM-G780G, Android 13, Oct 2026)
- **Privileged permissions must be whitelisted before the APK asks for them** (`ro.control_privapp_permissions
  =enforce`): ship new privileged permissions only through the module (XML + APK together). `build.sh` checks.
- **Android caches the parse of system apps** in `/data/system/package_cache` and ignores mtime for the system
  partition: `customize.sh` deletes our entry, otherwise an updated APK keeps its old permissions.
- Quick iterations: `adb install -r` installs an update over the system app (keeps privileged status) — only when
  no new privileged permission is requested.
- A running daemon binary can't be overwritten ("Text file busy"): copy beside it, `mv -f` over it, `pkill -x
  simrelayd` (service.sh restarts it in 2 s).
- Android 13: `onTetheredInterfacesChanged` gets `Set<TetheringInterface>` (name via `getInterface()`), and
  `onClientsChanged` is empty without NETWORK_SETTINGS — devices come from the kernel ARP table (`NEIGH`).
  `hostapd_cli` from root times out; `dumpsys wifi` works but costs ~360 ms / 200 KB.
- Reflection proxies for framework callbacks must answer `hashCode`/`equals`/`toString`.
- Samsung call tones (`call_end_tone`, `call_conn_tone`, `volume_waiting_tone`) play outside the muted voice path.
Debug client (root): `adb shell su -c '/data/adb/modules/simrelay-host/simrelayd --client "STATUS"'`.
If a module change ever stops the phone booting: adb comes up before the framework —
`adb shell su -c 'magisk --remove-modules'` removes modules and reboots.
