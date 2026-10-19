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
    private var sock: LocalSocket? = null
    private var reader: BufferedReader? = null
    private var out: OutputStream? = null
    private var profile: Profile? = null
    @Volatile private var activeSession = 0
    @Volatile private var chargeLimit = 0          // re-sent on reconnect (sysfs resets on reboot only, but cheap)

    @Volatile private var started = false

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
        activeSession = session
        request("CALL_START $session")
    }

    /** No active call any more. Idempotent. */
    fun callIdle() = exec.execute {
        if (activeSession == 0) return@execute
        activeSession = 0
        request("CALL_END")
    }

    fun status() = exec.execute { request("STATUS") }

    fun hasProfile(): Boolean = profile != null
    fun profile(): Profile? = profile
    @Volatile private var autoTried = false

    /** Build the automatic profile now (debug: on a phone that has a shipped one too) and log it. */
    fun tryAutoProfile(done: (Profile?) -> Unit) = exec.execute {
        connect()
        done(AutoProfile.build { n -> runCatching { send("HAS $n") }.getOrNull()?.startsWith("OK") == true })
    }

    /** The daemon (re)started: reconnect now so silence controls and the charge limit are re-applied. */
    fun reconnect() = exec.execute { if (started) { close(); request("STATUS") } }

    fun screenOff() = exec.execute { request("SCREEN_OFF") }
    fun powerKey(block: Boolean) = exec.execute { request("POWERKEY ${if (block) 1 else 0}") }
    fun netblock(uid: Int, on: Boolean) = exec.execute { request("NETBLOCK $uid ${if (on) 1 else 0}") }
    fun wake() = exec.execute { request("WAKE") }
    fun role(role: String, pkg: String) = exec.execute { request("ROLE $role $pkg") }

    /** Charge cap; [done] gets true if applied, false if this phone has no cap node or it failed. */
    fun charge(percent: Int, done: (Boolean) -> Unit = {}) = exec.execute {
        chargeLimit = percent
        done(request("CHARGE $percent")?.startsWith("OK") == true)
    }

    /** One request whose reply the caller needs (e.g. IFSTATS); [done] runs on the daemon thread. */
    fun query(line: String, done: (String?) -> Unit) = exec.execute { done(request(line)) }

    private fun connect(): Boolean {
        if (sock != null) return true
        if (!started) return false
        val p = profile
        return try {
            val s = LocalSocket()
            s.connect(LocalSocketAddress("simrelayd", LocalSocketAddress.Namespace.ABSTRACT))
            sock = s
            reader = BufferedReader(InputStreamReader(s.inputStream))
            out = s.outputStream
            send("HELLO")
            // No shipped profile: try one from the phone's own audio files once (the call test still decides).
            if (p == null && !autoTried) {
                autoTried = true
                profile = AutoProfile.build { n -> send("HAS $n")?.startsWith("OK") == true }
                Log.i(TAG, "profile: ${profile?.let { "${it.id} (auto)" } ?: "none, Basic mode"}")
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
        return null
    }

    private fun close() {
        runCatching { sock?.close() }
        sock = null; reader = null; out = null
    }
}
