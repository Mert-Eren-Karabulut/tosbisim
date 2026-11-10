// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Client for simrelayd (abstract socket @simrelayd). Keeps one connection open for the app's
 * lifetime: the daemon restores the mixer if this connection drops mid-call (fail-safe).
 * On every (re)connect it sends the device profile, and re-sends CALL_START if a call is active,
 * so a daemon restart mid-call is repaired automatically. The daemon announces each start with a
 * DAEMON_UP broadcast (DaemonEventReceiver), so we reconnect at once and the silence controls are back
 * within a second. Phones without a voice profile still connect: box mode and the charge limit need root.
 */
object Daemon {
    private const val TAG = "SimRelayHost"
    private val exec = Bg.single("daemon-client", daemon = false)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    // A daemon stuck in one command (it joins audio threads at CALL_END: up to ~10 s) must not hold every later
    // command forever: the read gives up, the request reconnects (CALL_START is re-sent if a call is on).
    private const val READ_TIMEOUT_MS = 20_000
    private var sock: LocalSocket? = null
    private var reader: BufferedReader? = null
    private var out: OutputStream? = null
    @Volatile private var profile: Profile? = null          // read from the call and setup threads too
    @Volatile private var activeSession = 0
    @Volatile private var chargeLimit = 0          // re-sent on reconnect (sysfs resets on reboot only, but cheap)

