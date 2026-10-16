// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import dev.simrelay.protocol.v1.Hotspot as HotspotMsg
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

/**
 * Wi-Fi hotspot with internet sharing. TetheringManager / SoftApConfiguration are system APIs (not in
 * the public SDK), hence reflection; they need TETHER_PRIVILEGED + OVERRIDE_WIFI_CONFIG (whitelisted in
 * the module). `cmd wifi start-softap` is not an option: it starts the radio without tethering.
 */
object Hotspot {
    private const val TAG = "SimRelayHost"
    private const val TETHERING_WIFI = 0
    // SoftApConfiguration constants
    private const val BAND_2GHZ = 1
    private const val BAND_5GHZ = 2
    private const val BAND_6GHZ = 4
    private const val SEC_OPEN = 0
    private const val SEC_WPA2 = 1
    private const val SEC_WPA3_TRANSITION = 2
    private const val SEC_WPA3 = 3

    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var clients: List<HotspotMsg.Client> = emptyList()
    @Volatile private var tetheredWifi = false
    private var onChange: (() -> Unit)? = null

    /** Follow tethering state and connected clients (TetheringEventCallback); [changed] on every change. */
    fun watch(ctx: Context, changed: () -> Unit) {
        onChange = changed
        runCatching {
            val tm = ctx.getSystemService("tethering") ?: return
            val cbClass = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
            val cb = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { proxy, m, args ->
                when (m.name) {
                    // The framework puts the callback in maps: answer Object's methods properly.
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === args?.get(0)
                    "toString" -> return@newProxyInstance "SimRelayTetheringCallback"
                    "onClientsChanged" -> {
                        val raw = (args?.get(0) as? Collection<*>).orEmpty()
                        if (raw.isNotEmpty()) clients = raw.mapNotNull { c -> client(c) }   // usually empty on Android 13
                        Log.i(TAG, "hotspot clients: ${clients.size} (raw ${raw.size}) ${clients.map { it.name.ifEmpty { "?" } }}")
                        changed()
                    }
                    "onTetheredInterfacesChanged" -> {
                        // Android 13 passes Set<TetheringInterface> (getInterface()); older ones List<String>.
                        val ifaces = (args?.get(0) as? Collection<*>).orEmpty().map { e ->
                            e as? String ?: runCatching { e!!.javaClass.getMethod("getInterface").invoke(e) as String }
                                .getOrDefault(e.toString())
                        }
                        Log.i(TAG, "tethered interfaces: $ifaces")
                        val wifi = ifaces.any { it.startsWith("wlan") || it.startsWith("swlan") || it.startsWith("ap") }
                        DataUsage.tetheringChanged(ifaces.filter { it.startsWith("swlan") || it.startsWith("wlan") || it.startsWith("ap") })
                        if (wifi != tetheredWifi) { tetheredWifi = wifi; Log.i(TAG, "hotspot tethered=$wifi $ifaces"); changed() }
                    }
                }
                when (m.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
            tm.javaClass.getMethod("registerTetheringEventCallback", java.util.concurrent.Executor::class.java, cbClass)
                .invoke(tm, exec, cb)
        }.onFailure { Log.w(TAG, "tethering events unavailable: ${cause(it)}") }
    }

    /** Devices on the hotspot from the kernel ARP table via simrelayd (Android 13 hides TetheredClient details). */
    fun refreshClients(iface: String) = Daemon.query("NEIGH $iface") { reply ->
        val list = reply?.removePrefix("OK ")?.substringAfter(' ', "")?.split(';')?.filter { it.contains(',') }.orEmpty()
            .map { e -> HotspotMsg.Client.newBuilder().setMac(e.substringBefore(',')).setName(e.substringAfter(',')).build() }
        if (list.map { it.mac } != clients.map { it.mac }) {
            clients = list
            Log.i(TAG, "hotspot devices: ${list.size}")
            onChange?.invoke()
        }
    }

    fun clearClients() { if (clients.isNotEmpty()) { clients = emptyList(); onChange?.invoke() } }

    // TetheredClient -> (name, mac). Never drop a client because a field can't be read: the automatic
    // hotspot relies on "nothing connected" being right.
    private fun client(c: Any?): HotspotMsg.Client? {
        if (c == null) return null
        val mac = runCatching { c.javaClass.getMethod("getMacAddress").invoke(c).toString() }.getOrDefault("")
        val name = runCatching {
            (c.javaClass.getMethod("getAddresses").invoke(c) as List<*>).firstNotNullOfOrNull { a ->
                a?.javaClass?.getMethod("getHostname")?.invoke(a) as? String
            }
        }.getOrNull()
        return HotspotMsg.Client.newBuilder().setMac(mac).setName(name.orEmpty()).build()
    }

    fun info(ctx: Context): HotspotMsg = runCatching {
        val wm = ctx.getSystemService(WifiManager::class.java)
        val on = WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wm) as Boolean
        val cfg = WifiManager::class.java.getMethod("getSoftApConfiguration").invoke(wm)!!
        fun <T> get(name: String): T? = runCatching { @Suppress("UNCHECKED_CAST") (cfg.javaClass.getMethod(name).invoke(cfg) as T) }.getOrNull()
        val bands = get<IntArray>("getBands")
        val band = when {
            bands != null && bands.size >= 2 -> HotspotMsg.Band.BAND_DUAL_2_5
            else -> when (get<Int>("getBand") ?: BAND_2GHZ) {
                BAND_2GHZ -> HotspotMsg.Band.BAND_2GHZ
                BAND_5GHZ -> HotspotMsg.Band.BAND_5GHZ
                BAND_6GHZ -> HotspotMsg.Band.BAND_6GHZ
                else -> HotspotMsg.Band.BAND_AUTO
            }
        }
        val security = when (get<Int>("getSecurityType")) {
            SEC_OPEN -> HotspotMsg.Security.OPEN
            SEC_WPA3_TRANSITION -> HotspotMsg.Security.WPA3_TRANSITION
            SEC_WPA3 -> HotspotMsg.Security.WPA3
            else -> HotspotMsg.Security.WPA2
        }
        HotspotMsg.newBuilder()
            .setSupported(true).setOn(on)
            .setSsid(get<String>("getSsid").orEmpty())
            .setPassphrase(get<String>("getPassphrase").orEmpty())
            .setBand(band).setSecurity(security)
            .setHidden(get<Boolean>("isHiddenSsid") ?: false)
            .setMaxClients(get<Int>("getMaxNumberOfClients") ?: 0)
            .setAutoOff(get<Boolean>("isAutoShutdownEnabled") ?: false)
            .setAutoOffMinutes(((get<Long>("getShutdownTimeoutMillis") ?: 0L) / 60_000L).toInt())
            .addAllClients(if (on) clients else emptyList())
            .setCapabilities(capabilities(wm))
            .setUsage(DataUsage.usage())
            .build()
    }.getOrElse { Log.w(TAG, "hotspot info unavailable: ${cause(it)}"); HotspotMsg.newBuilder().setSupported(false).build() }

    private fun capabilities(wm: WifiManager): HotspotMsg.Capabilities {
        val b = HotspotMsg.Capabilities.newBuilder().setHiddenSsid(true).setMaxClients(true).setAutoOff(true)
        b.addBands(HotspotMsg.Band.BAND_2GHZ)
        if (wm.is5GHzBandSupported) b.addBands(HotspotMsg.Band.BAND_5GHZ).addBands(HotspotMsg.Band.BAND_AUTO)
        if (wm.is6GHzBandSupported) b.addBands(HotspotMsg.Band.BAND_6GHZ)
        if (wm.is5GHzBandSupported && android.os.Build.VERSION.SDK_INT >= 31 && wm.isBridgedApConcurrencySupported) b.addBands(HotspotMsg.Band.BAND_DUAL_2_5)
        b.addSecurities(HotspotMsg.Security.WPA2)
        if (wm.isWpa3SaeSupported) b.addSecurities(HotspotMsg.Security.WPA3_TRANSITION).addSecurities(HotspotMsg.Security.WPA3)
        return b.build()
    }

    /** Apply the client's settings (ssid, passphrase, band, hidden, security, max clients, auto-off). */
    fun configure(ctx: Context, c: HotspotMsg, done: (Boolean, String) -> Unit) {
        runCatching {
            if (c.ssid.isBlank() || c.ssid.toByteArray().size > 32) error("network name must be 1-32 bytes")
            val sec = when (c.security) {
                HotspotMsg.Security.OPEN -> SEC_OPEN
                HotspotMsg.Security.WPA3_TRANSITION -> SEC_WPA3_TRANSITION
                HotspotMsg.Security.WPA3 -> SEC_WPA3
                else -> SEC_WPA2
            }
            if (sec != SEC_OPEN && c.passphrase.length !in 8..63) error("password must be 8-63 characters")
            val wm = ctx.getSystemService(WifiManager::class.java)
            val wasOn = WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wm) as Boolean
            val cfgClass = Class.forName("android.net.wifi.SoftApConfiguration")
            val current = WifiManager::class.java.getMethod("getSoftApConfiguration").invoke(wm)
            val bClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            val b = bClass.getConstructor(cfgClass).newInstance(current)
            fun call(name: String, types: Array<Class<*>>, vararg args: Any?) = bClass.getMethod(name, *types).invoke(b, *args)
            call("setSsid", arrayOf(String::class.java), c.ssid)
            call("setPassphrase", arrayOf(String::class.java, Int::class.javaPrimitiveType!!), if (sec == SEC_OPEN) null else c.passphrase, sec)
            when (c.band) {
                HotspotMsg.Band.BAND_DUAL_2_5 -> call("setBands", arrayOf(IntArray::class.java), intArrayOf(BAND_2GHZ, BAND_5GHZ))
                HotspotMsg.Band.BAND_5GHZ -> call("setBand", arrayOf(Int::class.javaPrimitiveType!!), BAND_5GHZ)
                HotspotMsg.Band.BAND_6GHZ -> call("setBand", arrayOf(Int::class.javaPrimitiveType!!), BAND_6GHZ)
                HotspotMsg.Band.BAND_AUTO -> call("setBand", arrayOf(Int::class.javaPrimitiveType!!), BAND_2GHZ or BAND_5GHZ)
                else -> call("setBand", arrayOf(Int::class.javaPrimitiveType!!), BAND_2GHZ)
            }
            call("setHiddenSsid", arrayOf(Boolean::class.javaPrimitiveType!!), c.hidden)
            if (c.maxClients > 0) call("setMaxNumberOfClients", arrayOf(Int::class.javaPrimitiveType!!), c.maxClients)
            call("setAutoShutdownEnabled", arrayOf(Boolean::class.javaPrimitiveType!!), c.autoOff)
            if (c.autoOff && c.autoOffMinutes > 0)
                call("setShutdownTimeoutMillis", arrayOf(Long::class.javaPrimitiveType!!), c.autoOffMinutes * 60_000L)
            val cfg = bClass.getMethod("build").invoke(b)
            val ok = WifiManager::class.java.getMethod("setSoftApConfiguration", cfgClass).invoke(wm, cfg) as Boolean
            Log.i(TAG, "hotspot config saved=$ok band=${c.band} sec=${c.security} hidden=${c.hidden} " +
                    "max=${c.maxClients} autoOff=${c.autoOff}/${c.autoOffMinutes}min (was on: $wasOn)")
            if (!ok) error("the phone rejected these settings")
            if (wasOn) set(ctx, false) { _, _ -> exec.execute { Thread.sleep(1500); set(ctx, true, done) } }
            else done(true, "saved")
        }.onFailure { done(false, cause(it).message ?: cause(it).toString()) }
    }

