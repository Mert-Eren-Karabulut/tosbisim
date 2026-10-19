// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.bluetooth.BluetoothAdapter

/**
 * Accept a Bluetooth pairing request while the host's pairing window is open (19 Oct: headless hosts can't tap
 * "Pair"). setPairingConfirmation needs BLUETOOTH_PRIVILEGED; adding that to the manifest would stop the phone booting
 * wherever the module's privapp whitelist is older (self-updates don't replace it before the next boot), so this runs
 * as root through app_process instead, like [RootWifi].
 *
 *   CLASSPATH=<apk> app_process /system/bin dev.simrelay.host.RootBt confirm|unbond <MAC>   -> "ok" | "error ..."
 */
object RootBt {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            require(args.isNotEmpty() && args[0] in setOf("confirm", "unbond", "check")) { "usage" }
            val adapter = adapter() ?: return println("error no Bluetooth adapter: $why")
            if (args[0] == "check") return println("ok ${adapter.state}")
            require(args.size == 2) { "usage" }
            @Suppress("MissingPermission")
            val dev = adapter.getRemoteDevice(args[1])
            val ok = if (args[0] == "confirm") dev.setPairingConfirmation(true)
                else dev.javaClass.getMethod("removeBond").invoke(dev) as Boolean
            println(if (ok) "ok" else "error refused")
        } catch (t: Throwable) {
            println("error ${t.cause ?: t}")
        }
    }

    /**
     * The adapter in this bare process. getDefaultAdapter() needs an app context since Android 12 and returns null here
     * (S20 FE, Android 13, 19 Oct: every pairing confirmation failed); then make a system context for this process
     * (our own app_process, not system_server).
     */
    @Suppress("DEPRECATION")
    private fun adapter(): BluetoothAdapter? {
        // Android 13+: Bluetooth is a mainline module whose service handle an app process gets at start-up
        // (ActivityThread.initializeMainlineModules); a bare app_process must do it itself or the adapter is null.
        runCatching { Class.forName("android.app.ActivityThread").getMethod("initializeMainlineModules").invoke(null) }
            .onFailure { why += "mainline: ${it.cause ?: it}; " }
        runCatching { BluetoothAdapter.getDefaultAdapter() }.onFailure { why += "default: $it; " }.getOrNull()?.let { return it }
        return runCatching {
            if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper()
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("systemMain").invoke(null)
            val ctx = at.getMethod("getSystemContext").invoke(thread) as android.content.Context
            ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        }.onFailure { why += "system context: ${it.cause ?: it}" }.getOrNull()
    }
    private var why = ""

    /** From the app (background thread): true if Android took the confirmation. */
    fun confirm(ctx: android.content.Context, mac: String): Boolean {
        if (!Regex("^[0-9A-F:]{17}$").matches(mac)) return false
        val last = Root.appProcess(ctx, "dev.simrelay.host.RootBt", "confirm", mac).last
        android.util.Log.i("SimRelayHost", "pairing request from $mac: confirm -> $last")
        // A failure here leaves the iPhone at "connecting" on a phone nobody can tap "Pair" on: we must hear of it
        if (last != "ok") Diag.event("pairing_confirm_failed", org.json.JSONObject().put("what", last.substringBefore(':').take(60)), withLog = false)
        return last == "ok"
    }

    /** Forget the Bluetooth bond with [mac] (the iPhone that unpaired). */
    fun unbond(ctx: android.content.Context, mac: String): Boolean {
        if (!Regex("^[0-9A-F:]{17}$").matches(mac)) return false
        val last = Root.appProcess(ctx, "dev.simrelay.host.RootBt", "unbond", mac).last
        android.util.Log.i("SimRelayHost", "bond with $mac removed -> $last")
        return last == "ok"
    }
}
