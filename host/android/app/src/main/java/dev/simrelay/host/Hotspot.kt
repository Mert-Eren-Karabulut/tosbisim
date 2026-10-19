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
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var listener: (() -> Unit)? = null
    /** Every change is reported on the main thread, whichever thread noticed it (tethering callbacks, the daemon). */
    private fun changed() { listener?.let { main.post(it) } }

    /** Follow tethering state and connected clients (TetheringEventCallback); [changed] on every change, on main. */
    fun watch(ctx: Context, onChange: () -> Unit) {
        listener = onChange
        exec.execute { apConfig(ctx, wait = true) }       // Android 11: have the root-read settings ready
        dropWpa3(ctx)
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
                        tethered = ifaces
                        if (wifi != tetheredWifi) { tetheredWifi = wifi; Log.i(TAG, "hotspot tethered=$wifi $ifaces"); changed() }
                        if (!wifi) whenDown?.invoke()
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
            changed()
        }
    }

    /** Devices on the hotspot now (from the tethering callbacks / ARP table). */
    fun clientCount(): Int = clients.size

    fun clearClients() { if (clients.isNotEmpty()) { clients = emptyList(); changed() } }

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

    /** The soft AP settings we show and change. */
    private data class ApCfg(val ssid: String, val passphrase: String, val security: Int, val band: Int, val bands: IntArray?,
                             val hidden: Boolean, val maxClients: Int, val autoOff: Boolean, val timeoutMs: Long) {
        fun same(o: ApCfg) = copy(bands = null) == o.copy(bands = null) && bands.contentEquals(o.bands)
    }

    @Volatile private var rootCfg: Pair<ApCfg, Long>? = null
    @Volatile private var rootCfgLoading = false

    /** On or off (cheap: no settings read, never a root call). */
    fun isOn(ctx: Context): Boolean = runCatching {
        WifiManager::class.java.getMethod("isWifiApEnabled").invoke(ctx.getSystemService(WifiManager::class.java)) as Boolean
    }.getOrDefault(false)

    /**
     * The hotspot's settings: from WifiManager where we may (Android 12+), else (Android 11: NETWORK_SETTINGS only) from
     * the root helper. That one takes seconds, so callers get the cached copy and a stale one is re-read on this
     * object's executor (a change is then reported); only that executor ever waits for it ([wait]).
     */
    private fun apConfig(ctx: Context, wait: Boolean = false): ApCfg? {
        val wm = ctx.getSystemService(WifiManager::class.java)
        val direct = runCatching { WifiManager::class.java.getMethod("getSoftApConfiguration").invoke(wm)!! }
        direct.getOrNull()?.let { cfg ->
            fun <T> get(name: String): T? = runCatching { @Suppress("UNCHECKED_CAST") (cfg.javaClass.getMethod(name).invoke(cfg) as T) }.getOrNull()
            return ApCfg(get<String>("getSsid").orEmpty(), get<String>("getPassphrase").orEmpty(), get<Int>("getSecurityType") ?: SEC_WPA2,
                get<Int>("getBand") ?: BAND_2GHZ, get<IntArray>("getBands"), get<Boolean>("isHiddenSsid") ?: false,
                get<Int>("getMaxNumberOfClients") ?: 0, get<Boolean>("isAutoShutdownEnabled") ?: false, get<Long>("getShutdownTimeoutMillis") ?: 0L)
        }
        if (cause(direct.exceptionOrNull() ?: return null) !is SecurityException) return null
        val cached = rootCfg
        if (cached != null && android.os.SystemClock.elapsedRealtime() - cached.second < 60_000) return cached.first
        if (!wait) {
            if (!rootCfgLoading) { rootCfgLoading = true; exec.execute { apConfig(ctx, wait = true); rootCfgLoading = false } }
            return cached?.first
        }
        val m = RootWifi.run(ctx, "get") ?: return cached?.first
        fun s(k: String) = String(android.util.Base64.decode(m[k].orEmpty(), android.util.Base64.NO_WRAP))
        val c = ApCfg(s("ssid"), s("passphrase"), m["security"]?.toIntOrNull() ?: SEC_WPA2, m["band"]?.toIntOrNull() ?: BAND_2GHZ,
            m["bands"]?.split(",")?.mapNotNull { it.toIntOrNull() }?.toIntArray(), m["hidden"] == "1", m["max"]?.toIntOrNull() ?: 0,
            m["autoOff"] == "1", m["timeout"]?.toLongOrNull() ?: 0L)
        rootCfg = c to android.os.SystemClock.elapsedRealtime()
        if (cached == null || !cached.first.same(c)) changed()      // new settings (or the first): push status
        return c
    }

    fun info(ctx: Context, wait: Boolean = false): HotspotMsg = runCatching {
        val wm = ctx.getSystemService(WifiManager::class.java)
        val on = WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wm) as Boolean
        val cfg = apConfig(ctx, wait) ?: return@runCatching loading(on)
        val band = when {
            cfg.bands != null && cfg.bands.size >= 2 -> HotspotMsg.Band.BAND_DUAL_2_5
            else -> when (cfg.band) {
                BAND_2GHZ -> HotspotMsg.Band.BAND_2GHZ
                BAND_5GHZ -> HotspotMsg.Band.BAND_5GHZ
                BAND_6GHZ -> HotspotMsg.Band.BAND_6GHZ
                else -> HotspotMsg.Band.BAND_AUTO
            }
        }
        val security = when (cfg.security) {
            SEC_OPEN -> HotspotMsg.Security.OPEN
            SEC_WPA3_TRANSITION -> HotspotMsg.Security.WPA3_TRANSITION
            SEC_WPA3 -> HotspotMsg.Security.WPA3
            else -> HotspotMsg.Security.WPA2
        }
        val starting = !on && startingSince > 0 && android.os.SystemClock.elapsedRealtime() - startingSince < 15_000
        HotspotMsg.newBuilder()
            .setSupported(true).setOn(on).setStarting(starting).setGateway(if (on) gateway() else "")
            .setSsid(cfg.ssid).setPassphrase(cfg.passphrase)
            .setBand(band).setSecurity(security)
            .setHidden(cfg.hidden).setMaxClients(cfg.maxClients)
            .setAutoOff(cfg.autoOff).setAutoOffMinutes((cfg.timeoutMs / 60_000L).toInt())
            .addAllClients(if (on) clients else emptyList())
            .setCapabilities(runCatching { capabilities(wm) }.getOrDefault(HotspotMsg.Capabilities.getDefaultInstance()))
            .setUsage(DataUsage.usage())
            .build()
    }.getOrElse { Log.w(TAG, "hotspot info unavailable: ${cause(it)}"); HotspotMsg.newBuilder().setSupported(false).build() }

    /** Android 11, before the root read of the settings is back (a second or two after start): on/off only. */
    private fun loading(on: Boolean): HotspotMsg = HotspotMsg.newBuilder().setSupported(true).setOn(on).build()

    /**
     * Offered settings. WPA2 only (18 Oct): WPA3 hotspots depend on the phone (the Redmi Note 8 Pro's station
     * has WPA3, its hotspot refuses SAE and silently never starts) and nobody needs them for a phone-to-phone link.
     */
    private fun capabilities(wm: WifiManager): HotspotMsg.Capabilities {
        val b = HotspotMsg.Capabilities.newBuilder().setHiddenSsid(true).setMaxClients(true).setAutoOff(true)
        b.addBands(HotspotMsg.Band.BAND_2GHZ)
        if (wm.is5GHzBandSupported) b.addBands(HotspotMsg.Band.BAND_5GHZ).addBands(HotspotMsg.Band.BAND_AUTO)
        if (wm.is6GHzBandSupported) b.addBands(HotspotMsg.Band.BAND_6GHZ)
        if (wm.is5GHzBandSupported && android.os.Build.VERSION.SDK_INT >= 31 && wm.isBridgedApConcurrencySupported) b.addBands(HotspotMsg.Band.BAND_DUAL_2_5)
        b.addSecurities(HotspotMsg.Security.WPA2)
        return b.build()
    }

    /** A hotspot left on WPA3 (by the OEM or an older host version) goes back to WPA2, same name and password. */
    private fun dropWpa3(ctx: Context) = exec.execute {
        val h = info(ctx, wait = true)
        if (!h.supported || h.on || (h.security != HotspotMsg.Security.WPA3 && h.security != HotspotMsg.Security.WPA3_TRANSITION)) return@execute
        configureNow(ctx, h.toBuilder().setSecurity(HotspotMsg.Security.WPA2).build()) { ok, d ->
            Log.i(TAG, "hotspot security ${h.security} -> WPA2: ${if (ok) "done" else d}")
            changed()
        }
    }

    /**
     * Apply the client's settings (ssid, passphrase, band, hidden, security, max clients, auto-off). On this object's
     * executor (Android 11 saves them as root, seconds); [reply] on main.
     */
    fun configure(ctx: Context, c: HotspotMsg, reply: (Boolean, String) -> Unit) = exec.execute { configureNow(ctx, c, reply) }

    private fun configureNow(ctx: Context, c: HotspotMsg, reply: (Boolean, String) -> Unit) {
        val done: (Boolean, String) -> Unit = { ok, d -> main.post { reply(ok, d) } }
        runCatching {
            if (c.ssid.isBlank() || c.ssid.toByteArray().size > 32) error("network name must be 1-32 bytes")
            val sec = when (c.security) {
                HotspotMsg.Security.OPEN -> SEC_OPEN
                HotspotMsg.Security.WPA3_TRANSITION, HotspotMsg.Security.WPA3 -> error("WPA3 isn't supported: use WPA2")
                else -> SEC_WPA2
            }
            if (sec != SEC_OPEN && c.passphrase.length !in 8..63) error("password must be 8-63 characters")
            val wm = ctx.getSystemService(WifiManager::class.java)
            val wasOn = WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wm) as Boolean
            val cfgClass = Class.forName("android.net.wifi.SoftApConfiguration")
            val current = runCatching { WifiManager::class.java.getMethod("getSoftApConfiguration").invoke(wm) }.getOrNull()
            if (current == null) {                       // Android 11: only as root (RootWifi)
                val bands = when (c.band) {
                    HotspotMsg.Band.BAND_DUAL_2_5 -> "$BAND_2GHZ,$BAND_5GHZ"; HotspotMsg.Band.BAND_5GHZ -> "$BAND_5GHZ"
                    HotspotMsg.Band.BAND_6GHZ -> "$BAND_6GHZ"; HotspotMsg.Band.BAND_AUTO -> "${BAND_2GHZ or BAND_5GHZ}"; else -> "$BAND_2GHZ"
                }
                fun b64(x: String) = android.util.Base64.encodeToString(x.toByteArray(), android.util.Base64.NO_WRAP)
                RootWifi.run(ctx, "set", b64(c.ssid), b64(c.passphrase), "$sec", bands, if (c.hidden) "1" else "0", "${c.maxClients}",
                    if (c.autoOff) "1" else "0", "${if (c.autoOff && c.autoOffMinutes > 0) c.autoOffMinutes * 60_000L else 0L}")
                    ?: error("the phone rejected these settings")
                rootCfg = null
                Log.i(TAG, "hotspot config saved as root (Android 11) band=${c.band} sec=${c.security} (was on: $wasOn)")
                if (wasOn) restart(ctx, reply) else done(true, "saved")
                return
            }
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
            if (wasOn) restart(ctx, reply) else done(true, "saved")
        }.onFailure { done(false, cause(it).message ?: cause(it).toString()) }
    }

    @Volatile private var tethered: List<String> = emptyList()
    @Volatile private var whenDown: (() -> Unit)? = null

    /** New settings on a running hotspot: stop it, start it again once Android reports Wi-Fi tethering gone. */
    private fun restart(ctx: Context, reply: (Boolean, String) -> Unit) {
        val again = Runnable { whenDown = null; set(ctx, true, reply) }
        whenDown = { main.removeCallbacks(again); main.post(again) }
        main.postDelayed(again, 5_000)                 // no tethering event (callback unavailable): go anyway
        set(ctx, false) { _, _ -> }
    }

    /** The soft AP's own IPv4 (e.g. 192.168.x.1): on the interfaces Android reports as tethered, else by usual names. */
    private fun gateway(): String = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && (it.name in tethered || it.name.startsWith("swlan") || it.name.startsWith("ap") ||
                it.name.startsWith("softap") || (it.name.startsWith("wlan") && it.name != "wlan0")) }
            .sortedByDescending { it.name in tethered }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull().orEmpty()

    /** Start/stop Wi-Fi tethering; [done] gets (ok, detail) once the system answers. */
    /** A start is in flight since (elapsedRealtime); 0 = none. */
    @Volatile private var startingSince = 0L

    /**
     * Idempotent: the iPhone may ask several times at once (widget taps + automatic hotspot), and a second
     * startTethering while one is in flight fails with TETHER_ERROR_INTERNAL_ERROR (5) although the hotspot
     * comes up (seen 16 Oct 2026). So "on" while on or starting is OK, and a failure while the hotspot is
     * actually up counts as success.
     */
    fun set(ctx: Context, on: Boolean, reply: (Boolean, String) -> Unit) {
        val done: (Boolean, String) -> Unit = { ok, d -> main.post { reply(ok, d) } }     // answers come on main
        val now = android.os.SystemClock.elapsedRealtime()
        val isOn = isOn(ctx)
        if (on && (isOn || now - startingSince < 15_000)) {
            Log.i(TAG, "hotspot start: already ${if (isOn) "on" else "starting"}, nothing to do")
            done(true, if (isOn) "already on" else "starting"); return
        }
        if (!on && !isOn && now - startingSince >= 15_000) { done(true, "already off"); return }
        // (a stop while starting goes through: stopTethering cancels the pending start)
        runCatching {
            val tm = ctx.getSystemService("tethering") ?: error("no tethering service")
            val tmClass = tm.javaClass
            if (!on) {
                startingSince = 0
                tmClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, TETHERING_WIFI)
                Log.i(TAG, "hotspot stop requested")
                done(true, "stopped")
                return
            }
            MobileData.ensureOn(ctx)                    // the hotspot shares mobile data: it must be on
            val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
            builderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType).invoke(builder, false)
            val request = builderClass.getMethod("build").invoke(builder)
            val cbClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val cb = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { _, m, args ->
                when (m.name) {
                    // Fires when the request is accepted, ~2 s before the AP is up: keep the in-flight window open
                    // (isWifiApEnabled covers it once up), or a second request in the gap fails with error 5.
                    "onTetheringStarted" -> { Log.i(TAG, "hotspot started"); done(true, "started") }
                    "onTetheringFailed" -> {
                        startingSince = 0
                        val upAnyway = isOn(ctx)
                        Log.w(TAG, "hotspot start failed: error ${args?.get(0)}${if (upAnyway) " (but it is on: OK)" else ""}")
                        done(upAnyway, if (upAnyway) "started" else "error ${args?.get(0)}")
                    }
                }
                null
            }
            startingSince = now
            tmClass.getMethod("startTethering", request.javaClass, java.util.concurrent.Executor::class.java, cbClass)
                .invoke(tm, request, exec, cb)
            Log.i(TAG, "hotspot start requested")
        }.onFailure {
            if (on) startingSince = 0
            Log.e(TAG, "hotspot ${if (on) "start" else "stop"} failed: ${cause(it)}")
            done(false, cause(it).toString())
        }
    }

    private fun cause(t: Throwable): Throwable = (t as? InvocationTargetException)?.targetException ?: t
}