    /** Start/stop Wi-Fi tethering; [done] gets (ok, detail) once the system answers. */
    fun set(ctx: Context, on: Boolean, done: (Boolean, String) -> Unit) {
        runCatching {
            val tm = ctx.getSystemService("tethering") ?: error("no tethering service")
            val tmClass = tm.javaClass
            if (!on) {
                tmClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, TETHERING_WIFI)
                Log.i(TAG, "hotspot stop requested")
                done(true, "stopped")
                return
            }
            val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
            builderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType).invoke(builder, false)
            val request = builderClass.getMethod("build").invoke(builder)
            val cbClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val cb = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { _, m, args ->
                when (m.name) {
                    "onTetheringStarted" -> { Log.i(TAG, "hotspot started"); done(true, "started") }
                    "onTetheringFailed" -> { Log.w(TAG, "hotspot failed: error ${args?.get(0)}"); done(false, "error ${args?.get(0)}") }
                }
                null
            }
            tmClass.getMethod("startTethering", request.javaClass, java.util.concurrent.Executor::class.java, cbClass)
                .invoke(tm, request, exec, cb)
            Log.i(TAG, "hotspot start requested")
        }.onFailure {
            Log.e(TAG, "hotspot ${if (on) "start" else "stop"} failed: ${cause(it)}")
            done(false, cause(it).toString())
        }
    }

    private fun cause(t: Throwable): Throwable = (t as? InvocationTargetException)?.targetException ?: t
}
