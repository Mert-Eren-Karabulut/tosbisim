// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Anonymous diagnostics: what helps us tell "expected to work" from "proven" on phones we never touched, and fix what
 * breaks on them. Events (onboarding milestones, failures, crashes, call-test results) go to api.tosbisim.com/reports
 * with the phone's model/firmware facts and a scrubbed excerpt of the host log ([Scrub]: no phone numbers, hardware
 * or IP addresses, ids, network names or message text ever leave the phone). On by default, explained on the setup
 * screen, one switch to turn it off. Queued on disk while offline; at most [PER_DAY] a day, the same event at most
 * once every [REPEAT_MS].
 */
object Diag {
    private const val TAG = "SimRelayHost"
    private const val PER_DAY = 30
    private const val REPEAT_MS = 6 * 3600_000L
    private const val LOG_BYTES = 12_000
    private val exec: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "diag").apply { isDaemon = true } }
        .let { ex -> java.util.concurrent.Executor { r -> ex.execute { try { r.run() } catch (t: Throwable) { Log.e(TAG, "diag: $t") } } } }
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // Synchronously to disk: the process is about to die. Sent at the next start.
            runCatching { File(dir(), "crash.txt").writeText("${t.name}: ${Log.getStackTraceString(e)}".take(8000)) }
            previous?.uncaughtException(t, e)
        }
        exec.execute {
            File(dir(), "crash.txt").takeIf { it.exists() }?.let { f ->
                event("crash", JSONObject().put("trace", Scrub.text(f.readText())), withLog = false)
                f.delete()
            }
            flush()
        }
    }

    var enabled: Boolean
        get() = prefs().getBoolean("enabled", true)
        set(v) { prefs().edit().putBoolean("enabled", v).apply(); Log.i(TAG, "diagnostics ${if (v) "on" else "off"}") }

    /**
     * Report [name] (e.g. "module_install_failed", "auto_profile", "milestone") with [detail]. [withLog]: attach the
     * last lines of the host log (scrubbed). Never throws, never blocks the caller.
     */
    fun event(name: String, detail: JSONObject = JSONObject(), withLog: Boolean = true, passed: Boolean? = null,
              kind: String = "event", force: Boolean = false) = exec.execute {
        if (!enabled) return@execute
        val key = "$name:${detail.optString("what")}"
        val p = prefs()
        val now = System.currentTimeMillis()
        if (!force && kind == "event" && now - p.getLong("last_$key", 0) < REPEAT_MS) return@execute   // force: the user asked
        val day = now / 86_400_000L
        val sentToday = if (p.getLong("day", -1) == day) p.getInt("today", 0) else 0
        if (sentToday >= PER_DAY) return@execute
        p.edit().putLong("last_$key", now).putLong("day", day).putInt("today", sentToday + 1).apply()
        val body = facts(kind).put("payload", JSONObject()
            .put("event", name).put("detail", detail)
            .put("android", Build.VERSION.RELEASE)
            .apply { if (withLog) put("log", recentLog(LOG_BYTES)) })
        passed?.let { body.put("passed", it) }
        File(dir(), "q-$now-${name.take(30)}.json").writeText(body.toString())
        flush()
    }

    /**
     * An onboarding step reached for the first time on this install ("setup_opened", "root_ok", "module_installed",
     * "host_running", "paired", "full_voice"): the funnel shows where people get stuck, per phone model.
     */
    fun milestone(name: String, detail: JSONObject = JSONObject()) {
        if (prefs().getBoolean("milestone_$name", false)) return
        prefs().edit().putBoolean("milestone_$name", true).apply()
        event("milestone", detail.put("what", name), withLog = false)
    }

    /** The phone's identity for a report: model and firmware facts, never ids of the phone or the user. */
    fun facts(kind: String): JSONObject {
        val p = Daemon.profile()
        return JSONObject()
            .put("kind", kind).put("app", "host").put("app_version", BuildConfigInfo.version(app))
            .put("install_id", Api.installId(app))
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("device", Build.DEVICE)
            .put("soc", Compat.soc()).put("sdk", Build.VERSION.SDK_INT).put("firmware", Build.FINGERPRINT)
            .put("tier", runCatching { Compat.verdict(app).tier.name.lowercase() }.getOrDefault(""))
            .put("profile_id", p?.id ?: JSONObject.NULL).put("profile_source", p?.source ?: JSONObject.NULL)
    }

    /** The host log's last [bytes], scrubbed: the persistent host log with root, else this app's own logcat. */
    fun recentLog(bytes: Int): String {
        val raw = Root.sh("tail -c $bytes /data/adb/tosbisim/logs/host.log", 10_000).takeIf { it.ok && it.out.length > 200 }?.out
            ?: runCatching {
                val p = ProcessBuilder("logcat", "-d", "-t", "600", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText().also { p.waitFor() }
            }.getOrDefault("")
        return Scrub.text(raw.takeLast(bytes))
    }

    /** Send what's queued (oldest first); kept on failure for the next try. */
    /** Our own test emulators (tools/rig/emulator.sh) must not count as people's phones; adb can allow them. */
    private val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("sdk_gphone")

    private fun flush() {
        if (emulator && !prefs().getBoolean("emulator_ok", false)) { dir().listFiles()?.forEach { it.delete() }; return }
        if (!enabled) { dir().listFiles()?.filter { it.name.startsWith("q-") }?.forEach { it.delete() }; return }
        for (f in dir().listFiles()?.filter { it.name.startsWith("q-") }?.sortedBy { it.name }.orEmpty()) {
            val body = runCatching { JSONObject(f.readText()) }.getOrNull()
            if (body == null) { f.delete(); continue }
            if (Api.postJson("/reports", body) == null) return               // offline: try again on the next event
            f.delete()
        }
    }

    /** Retry the queue (connectivity came back). */
    fun retry() = exec.execute { flush() }

    private fun dir() = File(app.filesDir, "diag").apply { mkdirs() }
    private fun prefs() = app.getSharedPreferences("diag", Context.MODE_PRIVATE)
}

/**
 * Removes what could identify a person from text that leaves the phone: phone numbers, Bluetooth/Wi-Fi hardware
 * addresses, IP addresses, e-mail addresses, UUID-like ids, quoted strings (network names) and number= fields.
 * Technical content (control names, PCM numbers, timings, model names) stays.
 */
object Scrub {
    private val rules = listOf(
        Regex("""(?i)\b(number|from|to|ssid|name|email)=\S+""") to "$1=<…>",
        Regex("""\b[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}\b""") to "<mac>",
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""") to "<id>",
        Regex("""\b[\w.+-]+@[\w-]+\.[\w.]+\b""") to "<email>",
        Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""") to "<ip>",
        // 9+ digits, single spaces between groups only ("+44 7700 900123"); log pid/tid columns have two spaces
        Regex("""(?<![\w.:-])\+?\d(?:\d| (?=\d)){7,17}\d(?![\w.])""") to "<num>",
        Regex("\"[^\"\\n]{1,64}\"") to "\"<…>\"",
    )

    fun text(s: String): String = rules.fold(s) { acc, (re, to) -> re.replace(acc, to) }
}

/** [s] cut into pieces of at most [max] UTF-8 bytes (a frame limit is in bytes; "…" alone is 3). */
fun utf8Chunks(s: String, max: Int): List<String> {
    val out = mutableListOf<String>()
    var start = 0; var bytes = 0; var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i); val n = Character.charCount(cp)
        val b = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
        if (bytes + b > max) { out += s.substring(start, i); start = i; bytes = 0 }
        bytes += b; i += n
    }
    if (start < s.length) out += s.substring(start)
    return out
}
