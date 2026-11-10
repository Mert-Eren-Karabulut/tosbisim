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
 * once every [REPEAT_MS]. The hardware inventory's parts ("dump", once per firmware) don't count towards the day.
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
     * last lines of the host log (scrubbed). [onceFor]: what this report is about (a firmware, a version); the same
     * again isn't sent. Never throws, never blocks the caller.
     */
    fun event(name: String, detail: JSONObject = JSONObject(), withLog: Boolean = true, passed: Boolean? = null,
              kind: String = "event", force: Boolean = false, onceFor: String? = null) = exec.execute {
        if (!enabled) return@execute
        val key = "$name:${detail.optString("what")}"
        val p = prefs()
        val now = System.currentTimeMillis()
        if (onceFor != null && p.getString("once_$key", null) == onceFor) return@execute
        if (!force && kind == "event" && now - p.getLong("last_$key", 0) < REPEAT_MS) return@execute   // force: the user asked
        val day = now / 86_400_000L
        val sentToday = if (p.getLong("day", -1) == day) p.getInt("today", 0) else 0
        // An inventory's parts belong together: a cap reached half-way would leave a dump that never decodes (audit,
        // 20 Oct 2026). They come once per firmware, so they don't count.
        val dump = kind == "dump"
        if (!dump && sentToday >= PER_DAY) return@execute
        p.edit().putLong("last_$key", now).putLong("day", day).putInt("today", sentToday + if (dump) 0 else 1)
            .also { e -> if (onceFor != null) e.putString("once_$key", onceFor) }.apply()
        val body = facts(kind).put("payload", JSONObject()
            .put("event", name).put("detail", detail)
            .put("android", Build.VERSION.RELEASE).put("root", Compat.rootKind(app).id)
            .apply { if (withLog) put("log", recentLog(LOG_BYTES)) })
        passed?.let { body.put("passed", it) }
        File(dir(), "q-$now-${name.take(30)}.json").writeText(body.toString())
        flush()
    }

    /** This app's version, for "once per version" reports. */
    fun version(): String = BuildConfigInfo.version(app)

    /**
     * An onboarding step reached for the first time on this install ("setup_opened", "root_ok", "module_installed",
     * "host_running", "paired", "full_voice"): the funnel shows where people get stuck, per phone model.
     */
    fun milestone(name: String, detail: JSONObject = JSONObject()) {
        if (prefs().getBoolean("milestone_$name", false)) return
        prefs().edit().putBoolean("milestone_$name", true).apply()
        event("milestone", detail.put("what", name), withLog = false)
    }

    /**
     * The phone's identity for a report: model and firmware facts, never ids of the phone or the user. "at": when the
     * report was made (epoch seconds). A host without internet sends its queue days later, and the server only knew
     * when each report arrived: a week of a Galaxy J7 Prime's reports all read the same minute (9 Nov 2026).
     */
    fun facts(kind: String): JSONObject {
        val p = Daemon.profile()
        return JSONObject()
            .put("kind", kind).put("app", "host").put("app_version", BuildConfigInfo.version(app))
            .put("at", System.currentTimeMillis() / 1000)
            .put("install_id", Api.installId(app))
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("device", Build.DEVICE)
            .put("soc", Compat.soc()).put("sdk", Build.VERSION.SDK_INT).put("firmware", Build.FINGERPRINT)
            .put("tier", runCatching { Compat.verdict(app).tier.name.lowercase() }.getOrDefault(""))
            .put("profile_id", p?.id ?: JSONObject.NULL).put("profile_source", p?.source ?: JSONObject.NULL)
    }

    /**
     * The host log's last [bytes], scrubbed and without its noise ([LogTrim]): the persistent host log with root, else
     * this app's own logcat. Read [LOG_READ] so the excerpt still reaches back far enough once the noise is out.
     */
    fun recentLog(bytes: Int, fast: Boolean = false): String {
        val raw = Root.sh("tail -c $LOG_READ /data/adb/tosbisim/logs/host.log", if (fast) 4_000L else 10_000L).takeIf { it.ok && it.out.length > 200 }?.out
            ?.let { if (Regex("""^\d\d-\d\d \d\d:""").containsMatchIn(it.take(9))) it else it.substring(it.indexOf('\n') + 1) }   // tail -c starts mid-line
            ?.let { it.substring(0, it.lastIndexOf('\n') + 1) }   // the file ends mid-line (4 KiB blocks): logcat has it whole
            ?.let { it + newerThan(it, if (fast) 6_000L else 10_000L) }
            ?: runCatching {
                val p = ProcessBuilder("logcat", "-d", "-t", "2000", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText().also { p.waitFor() }
            }.getOrDefault("")
        return LogTrim.trim(Scrub.text(raw), bytes)
    }
    // Far enough back for a whole call: its end alone is 15 KB of Telecom's lines, which LogTrim then leaves out
    private const val LOG_READ = 256 * 1024

    /**
     * The lines logcat holds after the file's last one: it writes the file in whole 4 KiB blocks, so a quiet host's file
     * trails by up to 25 minutes, the very ones a problem report is about (audit, 1 Nov 2026). Same buffers and tags as
     * the capture (box.c log_spawn).
     */
    private fun newerThan(file: String, timeoutMs: Long): String {
        val last = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}""", RegexOption.MULTILINE).findAll(file).lastOrNull()?.value ?: return ""
        val r = Root.sh("logcat -d -b main,system,crash -v time -T ${Root.q(last)} -s SimRelayHost:I simrelayd:I voice:D " +
            "Telecom:I SemWifiApTimeOutImpl:I Tethering:W AndroidRuntime:E", timeoutMs)
        if (!r.ok) return ""
        val newer = r.out.lines().filter { it.length > 18 && it.substring(0, 18) > last }
        return if (newer.isEmpty()) "" else (if (file.endsWith("\n")) "" else "\n") + newer.joinToString("\n") + "\n"
    }

    /** Our own test emulators (tools/rig/emulator.sh) must not count as people's phones; adb can allow them. */
    private val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("sdk_gphone")

    /** What the server can never take: a 4xx other than "timeout" (408) and "too many" (429). Anything else, and no
     *  answer at all, may work later. */
    private fun refused(code: Int) = code in 400..499 && code != 408 && code != 429

    /**
     * Send what's queued (oldest first); kept on failure for the next try. A report the server refuses is dropped:
     * kept, it blocked every report behind it for ever (audit, 20 Oct 2026). With diagnostics off only the user's own
     * "Send a report now" leaves the phone.
     */
    private fun flush() {
        if (emulator && !prefs().getBoolean("emulator_ok", false)) { dir().listFiles()?.forEach { it.delete() }; return }
        val queued = dir().listFiles()?.filter { it.name.startsWith("q-") }?.sortedBy { it.name }.orEmpty()
        val on = enabled
        for (f in queued) {
            if (!on && !f.name.endsWith(USER_REPORT)) { f.delete(); continue }
            val body = runCatching { JSONObject(f.readText()) }.getOrNull()
            if (body == null) { f.delete(); continue }
            val code = Api.postStatus("/reports", body).first
            if (code in 200..299) { f.delete(); continue }
            if (!refused(code)) return                                      // offline: try again on the next event
            Log.w(TAG, "diag: ${f.name} refused by the server (HTTP $code), dropped")
            f.delete()
        }
    }

    private const val USER_REPORT = "-user_report.json"

    /** The user's "Send a report now", for the screen: process-level, so a rotation keeps it. */
    enum class Report { IDLE, SENDING, SENT, QUEUED, REFUSED }
    @Volatile var report = Report.IDLE; private set

    /**
     * "Send a report now": the user asked, so it goes even with diagnostics off and past the daily cap, and the screen
     * says honestly whether it went (it claimed "sent" while diagnostics were off and nothing left the phone; audit,
     * 20 Oct 2026). Offline: queued, it goes out with the next event or connectivity. [done] runs on the diag thread.
     */
    fun userReport(detail: JSONObject, done: () -> Unit = {}) {
        if (report == Report.SENDING) return
        report = Report.SENDING
        exec.execute {
            try {
                if (emulator && !prefs().getBoolean("emulator_ok", false)) { report = Report.REFUSED; return@execute }
                val now = System.currentTimeMillis()
                val body = facts("event").put("payload", JSONObject()
                    .put("event", "user_report").put("detail", detail)
                    .put("android", Build.VERSION.RELEASE).put("root", Compat.rootKind(app).id).put("log", recentLog(LOG_BYTES)))
                val code = Api.postStatus("/reports", body).first
                report = when {
                    code in 200..299 -> Report.SENT
                    refused(code) -> Report.REFUSED.also { Log.w(TAG, "diag: user report refused (HTTP $code)") }
                    else -> { File(dir(), "q-$now$USER_REPORT").writeText(body.toString()); Report.QUEUED }
                }
                if (report == Report.SENT) flush()
            } finally {
                if (report == Report.SENDING) report = Report.REFUSED          // something threw: let them try again
                done()
            }
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

/**
 * A report's log excerpt without what crowded out our own lines. Nothing is dropped silently: a left-out stretch
 * becomes one "…" line with its count and times.
 *
 * - Telecom keeps its call events ("Event: RecordEntry TC@1: SET_ACTIVE", audio-route events) and its warnings. Its
 *   other lines went first on custom ROMs (phone-account bookkeeping several times a second while a SIM flickers, a
 *   J7 Prime report, 5 Nov 2026), then everywhere: a call's end is 15 KB of them in half a second, so the excerpt of
 *   a 5-minute call with a 3 s stall, and of a problem report about choppy audio, held the call's last half second
 *   and none of ours (a Redmi Note 9 Pro and a Galaxy A13, 9-10 Nov 2026).
 * - The daemon's "audio 5s" line stays when something was off in those 5 s; a run of clean ones is its first line
 *   and a count, so a whole call fits.
 * - Identical lines in a row are folded into their first (the setup screen's daemon check repeats every 2 s).
 * - Other tags' lines are cut at [LONG] characters.
 * Our own lines, other tags, and lines that aren't logcat lines (stack traces) always stay.
 */
object LogTrim {
    private val TIME = Regex("""^\d\d-\d\d (\d\d:\d\d:\d\d)\.\d{3}""")
    /** "-v time" ("I/Tag( 123): msg") or threadtime (" 123  456 I Tag: msg"): the level, the tag and the message. */
    private val LINE = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+(?:([VDIWEF])/([^(]*?)\s*\(\s*\d+\)|\d+\s+\d+\s+([VDIWEF])\s+(\S[^:]*?)\s*):\s?(.*)$""")
    /** Telecom keeping its phone-account registry, never about a call's course (its warnings included). */
    private val NOISE = Regex("""^(PhoneAccountRegistrar: |CallsManager: (handlePhoneAccountChanged|Sending phone-account|updateEmergencyCallNotificationAsync)|(TelecomServiceImpl\$1)?: (registerPhoneAccount|unregisterPhoneAccount|isInEmergencyCall)|AppLabelProxy: )""")
    /** Telecom's own record of a call's course: one short line per event. */
    private val CALL_EVENT = Regex("""^(Event: RecordEntry |Logging\.Events: )""")
    /** The daemon's 5 s line with nothing off: no drop, loss, late or concealed frame, error or reopen, at real time. */
    private val AUDIO_5S = Regex("""^audio 5s: sent=(\d+) txdrop=0 recv=(\d+) fec=0 plc=0 late=0 catchup=0 caperr=0 .*\((\d+)\.\d fps\).* reopen=0\b""")
    private val OURS = setOf("SimRelayHost", "simrelayd")
    private const val LONG = 300

    private fun steady(msg: String): Boolean {
        val m = AUDIO_5S.find(msg) ?: return false
        val (sent, recv, fps) = m.destructured
        return sent.toInt() in 490..510 && recv.toInt() in 490..510 && fps.toInt() in 99..100
    }

    /** [log] without the noise, its newest [keep] characters, starting at a whole line. */
    fun trim(log: String, keep: Int): String {
        val out = StringBuilder()
        var dropped = 0; var dropFirst = ""; var dropLast = ""
        var lastKey: String? = null; var repeats = 0; var repeatLast = ""; var alike = false
        fun endRepeats() {
            if (repeats > 0) out.append(if (alike) "… $repeats more clean 'audio 5s' lines, last at $repeatLast\n" else "… the line above $repeats more times, last at $repeatLast\n")
            repeats = 0
        }
        fun endDropped() {
            if (dropped > 0) out.append("… ").append(dropped).append(" Telecom lines left out (")
                .append(dropFirst).append(if (dropLast != dropFirst) "–$dropLast" else "").append(")\n")
            dropped = 0
        }
        for (raw in log.split('\n')) {
            if (raw.isEmpty()) continue
            var line = raw
            val m = LINE.find(line)
            val time = TIME.find(line)?.groupValues?.get(1).orEmpty()
            if (m != null) {
                val level = m.groupValues[1].ifEmpty { m.groupValues[3] }
                val tag = m.groupValues[2].ifEmpty { m.groupValues[4] }.trim()
                val msg = m.groupValues[5]
                if (tag == "Telecom" && (NOISE.containsMatchIn(msg) || (level !in "WEF" && !CALL_EVENT.containsMatchIn(msg)))) {
                    if (dropped == 0) dropFirst = time
                    dropped++; dropLast = time; continue
                }
                val clean = tag == "simrelayd" && steady(msg)
                val key = if (clean) "simrelayd: audio 5s, clean" else "$tag: $msg"
                if (key == lastKey) { repeats++; repeatLast = time; continue }
                endRepeats(); endDropped()
                lastKey = key; alike = clean
                if (tag !in OURS && line.length > LONG) line = line.substring(0, LONG) + "…"
            } else { endRepeats(); endDropped(); lastKey = null }
            out.append(line).append('\n')
        }
        endRepeats(); endDropped()
        if (out.length <= keep) return out.toString()
        val tail = out.substring(out.length - keep)
        return tail.substring(tail.indexOf('\n') + 1)             // the first line was cut: start at the next one
    }
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
