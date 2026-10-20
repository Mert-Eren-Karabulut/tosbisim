# TosbiSim

**Turn an old Android phone into a SIM box for your iPhone.** Put your SIM in a spare rooted Android phone, leave
it in a drawer, and get its calls, texts and mobile data on your iPhone over Bluetooth — like a SIM adapter, but
with a phone you already own.

- **Calls** ring on your iPhone (native CallKit screen) and are answered, made and talked on from the iPhone;
  the audio is relayed digitally over Bluetooth LE. The host phone stays completely silent.
- **Texts** arrive in the TosbiSim app, with one-time codes offered in iOS AutoFill.
- **Data**: the host's Wi-Fi hotspot, switched on from the iPhone (automatically when you have no Wi-Fi).
- **Dual SIM** hosts, **box mode** (screen kept off, battery saving, charge limit), SIM PIN unlock.

This repository is the **host side** (the Android app, its root daemon and Magisk module), published under the
GNU GPL v3. The iPhone app is distributed separately.

## Which phones work?

See the **[support page](https://mert-eren-karabulut.github.io/tosbisim/)** (also in the host app before you root,
and in the iPhone app under Host → Check a phone). Tiers: *verified* (measured by us), *expected* (same audio
platform as a verified phone), *experimental* (researched, not measured), *unknown*. Whatever the tier, call
audio switches on only after a short **call test** on your phone; until then the host runs in Basic mode
(texts, call control, hotspot).

Requirements: Android 11 or newer, an unlockable bootloader, Magisk, Bluetooth LE peripheral support.
Unlocking the bootloader erases the phone and may void its warranty. TosbiSim never changes the phone's IMEI.

## Setting up a host

1. Check your phone on the [support page](https://mert-eren-karabulut.github.io/tosbisim/), then unlock the
   bootloader and install [Magisk](https://github.com/topjohnwu/Magisk) (see its guide for your phone).
2. On the phone, download `TosbiSim-Host-<version>-Magisk.zip` from the Releases page.
3. Open Magisk → *Modules* → *Install from storage* → choose the zip → *Reboot*. TosbiSim Host starts by itself
   after the restart. (There is no separate app to install: Google Play Protect blocks it on many phones; the
   Magisk module carries it.)
4. Pair from the TosbiSim iPhone app (*Add host*), then run *Host → Call audio → Run the call test* with a friend.
5. Turn on *Box mode* and put the phone away. To use its screen again: Volume Up ×3, then Volume Down ×3.
6. To give the phone back: on its screen, *Stop using this phone as a host*. Its settings, sounds and default apps
   come back, TosbiSim removes itself and the phone restarts (Magisk stays).

## Building from source

- Host app + daemon + module: `host/release.sh` (needs the Android SDK/NDK and your own signing key in
  `~/.tosbisim/signing.properties`), or the developer loop in [host/README.md](host/README.md).
- Protocol shared with the iPhone app: [protocol/](protocol/) (MIT).
- Device profiles and the support table: [profiles/](profiles/). Measuring a new phone: [tools/](tools/)
  (read-only dump + the four live-call experiments).

## Licence

Host: GPL-3.0-or-later ([LICENSE](LICENSE)). Protocol: MIT ([protocol/LICENSE](protocol/LICENSE)).

---

### Türkçe

**Eski bir Android telefonu iPhone'unuz için bir SIM kutusuna dönüştürün.** SIM'inizi root'lu yedek bir Android
telefona takın, çekmeceye koyun; aramalar, mesajlar ve mobil veri Bluetooth ile iPhone'unuza gelsin. Hangi
telefonların çalıştığını [destek sayfasında](https://mert-eren-karabulut.github.io/tosbisim/) görebilirsiniz. Arama
sesi, her telefonda kısa bir arama testinden sonra açılır. Kurulum: önyükleyici kilidini açıp Magisk kurun,
`TosbiSim-Host-<sürüm>-Magisk.zip` dosyasını Releases sayfasından telefona indirin, Magisk → *Modüller* →
*Depolamadan yükle* ile seçip *Yeniden başlat*'a dokunun (ayrı bir uygulama kurmanız gerekmez). Ardından
iPhone'dan eşleştirip arama testini yapın. Telefonu geri almak için ekranındaki *Bu telefonu ana cihaz olarak
kullanmayı bırak*'a dokunun. TosbiSim telefonun IMEI'sini asla değiştirmez.
