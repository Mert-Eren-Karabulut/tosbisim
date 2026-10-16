// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.simrelay.protocol.v1.Hotspot.Usage
import java.time.LocalDate
import java.time.YearMonth

/**
 * Data shared through the hotspot, counted on the host from the hotspot interface's kernel byte counters
 * (read by simrelayd: sysfs needs root). Polled once a minute and only while the hotspot is on, so the
 * cost is two small file reads per minute. Counters restart when the interface comes back up: a value
 * lower than the last one is treated as a fresh counter.
 */
object DataUsage {
    private const val TAG = "SimRelayHost"
    private const val POLL_MS = 60_000L
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    @Volatile private var ifaces: List<String> = emptyList()
    private var onUpdate: (() -> Unit)? = null

    private val prefs get() = app.getSharedPreferences("usage", Context.MODE_PRIVATE)

    fun init(ctx: Context, updated: () -> Unit) { app = ctx.applicationContext; onUpdate = updated }

    /** Wi-Fi tethering interfaces changed (from Hotspot's tethering callback). */
    fun tetheringChanged(wifiIfaces: List<String>) {
        if (!::app.isInitialized) return
        val wasOn = ifaces.isNotEmpty()
        ifaces = wifiIfaces
        if (!wasOn && wifiIfaces.isNotEmpty()) {
            prefs.edit().putLong("session_down", 0).putLong("session_up", 0).apply()
            wifiIfaces.forEach { forgetLast(it) }        // a new session starts from the counter's current value
            main.removeCallbacks(poll); main.postDelayed(poll, 5_000)
        } else if (wasOn && wifiIfaces.isEmpty()) {
            main.removeCallbacks(poll)
            Hotspot.clearClients()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            for (iface in ifaces) { sample(iface); Hotspot.refreshClients(iface) }
            if (ifaces.isNotEmpty()) main.postDelayed(this, POLL_MS)
        }
    }

    private fun sample(iface: String) = Daemon.query("IFSTATS $iface") { reply ->
        val rx = Regex("rx=(\\d+)").find(reply ?: "")?.groupValues?.get(1)?.toLongOrNull() ?: return@query
        val tx = Regex("tx=(\\d+)").find(reply ?: "")?.groupValues?.get(1)?.toLongOrNull() ?: return@query
        val p = prefs
        val lastRx = p.getLong("last_rx_$iface", -1)
        val lastTx = p.getLong("last_tx_$iface", -1)
        // tx on the hotspot interface = data sent to connected devices (their download).
        val down = when { lastTx < 0 -> 0L; tx >= lastTx -> tx - lastTx; else -> tx }
        val up = when { lastRx < 0 -> 0L; rx >= lastRx -> rx - lastRx; else -> rx }
        val e = p.edit().putLong("last_rx_$iface", rx).putLong("last_tx_$iface", tx)
        if (down + up > 0) {
            val day = LocalDate.now().toString()
            val month = YearMonth.now().toString()
            val todayBase = if (p.getString("day", null) == day) p.getLong("today", 0) else 0
            val monthBase = if (p.getString("month_key", null) == month) p.getLong("month", 0) else 0
            e.putLong("session_down", p.getLong("session_down", 0) + down)
                .putLong("session_up", p.getLong("session_up", 0) + up)
                .putString("day", day).putLong("today", todayBase + down + up)
                .putString("month_key", month).putLong("month", monthBase + down + up)
        }
        e.apply()
        if (down + up > 0) { Log.i(TAG, "hotspot data +${(down + up) / 1024} KiB"); main.post { onUpdate?.invoke() } }
    }

    private fun forgetLast(iface: String) = Daemon.query("IFSTATS $iface") { reply ->
        val rx = Regex("rx=(\\d+)").find(reply ?: "")?.groupValues?.get(1)?.toLongOrNull() ?: -1
        val tx = Regex("tx=(\\d+)").find(reply ?: "")?.groupValues?.get(1)?.toLongOrNull() ?: -1
        prefs.edit().putLong("last_rx_$iface", rx).putLong("last_tx_$iface", tx).apply()
    }

    fun usage(): Usage {
        if (!::app.isInitialized) return Usage.getDefaultInstance()
        val p = prefs
        val today = if (p.getString("day", null) == LocalDate.now().toString()) p.getLong("today", 0) else 0
        val month = if (p.getString("month_key", null) == YearMonth.now().toString()) p.getLong("month", 0) else 0
        return Usage.newBuilder().setSessionDown(p.getLong("session_down", 0)).setSessionUp(p.getLong("session_up", 0))
            .setToday(today).setMonth(month).build()
    }
}
