// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.os.IBinder
import android.util.Base64

/**
 * Hotspot settings on Android 11, where reading or changing them needs NETWORK_SETTINGS (not grantable to privileged
 * apps; Android 12+ accepts OVERRIDE_WIFI_CONFIG). Run as root through app_process, the Wi-Fi service's own permission
 * check passes for uid 0. Prints key=value lines (strings Base64), "ok" / "error ...".
 *
 *   CLASSPATH=<apk> app_process /system/bin dev.simrelay.host.RootWifi get
 *   ... set <ssid b64> <passphrase b64> <securityType> <band|band,band> <hidden 0/1> <maxClients> <autoOff 0/1> <timeoutMs>
 */
object RootWifi {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "wifi") as IBinder
            val wifi = Class.forName("android.net.wifi.IWifiManager\$Stub").getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!
            val current = wifi.javaClass.getMethod("getSoftApConfiguration").invoke(wifi)!!
            when (args.firstOrNull()) {
                "get" -> print(current)
                "set" -> set(wifi, current, args)
                else -> println("error usage")
            }
        } catch (t: Throwable) {
            println("error ${(t.cause ?: t)}")
        }
    }

    private fun b64(s: String?) = Base64.encodeToString((s ?: "").toByteArray(), Base64.NO_WRAP)
    private fun unb64(s: String) = String(Base64.decode(s, Base64.NO_WRAP))

    private fun print(c: Any) {
        fun <T> get(name: String): T? = runCatching { @Suppress("UNCHECKED_CAST") (c.javaClass.getMethod(name).invoke(c) as T) }.getOrNull()
        println("ssid=${b64(get<String>("getSsid"))}")
        println("passphrase=${b64(get<String>("getPassphrase"))}")
        println("security=${get<Int>("getSecurityType") ?: 1}")
        println("band=${get<Int>("getBand") ?: 1}")
        get<IntArray>("getBands")?.let { println("bands=${it.joinToString(",")}") }
        println("hidden=${if (get<Boolean>("isHiddenSsid") == true) 1 else 0}")
        println("max=${get<Int>("getMaxNumberOfClients") ?: 0}")
        println("autoOff=${if (get<Boolean>("isAutoShutdownEnabled") == true) 1 else 0}")
        println("timeout=${get<Long>("getShutdownTimeoutMillis") ?: 0}")
        println("ok")
    }

    private fun set(wifi: Any, current: Any, a: Array<String>) {
        val cfgClass = Class.forName("android.net.wifi.SoftApConfiguration")
        val bClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
        val b = bClass.getConstructor(cfgClass).newInstance(current)
        fun call(name: String, types: Array<Class<*>>, vararg args: Any?) = bClass.getMethod(name, *types).invoke(b, *args)
        val sec = a[3].toInt()
        call("setSsid", arrayOf(String::class.java), unb64(a[1]))
        call("setPassphrase", arrayOf(String::class.java, Int::class.javaPrimitiveType!!), if (sec == 0) null else unb64(a[2]), sec)
        val bands = a[4].split(",").map { it.toInt() }
        if (bands.size > 1) call("setBands", arrayOf(IntArray::class.java), bands.toIntArray())
        else call("setBand", arrayOf(Int::class.javaPrimitiveType!!), bands[0])
        call("setHiddenSsid", arrayOf(Boolean::class.javaPrimitiveType!!), a[5] == "1")
        if (a[6].toInt() > 0) call("setMaxNumberOfClients", arrayOf(Int::class.javaPrimitiveType!!), a[6].toInt())
        call("setAutoShutdownEnabled", arrayOf(Boolean::class.javaPrimitiveType!!), a[7] == "1")
        if (a[8].toLong() > 0) call("setShutdownTimeoutMillis", arrayOf(Long::class.javaPrimitiveType!!), a[8].toLong())
        val cfg = bClass.getMethod("build").invoke(b)
        val ok = wifi.javaClass.getMethod("setSoftApConfiguration", cfgClass, String::class.java).invoke(wifi, cfg, "com.android.shell") as Boolean
        println(if (ok) "ok" else "error rejected")
    }

    /** From the app: run the helper as root; key=value map, or null with the error logged. */
    fun run(ctx: android.content.Context, vararg args: String): Map<String, String>? {
        val lines = Root.appProcess(ctx, "dev.simrelay.host.RootWifi", *args).out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.lastOrNull() != "ok") { android.util.Log.w("SimRelayHost", "root wifi ${args.firstOrNull()}: ${lines.lastOrNull()}"); return null }
        // key=value lines only: stderr is merged and MIUI's system context prints stack traces there
        return lines.filter { KEY.containsMatchIn(it) }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    private val KEY = Regex("^[A-Za-z]+=")
}
