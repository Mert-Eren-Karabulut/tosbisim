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
 * In order: the iPhone is told (an Unpair it didn't ask for) and forgotten; box mode off (it restores its own saved
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

    /** Start; [progress] gets each step's text on main. The phone restarts at the end. */
    fun start(ctx: Context, progress: (String) -> Unit) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        Log.i(TAG, "RETIRE: giving the phone back and removing TosbiSim")
        Diag.event("host_removed", JSONObject().put("what", "user"), withLog = false, force = true)
        exec.execute { run(app) { s -> main.post { progress(s) } } }
    }

    private fun run(app: Context, progress: (String) -> Unit) {
        progress(app.getString(R.string.retire_step_iphone))
        await { done -> Relay.retire(done) }

        progress(app.getString(R.string.retire_step_settings))
        // The phone app from before TosbiSim: remembered when the host took the role, else box mode's saved one (read
        // before its restore drops it)
        val boxDialer = prefs(app).getString("dialer", null) ?: runCatching {
            JSONObject(app.getSharedPreferences("box", Context.MODE_PRIVATE).getString("orig", "{}")!!).optString("dialer")
        }.getOrDefault("")
        await { done -> main.post { if (Box.on) Box.set(false); done() } }
        if (Hotspot.isOn(app)) await { done -> Hotspot.set(app, false) { _, _ -> done() } }
        restoreSound(app)
        await { done -> Daemon.query("STATUS") { done() } }   // box mode's daemon commands have run
        Thread.sleep(1500)                                      // the diagnostics event on its way

        progress(app.getString(R.string.retire_step_restart))
        val script = File(app.cacheDir, "tosbisim-remove.sh")
        script.writeText(script(app, boxDialer))
        val r = Root.sh("cp ${Root.q(script.path)} /data/local/tmp/tosbisim-remove.sh && " +
            "setsid sh /data/local/tmp/tosbisim-remove.sh </dev/null >/dev/null 2>&1 &", 15_000)
        Log.i(TAG, "RETIRE: removal script started (${r.code})")
        if (!r.ok) { running = false; progress(app.getString(R.string.retire_failed)) }
    }

    private fun await(timeoutS: Long = 10, start: (() -> Unit) -> Unit) {
        val latch = CountDownLatch(1)
        runCatching { start { latch.countDown() } }.onFailure { Log.w(TAG, "retire: $it"); latch.countDown() }
        if (!latch.await(timeoutS, TimeUnit.SECONDS)) Log.w(TAG, "retire: a step timed out, going on")
    }

    /** Volumes as they were before TosbiSim (a silent snapshot, from a phone set up before snapshots, gives 2/3). */
    private fun restoreSound(app: Context) {
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val saved = runCatching { JSONObject(prefs(app).getString("volumes", "{}")!!) }.getOrDefault(JSONObject())
        for (s in STREAMS) runCatching {
            val min = am.getStreamMinVolume(s)
            val v = saved.optInt("$s", -1).takeIf { it > min } ?: (am.getStreamMaxVolume(s) * 2 / 3)
            am.setStreamVolume(s, v, 0)
        }.onFailure { Log.w(TAG, "retire: volume $s: $it") }
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
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
            |# What service.sh silenced (its snapshot, else Android's defaults)
            |put system vibrate_when_ringing 1
            |put system haptic_feedback_enabled 1
            |put system sound_effects_enabled 1
            |put system lockscreen_sounds_enabled 1
            |put system dtmf_tone 1
            |if [ "${'$'}(getprop ro.product.manufacturer | tr A-Z a-z)" = samsung ]; then
            |  put system call_end_tone 1; put system call_conn_tone 1; put system volume_waiting_tone 1
            |fi
            |case "${'$'}(orig global zen_mode)" in 1) cmd notification set_dnd priority;; 3) cmd notification set_dnd alarms;; *) cmd notification set_dnd off;; esac
            |# Default apps: the ones from before TosbiSim, else the phone's own
            |sms=${'$'}(orig secure sms_default_application); usable "${'$'}sms" || sms=${q(systemSmsApp(app))}
            |usable "${'$'}sms" || sms=${'$'}(cmd package query-receivers --brief -a android.provider.Telephony.SMS_DELIVER 2>/dev/null \
            |  | grep / | tr -d ' ' | cut -d/ -f1 | grep -v "^${'$'}PKG${'$'}" | head -1)
            |usable "${'$'}sms" && cmd role add-role-holder android.app.role.SMS "${'$'}sms" 0 && echo "SMS app: ${'$'}sms"
            |dialer=${q(boxDialer)}; usable "${'$'}dialer" || dialer=${'$'}(orig secure dialer_default_application)
            |usable "${'$'}dialer" || dialer=${q(systemDialer)}
            |usable "${'$'}dialer" && cmd role add-role-holder android.app.role.DIALER "${'$'}dialer" 0 && echo "phone app: ${'$'}dialer"
            |cmd wifi set-wifi-enabled enabled
            |# Root, the battery exemption, the module (gone at the restart), the daemon and its charge cut
            |dumpsys deviceidle whitelist -${'$'}PKG
            |[ $uid -gt 0 ] && magisk --sqlite "DELETE FROM policies WHERE uid=$uid"
            |[ -d /data/adb/modules/simrelay-host ] && touch /data/adb/modules/simrelay-host/remove
            |pkill -f modules/simrelay-host/service.sh
            |pkill -x simrelayd; sleep 1
            |for n in batt_slate_mode:0 input_suspend:0 charging_enabled:1; do
            |  f=/sys/class/power_supply/battery/${'$'}{n%%:*}; [ -e "${'$'}f" ] && echo "${'$'}{n##*:}" > "${'$'}f"
            |done
            |pm uninstall ${'$'}PKG
            |rm -rf /data/adb/tosbisim /data/local/tmp/simrelay-service.log /data/local/tmp/simrelay-service.log.1
            |echo "${'$'}(date) done, restarting"
            |} >> "${'$'}LOG" 2>&1
            |# Gone without a trace: every step above has run, so this script and its log go too
            |rm -f "${'$'}LOG" "${'$'}0"
            |sleep 2
            |svc power reboot || reboot
            |""".trimMargin()
    }
}
