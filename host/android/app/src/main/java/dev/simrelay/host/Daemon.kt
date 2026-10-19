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
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "daemon-client") }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    // A daemon stuck in one command (it joins audio threads at CALL_END: up to ~10 s) must not hold every later
    // command forever: the read gives up, the request reconnects (CALL_START is re-sent if a call is on).
    private const val READ_TIMEOUT_MS = 20_000
    private var sock: LocalSocket? = null
    private var reader: BufferedReader? = null
    private var out: OutputStream? = null
    private var profile: Profile? = null
    @Volatile private var activeSession = 0
    @Volatile private var chargeLimit = 0          // re-sent on reconnect (sysfs resets on reboot only, but cheap)

    @Volatile private var started = false
    /** [start] ran (the host service is up): only then does a failed probe mean the daemon is down. */
    fun isStarted() = started

    fun start(ctx: Context) {
        val p = Profiles.forThisDevice(ctx)
        profile = p
        started = true
        Log.i(TAG, "profile: ${p?.id ?: "NONE for this device — Basic mode only"}")
        exec.execute { connect() }
    }

    /** A call is ACTIVE on this voice session (1 or 2). Idempotent. */
    fun callActive(session: Int) = exec.execute {
        if (profile == null || activeSession == session) return@execute   // Basic mode: no audio routes to apply
        if (activeSession == 0) callSince = android.os.SystemClock.elapsedRealtime()
        activeSession = session
        request("CALL_START $session")
    }

    /** No active call any more. Idempotent. */
    fun callIdle() = exec.execute {
        if (activeSession == 0) return@execute
        activeSession = 0
        checkCallAudio(request("AUDIO?"), (android.os.SystemClock.elapsedRealtime() - callSince) / 1000)
        request("CALL_END")
    }

    @Volatile private var callSince = 0L

    /**
     * A full-voice call whose audio didn't flow one way (nothing captured from the caller, or nothing of the iPhone's
     * played into the call): the failure that matters most on phones we never measured ourselves.
     */
    private fun checkCallAudio(reply: String?, seconds: Long) {
        if (reply == null || seconds < 10 || !VoiceGate.fullVoice) return
        fun num(k: String) = Regex("\\b$k=(-?\\d+)").find(reply)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val cap = num("cap"); val played = num("played")
        if (cap > 0 && played > 0) return
        Diag.event("call_audio_failed", org.json.JSONObject().put("what", if (cap == 0L) "no capture" else "nothing played")
            .put("seconds", seconds).put("daemon", reply))
    }

    fun status() = exec.execute { request("STATUS") }

    fun hasProfile(): Boolean = profile != null
    fun profile(): Profile? = profile
    @Volatile private var autoTried = false

    /** Build the automatic profile now (debug: on a phone that has a shipped one too) and log it. */
    fun tryAutoProfile(done: (Profile?) -> Unit) = exec.execute {
        connect()
        val p = AutoProfile.build { n -> runCatching { send("HAS $n") }.getOrNull()?.startsWith("OK") == true }
        main.post { done(p) }
    }

    /** The daemon (re)started: reconnect now so silence controls and the charge limit are re-applied. */
    fun reconnect() = exec.execute { if (started) { close(); request("STATUS") } }

    fun screenOff() = exec.execute { request("SCREEN_OFF") }
    fun powerKey(block: Boolean) = exec.execute { request("POWERKEY ${if (block) 1 else 0}") }
    fun netblock(uid: Int, on: Boolean) = exec.execute { request("NETBLOCK $uid ${if (on) 1 else 0}") }
    fun wake() = exec.execute { request("WAKE") }
    fun role(role: String, pkg: String) = exec.execute { request("ROLE $role $pkg") }

    /** Charge cap; [done] (on main) gets true if applied, false if this phone has no cap node or it failed. */
    fun charge(percent: Int, done: (Boolean) -> Unit = {}) = exec.execute {
        chargeLimit = percent
        val ok = request("CHARGE $percent")?.startsWith("OK") == true
        main.post { done(ok) }
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
            // No shipped profile: try one from the phone's own audio files once (the call test still decides).
            if (p == null && !autoTried) {
                autoTried = true
                profile = AutoProfile.build { n -> send("HAS $n")?.startsWith("OK") == true }
                Log.i(TAG, "profile: ${profile?.let { "${it.id} (auto)" } ?: "none, Basic mode"}")
                // What the auto profile found (or why not) is the one thing we can't learn from firmware dumps
                Diag.event("auto_profile", org.json.JSONObject().put("what", if (profile != null) "built" else "none")
                    .put("profile", profile?.json ?: org.json.JSONObject.NULL).put("audio", Discovery.audio()))
            }
            profile?.daemonSetLines()?.forEach { send(it) }
            if (Box.blocksPowerKey) send("POWERKEY 1")      // a restarted daemon has let go of the power key
            if (chargeLimit > 0) send("CHARGE $chargeLimit")
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