    @Volatile private var started = false
    private lateinit var appCtx: Context
    /** [start] ran (the host service is up): only then does a failed probe mean the daemon is down. */
    fun isStarted() = started

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        val p = Profiles.forThisDevice(ctx)
        profile = p
        started = true
        Log.i(TAG, "profile: ${p?.id ?: "NONE for this device — Basic mode only"}")
        CallAudioCheck.radio = { radios(appCtx) }
        exec.execute { connect() }
    }

    /** What the phone's radios are doing, in a few words: Bluetooth shares its antenna and its chip with them. */
    private fun radios(c: Context): String {
        val wifi = runCatching { c.getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled }.getOrNull()
        val net = runCatching { c.getSystemService(android.telephony.TelephonyManager::class.java).voiceNetworkType }.getOrNull()
        val g = when (net) {
            null -> "?"
            android.telephony.TelephonyManager.NETWORK_TYPE_GSM, android.telephony.TelephonyManager.NETWORK_TYPE_GPRS,
            android.telephony.TelephonyManager.NETWORK_TYPE_EDGE -> "2g"
            android.telephony.TelephonyManager.NETWORK_TYPE_UMTS, android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA,
            android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA, android.telephony.TelephonyManager.NETWORK_TYPE_HSPA,
            android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP -> "3g"
            android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "lte"
            android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "nr"
            android.telephony.TelephonyManager.NETWORK_TYPE_IWLAN -> "wifi-calling"
            else -> "type$net"
        }
        return "wifi=${wifi?.let { if (it) "on" else "off" } ?: "?"} hotspot=${if (Hotspot.isOn(c)) "on" else "off"} voice=$g ble=${BleLink.lastIntervalMs}ms"
    }

    /** A call is ACTIVE (or still DIALING: [connected] false) on this voice session (1 or 2). Idempotent. */
    fun callActive(session: Int, connected: Boolean = true) = exec.execute {
        if (profile == null) return@execute                              // Basic mode: no audio routes to apply
        // The audio check counts from the answer: a call that only ever dialled has no caller to hear, and was
        // reported as a failed call (audit, 20 Oct 2026)
        if (connected && connectedSince == 0L) connectedSince = android.os.SystemClock.elapsedRealtime()
        if (activeSession == session) return@execute
        activeSession = session
        CallAudioCheck.begin(when { HotspotPause.paused -> "paused"; ::appCtx.isInitialized && Hotspot.isOn(appCtx) -> "on"; else -> "off" })
        request("CALL_START $session")
    }

    /** No active call any more. Idempotent. */
    fun callIdle() = exec.execute {
        if (activeSession == 0) return@execute
        activeSession = 0
        val since = connectedSince
        connectedSince = 0L
        if (since != 0L) {
            val reply = request("AUDIO?")
            val seconds = (android.os.SystemClock.elapsedRealtime() - since) / 1000
            checkCallAudio(reply, seconds)
            reply?.let { runCatching { CallAudioCheck.check(it, seconds) }.onFailure { e -> Log.w(TAG, "call audio check: $e") } }
        }
        request("CALL_END")
    }

    @Volatile private var connectedSince = 0L

    /**
     * A full-voice call whose audio didn't flow one way (nothing captured from the caller, or nothing of the iPhone's
     * played into the call): the failure that matters most on phones we never measured ourselves.
     */
    private fun checkCallAudio(reply: String?, seconds: Long) {
        if (reply == null || seconds < 10 || !VoiceGate.fullVoice) return
        fun num(k: String) = Regex("\\b$k=(-?\\d+)").find(reply)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val cap = num("cap"); val played = num("played"); val voiced = num("voiced")
        // Captured but silent for a whole longer call: the caller's audio never reached us (20 Oct 2026: the far phone's
        // own host had unlinked its mic; a wrong capture route looks the same)
        val silent = cap > 0 && voiced == 0L && seconds >= 30
        if (cap > 0 && played > 0 && !silent) return
        Diag.event("call_audio_failed", org.json.JSONObject().put("what", when { cap == 0L -> "no capture"; played == 0L -> "nothing played"; else -> "caller silent" })
            .put("seconds", seconds).put("daemon", reply))
    }

    fun status() = exec.execute { request("STATUS") }

    fun hasProfile(): Boolean = profile != null
    fun profile(): Profile? = profile
    @Volatile private var autoTried = false
    @Volatile private var fitTried = false
    @Volatile private var inventoryTried = false

    /** Build the automatic profile now (debug: on a phone that has a shipped one too) and log it. */
    fun tryAutoProfile(done: (Profile?) -> Unit) = exec.execute {
        connect()
        val p = AutoProfile.build { n -> runCatching { send("HAS $n") }.getOrNull() }
        main.post { done(p) }
    }

    /**
     * The daemon (re)started: reconnect now so silence controls and the charge limit are re-applied. Without a profile,
     * the new daemon gets its own auto profile try: after an app update the first try met the old build, whose HAS
     * didn't list an enum's options yet (2 Nov 2026), and the route stayed off until the next reboot.
     */
    fun reconnect() = exec.execute { if (started) { if (profile == null) autoTried = false; close(); request("STATUS") } }

    fun screenOff() = exec.execute { request("SCREEN_OFF") }
    fun powerKey(block: Boolean) = exec.execute { request("POWERKEY ${if (block) 1 else 0}") }
    fun netblock(uid: Int, on: Boolean) = exec.execute { request("NETBLOCK $uid ${if (on) 1 else 0}") }
    fun wake() = exec.execute { request("WAKE") }
    fun role(role: String, pkg: String) = exec.execute { request("ROLE $role $pkg") }

    /** Charge cap. Box hears the daemon's answer, here and after every reconnect ([chargeAnswer]). */
    fun charge(percent: Int) = exec.execute {
        chargeLimit = percent
        chargeAnswer(request("CHARGE $percent"))
    }

    /** The daemon's answer is the one truth on the cap; no answer (daemon not up yet, at boot) tells nothing: the
     *  reconnect sends it again. Read as "unsupported", it showed so until the next app restart (audit, 1 Nov 2026). */
    private fun chargeAnswer(reply: String?) {
        if (reply == null) return
        val ok = reply.startsWith("OK")
        main.post { Box.chargeAnswered(ok) }
    }

    private val probing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Is the daemon there? One HELLO, no retries, at most one in flight: the setup screen asks every 2 s and must not
     * queue probes (with their retry sleeps) ahead of CALL_START and box commands while the daemon is down.
     */
    fun probe(done: (String?) -> Unit) {
        if (!probing.compareAndSet(false, true)) return
        exec.execute {
            val r = runCatching { if (connect()) send("HELLO") else null }.getOrNull()
            probing.set(false)
            main.post { done(r) }
        }
    }

    /** One request whose reply the caller needs (e.g. IFSTATS); [done] runs on main, never on the daemon thread. */
    fun query(line: String, done: (String?) -> Unit) = exec.execute { val r = request(line); main.post { done(r) } }

    private fun connect(): Boolean {
        if (sock != null) return true
        if (!started) return false
        val p = profile
        return try {
            val s = LocalSocket()
            s.connect(LocalSocketAddress("simrelayd", LocalSocketAddress.Namespace.ABSTRACT))
            s.soTimeout = READ_TIMEOUT_MS
            sock = s
            reader = BufferedReader(InputStreamReader(s.inputStream))
            out = s.outputStream
            send("HELLO")
            // Safe mode: the daemon found that the last boot ended while it was writing audio controls (a kernel crash
            // from a wrong control, most likely). It writes none this boot; we want to know which phone and profile.
            if (send("STATUS")?.contains("safe=1") == true)
                Diag.event("daemon_safe_mode", org.json.JSONObject().put("what", "crash while writing").put("profile", p?.id ?: ""))
            // A shipped profile on this card, once: its PCMs by name, and one matched by family (another phone's
            // measurements) only if this card has every control it drives; else this phone's own audio files decide
            // (31 Oct 2026). A phone's own measured profile is kept as it is, with a note in the log.
            if (p != null && !fitTried) {
                fitTried = true
                val (fit, why) = p.onThisCard({ n -> send("HAS $n")?.startsWith("OK") == true }, AsoundPcm.byName())
                when {
                    fit != null -> profile = fit
                    p.source == "family" -> {
                        profile = null
                        Log.w(TAG, "profile ${p.id} doesn't fit this phone ($why): trying its own audio files")
                        Diag.event("family_profile", org.json.JSONObject().put("what", "rejected").put("profile", p.id).put("why", why))
                    }
                    else -> Log.w(TAG, "profile ${p.id}: $why (kept: this phone's own profile)")
                }
            }
            // No shipped profile (or none that fits): try one from the phone's own audio files once (the call test still decides).
            if (profile == null && !autoTried) {
                autoTried = true
                profile = AutoProfile.build { n -> send("HAS $n") }
                Log.i(TAG, "profile: ${profile?.let { "${it.id} (auto)" } ?: "none, Basic mode"}")
                if (profile != null) Relay.voiceChanged()          // the phone's Host tab offers the call test now
                // What the auto profile found (or why not) is the one thing we can't learn from firmware dumps
                // Once per firmware, app version and outcome: every start sent it again, 42 of the first 320 reports
                Diag.event("auto_profile", org.json.JSONObject().put("what", if (profile != null) "built" else "none")
                    .put("profile", profile?.json ?: org.json.JSONObject.NULL).put("why", AutoProfile.why).put("audio", Discovery.audio()),
                    onceFor = "${android.os.Build.FINGERPRINT}|${BuildConfigInfo.version(appCtx)}|${profile?.id}")
            }
            profile?.daemonSetLines()?.forEach { send(it) }
            if (Box.blocksPowerKey) send("POWERKEY 1")      // a restarted daemon has let go of the power key
            if (chargeLimit > 0) chargeAnswer(send("CHARGE $chargeLimit"))
            // Once per firmware, never during a call (not even a ringing one: the daemon is busy for seconds): the sound
            // card as the kernel describes it (read-only). One try per process: a daemon that fails or dies on it must
            // not get it again at every reconnect (audit, 20 Oct 2026).
            if (activeSession == 0 && !CallRegistry.anyCall() && !inventoryTried && Inventory.due()) {
                inventoryTried = true
                send("INVENTORY")?.takeIf { it.startsWith("OK") }?.let { Inventory.send(it) }
                    ?: Log.w(TAG, "inventory: the daemon didn't give it; not again until the app restarts")
            }
            if (activeSession != 0) send("CALL_START $activeSession")
            true
        } catch (e: Exception) {
            Log.w(TAG, "daemon connect failed: $e")
            close()
            false
        }
    }

    private fun send(line: String): String? {
        val o = out ?: return null
        o.write((line + "\n").toByteArray())
        o.flush()
        val reply = reader?.readLine()
        Log.i(TAG, "daemon <- $line -> $reply")
        return reply
    }

    private fun request(line: String): String? {
        repeat(3) { attempt ->
            if (connect()) {
                try {
                    return send(line)
                } catch (e: Exception) {
                    Log.w(TAG, "daemon request failed: $e")
                    close()
                }
            }
            Thread.sleep(500L * (attempt + 1))
        }
        Log.e(TAG, "daemon unreachable, dropped: $line")
        Diag.event("daemon_unreachable", org.json.JSONObject().put("what", line.substringBefore(' ')))
        return null
    }

    private fun close() {
        runCatching { sock?.close() }
        sock = null; reader = null; out = null
    }
}
