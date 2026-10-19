// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * The host app's root shell (Magisk su), in one place: every call has a time limit (a su waiting on a prompt nobody
 * answers, on a headless phone, must not block a caller forever: several call sites read the output before their
 * timed wait, so the limit never applied), output and errors merged, the exit code kept. Anything that carries data
 * (paths, Wi-Fi names, passwords) goes through [q], never pasted into the command unquoted.
 * Must not be called on the main thread.
 */
object Root {
    private const val TAG = "SimRelayHost"

    data class Result(val code: Int, val out: String) {
        val ok get() = code == 0
        /** The last non-empty output line (helpers end with "ok" / "error ..."). */
        val last get() = out.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
    }

    fun sh(cmd: String, timeoutMs: Long = 30_000): Result = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } } }
            .apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            Log.w(TAG, "root: timed out after ${timeoutMs / 1000} s: ${logged(cmd)}")
            reader.join(500)
            return Result(-1, synchronized(out) { out.toString() } + "timeout")
        }
        reader.join(2000)
        Result(p.exitValue(), synchronized(out) { out.toString() })
    }.getOrElse { Result(-1, "su: $it") }

    /** A command as it may appear in a log: cut before the first quoted word ([q]: SSIDs, passwords, paths). */
    private fun logged(cmd: String) = cmd.substringBefore('\'').take(60).trimEnd() + if ('\'' in cmd) " …" else ""

    /** One shell word, whatever it contains. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Run one of this app's classes as root (system APIs that check the caller's identity: RootWifi, RootBt). */
    fun appProcess(ctx: Context, cls: String, vararg args: String, timeoutMs: Long = 30_000): Result =
        sh("CLASSPATH=${q(ctx.applicationInfo.sourceDir)} app_process /system/bin $cls ${args.joinToString(" ") { q(it) }}", timeoutMs)

    /** Fire and forget, off the caller's thread. */
    fun background(cmd: String, timeoutMs: Long = 30_000) = Thread { sh(cmd, timeoutMs) }.apply { isDaemon = true }.start()
}
