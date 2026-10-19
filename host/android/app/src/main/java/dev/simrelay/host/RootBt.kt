// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.bluetooth.BluetoothAdapter

/**
 * Accept a Bluetooth pairing request while the host's pairing window is open (19 Oct: headless hosts can't tap
 * "Pair"). setPairingConfirmation needs BLUETOOTH_PRIVILEGED; adding that to the manifest would stop the phone booting
 * wherever the module's privapp whitelist is older (self-updates don't replace it before the next boot), so this runs
 * as root through app_process instead, like [RootWifi].
 *
 *   CLASSPATH=<apk> app_process /system/bin dev.simrelay.host.RootBt confirm <MAC>   -> "ok" | "error ..."
 */
object RootBt {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            require(args.size == 2 && args[0] == "confirm") { "usage" }
            @Suppress("DEPRECATION", "MissingPermission")
            val ok = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(args[1]).setPairingConfirmation(true)
            println(if (ok) "ok" else "error refused")
        } catch (t: Throwable) {
            println("error ${t.cause ?: t}")
        }
    }

    /** From the app (background thread): true if Android took the confirmation. */
    fun confirm(ctx: android.content.Context, mac: String): Boolean {
        if (!Regex("^[0-9A-F:]{17}$").matches(mac)) return false
        val cmd = "CLASSPATH='${ctx.applicationInfo.sourceDir}' app_process /system/bin dev.simrelay.host.RootBt confirm $mac"
        val out = runCatching {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor() }
        }.getOrElse { "error $it" }
        val last = out.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        android.util.Log.i("SimRelayHost", "pairing request from $mac: confirm -> $last")
        return last == "ok"
    }
}
