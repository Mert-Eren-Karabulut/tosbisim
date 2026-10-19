// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Background work that can't take the host down. An exception thrown on a plain executor thread or a HandlerThread
 * kills the whole process: one odd line from a root command on one phone, every start, and the host never stays up
 * (audit, 19 Oct). Here a failing task is logged and reported once per place and kind, and the thread carries on.
 */
object Bg {
    private const val TAG = "SimRelayHost"
    private val reported = java.util.Collections.synchronizedSet(HashSet<String>())

    /** A single background thread whose tasks never crash the process. */
    fun single(name: String, daemon: Boolean = true): Executor {
        val ex = Executors.newSingleThreadExecutor { Thread(it, name).apply { isDaemon = daemon } }
        return Executor { r -> ex.execute { guard(name) { r.run() } } }
    }

    /** A Handler for a HandlerThread with the same guarantee. */
    fun handler(name: String, looper: Looper): Handler = object : Handler(looper) {
        override fun dispatchMessage(msg: Message) { guard(name) { super.dispatchMessage(msg) } }
    }

    inline fun guard(place: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { failed(place, t) }
    }

    fun failed(place: String, t: Throwable) {
        Log.e(TAG, "$place: ${Log.getStackTraceString(t).take(1500)}")
        if (reported.add("$place:${t.javaClass.simpleName}")) runCatching {
            Diag.event("background_error", org.json.JSONObject().put("what", place).put("error", t.javaClass.simpleName)
                .put("trace", Scrub.text(Log.getStackTraceString(t).take(3000))))
        }
    }
}
