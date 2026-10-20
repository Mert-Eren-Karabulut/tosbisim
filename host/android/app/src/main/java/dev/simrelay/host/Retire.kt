// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telecom.TelecomManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Stop using this phone as a host": gives the phone back and removes TosbiSim. A host overrides a lot of the phone
 * (20 Oct 2026: people must be able to go back to using it as a phone): its sounds, vibration and Do Not Disturb,
 * box mode's screen, button and network settings, the default phone and messaging apps, root for the app, the module.
 *
 * In order: no call on, root answers and the removal script is in place (nothing changes before that); the iPhone is
 * told (an Unpair it didn't ask for) and forgotten; box mode off (it restores its own saved
 * settings); the hotspot off; volumes and Do Not Disturb back; then a root script that outlives the app restores the
 * settings service.sh changed (from the snapshot it took before its first change, else Android's defaults), hands the
 * default apps back, turns Wi-Fi on, revokes root and the battery exemption, marks the module for removal, uninstalls
 * the app, deletes TosbiSim's files and restarts the phone (a Magisk module only goes away with a restart). Magisk and
 * the unlocked bootloader stay: they are the user's, not ours.
 */
object Retire {
    private const val TAG = "SimRelayHost"
    private const val PKG = "dev.simrelay.host"
    private val STREAMS = intArrayOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION)
    private val exec = Bg.single("retire", daemon = false)
    private val main = Handler(Looper.getMainLooper())

    /** Giving the phone back: guards (SMS role, box mode, silence) must not undo it meanwhile. */
    @Volatile var running = false; private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("retire", Context.MODE_PRIVATE)

    /** Once, before the host first takes the phone app role: the app to give it back to. */
    fun rememberDialer(ctx: Context, pkg: String?) {
        if (pkg.isNullOrEmpty() || pkg == PKG || prefs(ctx).contains("dialer")) return
        prefs(ctx).edit().putString("dialer", pkg).apply()
    }

    /** Once, before the host first silences the phone: the volumes to give back. */
    fun snapshotVolumes(ctx: Context, am: AudioManager) {
        val p = prefs(ctx)
        if (p.contains("volumes")) return
        val o = JSONObject()
        STREAMS.forEach { s -> runCatching { o.put("$s", am.getStreamVolume(s)) } }
        p.edit().putString("volumes", o.toString()).apply()
    }

    /** Where the removal is, for the setup screen: a string resource, 0 = not running. Process-level, so a rotation
     *  finds it (the new screen showed "Checking…" for good; audit, 20 Oct 2026). */
    @Volatile var step = 0; private set
    /** Why the last try stopped (a string resource), 0 = it didn't fail. Cleared by the next try or [dismiss]. */
    @Volatile var failed = 0; private set
    /** The failed try had already told the iPhone: it must pair again. */
    @Volatile var unpaired = false; private set
    /** The setup screen on show, told of every change (on main). */
    @Volatile var changed: (() -> Unit)? = null

    private const val REMOVER = "/data/local/tmp/tosbisim-remove.sh"

    private fun notifyChanged() = main.post { changed?.invoke() }
    private fun progress(res: Int) { step = res; notifyChanged() }

    /** The failure card was read. */
    fun dismiss() { if (!running) { failed = 0; unpaired = false; notifyChanged() } }

    /** Start (the setup screen follows [step] / [failed]). The phone restarts at the end. */
    fun start(ctx: Context) {
        if (running) return
        running = true
        failed = 0; unpaired = false
        val app = ctx.applicationContext
        progress(R.string.retire_step_check)
        exec.execute { run(app) }
    }

    /** A call on this phone (ringing, dialling, active or held), seen by TosbiSim or by Telecom. */
    @android.annotation.SuppressLint("MissingPermission")   // READ_PHONE_STATE from the module; a refusal is caught
    private fun inCall(app: Context) = CallRegistry.anyCall() ||
        runCatching { app.getSystemService(TelecomManager::class.java).isInCall }.getOrDefault(false)

    /**
     * Nothing irreversible happens before root answered and the removal script is in place: a phone whose root was
     * refused got its iPhone unpaired, box mode off and its sounds back, then "couldn't remove" (audit, 20 Oct 2026).
     * Any later failure rolls back to a working host ([rollBack]); `running` can't stick (it blocked every guard).
     */
    private fun run(app: Context) {
        var touched = false
        var boxWasOn = false
        var ok = false
        try {
            // As the updater: never mid-call (the script ends with a restart)
            if (inCall(app)) { failed = R.string.retire_in_call; return }
            if (Root.sh("id -u", 25_000).last != "0") { failed = R.string.retire_no_root; return }
            // The phone app from before TosbiSim: remembered when the host took the role, else box mode's saved one
            // (read before its restore drops it)
            val boxDialer = prefs(app).getString("dialer", null) ?: runCatching {
                JSONObject(app.getSharedPreferences("box", Context.MODE_PRIVATE).getString("orig", "{}")!!).optString("dialer")
            }.getOrDefault("")
            val script = File(app.cacheDir, "tosbisim-remove.sh")
            val staged = runCatching { script.writeText(script(app, boxDialer)) }.isSuccess &&
                Root.sh("cp ${Root.q(script.path)} $REMOVER && chmod 700 $REMOVER", 15_000).ok
            if (!staged) { failed = R.string.retire_prepare_failed; return }
            Log.i(TAG, "RETIRE: giving the phone back and removing TosbiSim")
            Diag.event("host_removed", JSONObject().put("what", "user"), withLog = false, force = true)

            touched = true
            progress(R.string.retire_step_iphone)
            if (Relay.started()) {
                unpaired = Relay.pairedClient() != null
                await { done -> Relay.retire(done) }
            }

            progress(R.string.retire_step_settings)
            boxWasOn = Box.on
            await { done -> main.post { if (Box.on) Box.set(false); done() } }
            if (Hotspot.isOn(app)) await { done -> Hotspot.set(app, false) { _, _ -> done() } }
            restoreSound(app)
            if (Daemon.isStarted()) await { done -> Daemon.query("STATUS") { done() } }   // box mode's daemon commands have run
            Thread.sleep(1500)                                      // the diagnostics event on its way

            if (inCall(app)) { failed = R.string.retire_in_call; return }   // one came in meanwhile
            progress(R.string.retire_step_restart)
            val r = Root.sh("setsid sh $REMOVER </dev/null >/dev/null 2>&1 &", 15_000)
            Log.i(TAG, "RETIRE: removal script started (${r.code})")
            if (!r.ok) { failed = R.string.retire_failed; return }
            ok = true                                               // the script takes it from here (and restarts)
        } catch (e: Exception) {
            Log.e(TAG, "RETIRE failed: $e")
        } finally {
            if (!ok) {
                if (failed == 0) failed = R.string.retire_failed
                running = false
                step = 0
                Log.w(TAG, "RETIRE stopped: ${app.resources.getResourceEntryName(failed)}${if (touched) ", rolling back" else ""}")
                if (touched) runCatching { rollBack(app, boxWasOn) }.onFailure { Log.e(TAG, "retire roll-back: $it") }
                Diag.event("host_remove_failed", JSONObject().put("what", app.resources.getResourceEntryName(failed)).put("rolled_back", touched))
            }
            notifyChanged()
        }
    }

    /**
     * The removal didn't start: back to a working host (silent, box mode as it was, Bluetooth advertising) rather than
     * a phone half given back that serves nobody. The pairing stays forgotten: the iPhone was told.
     */
    private fun rollBack(app: Context, boxWasOn: Boolean) {
        HostService.silence(app)
        if (boxWasOn) await { done -> main.post { if (Box.ready && !Box.on) Box.set(true); done() } }
        if (Relay.started()) Relay.unretire()
    }

    private fun await(timeoutS: Long = 10, start: (() -> Unit) -> Unit) {
        val latch = CountDownLatch(1)
        runCatching { start { latch.countDown() } }.onFailure { Log.w(TAG, "retire: $it"); latch.countDown() }
        if (!latch.await(timeoutS, TimeUnit.SECONDS)) Log.w(TAG, "retire: a step timed out, going on")
    }

    /**
     * Sound as it was before TosbiSim (a silent snapshot, from a phone set up before snapshots, gives 2/3). Do Not
     * Disturb first, then the ringer, then volumes: under total silence Android refuses the ringer mode and holds ring
     * volumes at zero (audit, 20 Oct 2026). Then the call volume and mic Basic mode's call silence may have left.
     */
    private fun restoreSound(app: Context) {
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val nm = app.getSystemService(NotificationManager::class.java)
        runCatching { if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
            .onFailure { Log.w(TAG, "retire: do not disturb: $it") }
        // The host left the ringer silent (or vibrate): ringing again is what an ordinary phone does (DND access needed)
        runCatching { am.ringerMode = AudioManager.RINGER_MODE_NORMAL }.onFailure { Log.w(TAG, "retire: ringer: $it") }
        val saved = runCatching { JSONObject(prefs(app).getString("volumes", "{}")!!) }.getOrDefault(JSONObject())
        for (s in STREAMS) runCatching {
            val min = am.getStreamMinVolume(s)
            val v = saved.optInt("$s", -1).takeIf { it > min } ?: (am.getStreamMaxVolume(s) * 2 / 3)
            am.setStreamVolume(s, v, 0)
        }.onFailure { Log.w(TAG, "retire: volume $s: $it") }
        BasicSilence.set(app, false)                                // its saved call volume, if a call left one
        runCatching {
            val v = AudioManager.STREAM_VOICE_CALL
            if (am.getStreamVolume(v) <= am.getStreamMinVolume(v)) am.setStreamVolume(v, am.getStreamMaxVolume(v) * 2 / 3, 0)
            am.isMicrophoneMute = false
        }.onFailure { Log.w(TAG, "retire: call volume: $it") }
    }

    /** The phone's own messaging app: the system app that receives SMS (Google Messages, Samsung Messages, MIUI's). */
    private fun systemSmsApp(app: Context): String = runCatching {
        app.packageManager.queryBroadcastReceivers(Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION), 0)
            .map { it.activityInfo.applicationInfo }.filter { it.packageName != PKG }
            .sortedBy { if (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) 0 else 1 }
            .firstOrNull()?.packageName
    }.getOrNull().orEmpty()

    private fun script(app: Context, boxDialer: String): String {
        val uid = runCatching { app.packageManager.getPackageUid(PKG, 0) }.getOrDefault(-1)
        val systemDialer = runCatching { app.getSystemService(TelecomManager::class.java).systemDialerPackage }.getOrNull().orEmpty()
        fun q(s: String) = Root.q(s)
        return """
            |#!/system/bin/sh
            |# TosbiSim: give this phone back (the host app's "Stop using this phone as a host").
            |PKG=$PKG
            |ORIG=/data/adb/tosbisim/original-settings.txt
            |LOG=/data/local/tmp/tosbisim-removed.log
            |orig() { grep "^${'$'}1 ${'$'}2 " "${'$'}ORIG" 2>/dev/null | head -1 | cut -d' ' -f3-; }
            |put() { v=${'$'}(orig ${'$'}1 ${'$'}2); [ -z "${'$'}v" ] && v=${'$'}3
            |  if [ "${'$'}v" = null ]; then settings delete ${'$'}1 ${'$'}2; else settings put ${'$'}1 ${'$'}2 "${'$'}v"; fi; }
            |usable() { [ -n "${'$'}1" ] && [ "${'$'}1" != null ] && [ "${'$'}1" != "${'$'}PKG" ] && pm path "${'$'}1" >/dev/null 2>&1; }
            |{
            |echo "${'$'}(date) giving the phone back"
            |# TosbiSim stops first, for good: the app restarted from the module's system copy in the last seconds (pm
            |# uninstall only removes its update) and silenced the phone again before the restart (20 Oct 2026). Without
            |# root and stopped it can't come back before the restart, which removes the module.
            |fail=0
            |pkill -f modules/simrelay-host/service.sh
            |pkill -x simrelayd
            |[ $uid -gt 0 ] && magisk --sqlite "DELETE FROM policies WHERE uid=$uid"
            |dumpsys deviceidle whitelist -${'$'}PKG
            |[ -d /data/adb/modules/simrelay-host ] && { touch /data/adb/modules/simrelay-host/remove || fail=1; }
            |# An update waiting for the restart (Magisk moves modules_update in at boot) would bring the module back
            |rm -rf /data/adb/modules_update/simrelay-host || fail=1
            |pm uninstall ${'$'}PKG
            |am force-stop ${'$'}PKG
            |sleep 1
            |for n in batt_slate_mode:0 input_suspend:0 charging_enabled:1; do
            |  f=/sys/class/power_supply/battery/${'$'}{n%%:*}; [ -e "${'$'}f" ] && echo "${'$'}{n##*:}" > "${'$'}f"
            |done
            |# What service.sh silenced (its snapshot, else Android's defaults)
            |put system vibrate_when_ringing 1
            |put system haptic_feedback_enabled 1
            |put system sound_effects_enabled 1
            |put system lockscreen_sounds_enabled 1
            |put system dtmf_tone 1
            |if [ "${'$'}(getprop ro.product.manufacturer | tr A-Z a-z)" = samsung ]; then
            |  put system call_end_tone 1; put system call_conn_tone 1; put system volume_waiting_tone 1
            |fi
            |# Default apps: the ones from before TosbiSim, else the phone's own
            |sms=${'$'}(orig secure sms_default_application); usable "${'$'}sms" || sms=${q(systemSmsApp(app))}
            |usable "${'$'}sms" || sms=${'$'}(cmd package query-receivers --brief -a android.provider.Telephony.SMS_DELIVER 2>/dev/null \
            |  | grep / | tr -d ' ' | cut -d/ -f1 | grep -v "^${'$'}PKG${'$'}" | head -1)
            |usable "${'$'}sms" && cmd role add-role-holder android.app.role.SMS "${'$'}sms" 0 && echo "SMS app: ${'$'}sms"
            |dialer=${q(boxDialer)}; usable "${'$'}dialer" || dialer=${'$'}(orig secure dialer_default_application)
            |usable "${'$'}dialer" || dialer=${q(systemDialer)}
            |usable "${'$'}dialer" && cmd role add-role-holder android.app.role.DIALER "${'$'}dialer" 0 && echo "phone app: ${'$'}dialer"
            |cmd wifi set-wifi-enabled enabled
            |# Do Not Disturb last, once nothing of TosbiSim runs; Android saves it a moment later, so wait until it reads back
            |case "${'$'}(orig global zen_mode)" in 1) dnd=priority; z=1;; 3) dnd=alarms; z=3;; *) dnd=off; z=0;; esac
            |am force-stop ${'$'}PKG
            |for i in 1 2 3 4 5 6 7 8 9 10; do
            |  [ "${'$'}(settings get global zen_mode)" = "${'$'}z" ] && break
            |  cmd notification set_dnd ${'$'}dnd; sleep 1
            |done
            |[ "${'$'}(settings get global zen_mode)" = "${'$'}z" ] || { echo "Do Not Disturb didn't change"; fail=1; }
            |cmd audio set-ringer-mode NORMAL 2>/dev/null
            |rm -rf /data/adb/tosbisim /data/local/tmp/simrelay-service.log /data/local/tmp/simrelay-service.log.1 || fail=1
            |echo "${'$'}(date) done (failures: ${'$'}fail), restarting"
            |} >> "${'$'}LOG" 2>&1
            |# Gone without a trace when every step worked; else the log stays, to say what didn't (audit, 20 Oct 2026)
            |[ "${'$'}fail" = 0 ] && rm -f "${'$'}LOG"
            |rm -f "${'$'}0"
            |sync; sleep 5                            # Android's own settings files (Do Not Disturb) reach the disk
            |svc power reboot || reboot
            |""".trimMargin()
    }
}
