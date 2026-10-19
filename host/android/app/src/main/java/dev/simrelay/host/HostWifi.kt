// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import dev.simrelay.protocol.v1.HostWifi as HostWifiMsg
import dev.simrelay.protocol.v1.WifiAddResult
import dev.simrelay.protocol.v1.WifiScanResult
import java.util.concurrent.Executors

/**
 * The host's own Wi-Fi connection (not the hotspot), one rule on every phone (18 Oct 2026; protocol HostWifi):
 * off while the hotspot is on and while the client is on Bluetooth; when the client has been away [AWAY_MS], on only if
 * the user chose "use Wi-Fi when I'm away". Never switched during a call; left alone until a client is paired.
 *
 * Saved networks, scans and joins go through `cmd wifi` as root: the same commands on Android 11-14 and every maker
 * checked so far (Redmi Note 8 Pro / Android 11, S20 FE / Android 13), where the app APIs need NETWORK_SETTINGS.
 */
object HostWifi {
    private const val TAG = "SimRelayHost"
    private const val AWAY_MS = 120_000L          // Bluetooth gone this long = away (walking around the house isn't)
    private const val SETUP_MS = 90_000L          // Wi-Fi kept on this long for a scan / join from the client
    private const val REMOTE_CHECK_MS = 60_000L   // switched off over the internet: back on if the room is lost

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var near: () -> Boolean
    private lateinit var inCall: () -> Boolean
    private lateinit var paired: () -> Boolean
    private lateinit var hotspotOn: () -> Boolean
    private var changed: () -> Unit = {}

    @Volatile private var lastNear = 0L
    /** Test rig (adb only, HostService WIFI op=client): true = treat the client as nearby, false = away (at once,
     *  no 2-minute wait), null = the real Bluetooth state. */
    @Volatile var clientOverride: Boolean? = null
    @Volatile private var setupUntil = 0L
    @Volatile private var reason = HostWifiMsg.Reason.REASON_UNSPECIFIED
    @Volatile var connectedSsid = ""; private set
    private var wasAway = true      // no "you just left" at start-up
    @Volatile private var saved: List<HostWifiMsg.Saved> = emptyList()

    private val prefs by lazy { app.getSharedPreferences("hostwifi", Context.MODE_PRIVATE) }
    val useWhenAway get() = prefs.getBoolean("use_when_away", false)

    fun start(ctx: Context, near: () -> Boolean, inCall: () -> Boolean, paired: () -> Boolean, hotspotOn: () -> Boolean,
              changed: () -> Unit) {
        app = ctx.applicationContext
        this.near = near; this.inCall = inCall; this.paired = paired; this.hotspotOn = hotspotOn; this.changed = changed
        // Just started: away until the client shows up on Bluetooth (a host abroad keeps its Wi-Fi through a restart).
        lastNear = -AWAY_MS
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { refresh() }
        }, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) = refresh()
            override fun onLost(n: Network) = refresh()
        }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        // The host's internet moving between Wi-Fi and mobile data: the client shows it (Uplink).
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { main.post { changed() } }
            override fun onLost(n: Network) { main.post { changed() } }
        })
        exec.execute { saved = listSaved() }
        evaluate("start")
    }

    // ------------------------------------------------------------------ the rule

    /** Re-check the rule (Bluetooth up/down, hotspot, call end, setting, keep-alive). Main thread. */
    fun evaluate(why: String) {
        if (!::app.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        val isNear = clientOverride ?: near()
        if (isNear) lastNear = now
        val away = clientOverride == false || now - lastNear >= AWAY_MS
        if (away && !wasAway && paired()) leftHost()
        wasAway = away
        val (want, r) = when {
            !paired() -> null to HostWifiMsg.Reason.NOT_PAIRED
            inCall() -> null to HostWifiMsg.Reason.IN_CALL
            hotspotOn() -> false to HostWifiMsg.Reason.HOTSPOT
            now < setupUntil -> true to HostWifiMsg.Reason.SETUP
            !away -> false to HostWifiMsg.Reason.BLUETOOTH
            useWhenAway -> true to HostWifiMsg.Reason.AWAY
            else -> false to HostWifiMsg.Reason.SETTING_OFF
        }
        if (r != reason) { reason = r; changed() }
        if (want != null && want != wifiOn()) {
            Log.i(TAG, "host Wi-Fi -> ${if (want) "on" else "off"} ($r, $why)")
            setRadio(want)
        }
        if (want == true && r == HostWifiMsg.Reason.AWAY) kickJoin() else main.removeCallbacks(joinKick)
        // Bluetooth just went: look again when "away" begins.
        main.removeCallbacks(awayCheck)
        if (!isNear && !away) main.postDelayed(awayCheck, lastNear + AWAY_MS - now + 500)
    }

    private val awayCheck = Runnable { evaluate("client away") }

    /**
     * Wi-Fi on but not joined: scan ourselves. With the screen off (box mode) Samsung only looks for saved networks
     * with an offloaded PNO scan, which can fail once and never retry (S20 FE, 18 Oct: on and unjoined for 9 min).
     * A finished scan makes Android's own network selection join a saved network.
     */
    private var kicks = 0
    private val joinKick = Runnable {
        if (reason != HostWifiMsg.Reason.AWAY || !wifiOn() || connectedSsid.isNotEmpty()) { kicks = 0; return@Runnable }
        kicks++
        exec.execute { root("cmd wifi start-scan"); refresh() }
        Log.i(TAG, "host Wi-Fi: not joined yet, scanning (try $kicks)")
        kickJoin()
    }
    private fun kickJoin() {
        if (connectedSsid.isNotEmpty()) { kicks = 0; return }
        main.removeCallbacks(joinKick)
        main.postDelayed(joinKick, when { kicks == 0 -> 5_000L; kicks < 6 -> 15_000L; else -> 120_000L })   // quick tries, then every 2 min
    }

    /** You just left (Bluetooth gone [AWAY_MS]): a hotspot nobody uses is pointless now and would keep the host off
     *  Wi-Fi (18 Oct: any unused hotspot). Only at that moment: one turned on later while away stays. */
    private fun leftHost() {
        val h = Hotspot.info(app)
        if (!h.on || h.clientsCount > 0) return
        Log.i(TAG, "client left, hotspot unused: turning it off")
        Hotspot.set(app, false) { _, _ -> }
    }

    /** The user's switch. Off from the internet while the host sits on Wi-Fi: if that cuts the host off (no working
     *  mobile data), it comes back on by itself, or nobody could ever reach the host again. */
    fun setUseWhenAway(on: Boolean, remote: Boolean) {
        prefs.edit().putBoolean("use_when_away", on).apply()
        Log.i(TAG, "host Wi-Fi: use when away = $on${if (remote) " (over the internet)" else ""}")
        evaluate("setting")
        changed()
        if (!on && remote) main.postDelayed({
            if (!useWhenAway && !RemoteLink.online && !near()) {
                Log.w(TAG, "host Wi-Fi: no internet without Wi-Fi after the remote switch-off: turning it back on")
                setUseWhenAway(true, remote = false)
            }
        }, REMOTE_CHECK_MS)
    }

    /** For the rig's status line: what the default network is. */
    fun uplinkKind(): String {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val c = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "none"
        return when { c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"; c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"; else -> "other" }
    }

    fun status(): HostWifiMsg = HostWifiMsg.newBuilder()
        .setUseWhenAway(useWhenAway).setOn(wifiOn()).setConnectedSsid(connectedSsid)
        .addAllSaved(saved).setReason(reason).build()

    private fun wifiOn() = app.getSystemService(WifiManager::class.java).isWifiEnabled

    @Suppress("DEPRECATION")
    private fun setRadio(on: Boolean) {
        val ok = runCatching { app.getSystemService(WifiManager::class.java).setWifiEnabled(on) }.getOrDefault(false)
        if (!ok) exec.execute { root("cmd wifi set-wifi-enabled ${if (on) "enabled" else "disabled"}") }
    }

    private fun refresh() = exec.execute {
        val ssid = if (wifiOn()) Regex("Wifi is connected to \"(.*)\"").find(root("cmd wifi status"))?.groupValues?.get(1).orEmpty() else ""
        if (ssid != connectedSsid) {
            connectedSsid = ssid
            Log.i(TAG, "host Wi-Fi: ${if (ssid.isEmpty()) "not connected" else "connected to a saved network"}")
        }
        main.post { changed() }
    }

    // ------------------------------------------------------------------ client requests (Bluetooth only)

    /** Networks around the host, for the client's "Add network" list. Turns Wi-Fi on for a moment if needed. */
    fun scan(done: (WifiScanResult?, String?) -> Unit) {
        if (hotspotOn()) return done(null, "turn the hotspot off first")
        holdForSetup()
        exec.execute {
            if (!awaitRadio()) { main.post { done(null, "Wi-Fi didn't turn on") }; return@execute }
            root("cmd wifi start-scan")
            var list = scanResults()
            for (i in 1..8) { if (list.isNotEmpty() && i > 3) break; Thread.sleep(500); list = scanResults() }
            val savedNames = saved.map { it.ssid }.toSet()
            val nets = list.filter { it.ssid.isNotEmpty() && !it.enterprise }
                .groupBy { it.ssid }.map { (_, g) -> g.maxBy { it.rssi } }.sortedByDescending { it.rssi }.take(30)
                .map { WifiScanResult.Network.newBuilder().setSsid(it.ssid).setSecure(it.secure).setLevel(level(it.rssi))
                    .setSaved(it.ssid in savedNames).build() }
            Log.i(TAG, "host Wi-Fi scan: ${nets.size} networks")
            main.post { done(WifiScanResult.newBuilder().addAllNetworks(nets).build(), null) }
        }
    }

    /** Save a network and join it to prove the password; forgotten again if that fails. */
    fun add(ssid: String, passphrase: String, done: (WifiAddResult) -> Unit) {
        fun result(ok: Boolean, p: WifiAddResult.Problem) = WifiAddResult.newBuilder().setSsid(ssid).setOk(ok).setProblem(p).build()
        if (ssid.isEmpty() || ssid.toByteArray().size > 32 || (passphrase.isNotEmpty() && passphrase.length !in 8..63))
            return done(result(false, WifiAddResult.Problem.BAD_INPUT))
        if (hotspotOn()) return done(result(false, WifiAddResult.Problem.HOTSPOT_ON))
        holdForSetup()
        exec.execute {
            val r = runCatching {
                if (!awaitRadio()) return@runCatching result(false, WifiAddResult.Problem.JOIN_FAILED)
                var seen = scanResults().firstOrNull { it.ssid == ssid }
                if (seen == null) { root("cmd wifi start-scan"); for (i in 1..10) { Thread.sleep(500); seen = scanResults().firstOrNull { it.ssid == ssid }; if (seen != null) break } }
                // Not in the scan: still try (a stuck scanner was seen on the Redmi, 18 Oct; or a hidden network).
                val sec = when {
                    seen == null -> if (passphrase.isEmpty()) "open" else "wpa2"
                    !seen.secure -> "open"
                    seen.sae && !seen.psk -> "wpa3"
                    else -> "wpa2"
                }
                if (sec != "open" && passphrase.isEmpty()) return@runCatching result(false, WifiAddResult.Problem.BAD_INPUT)
                root("cmd wifi connect-network \"$(echo ${b64(ssid)} | base64 -d)\" $sec" +
                    if (sec == "open") "" else " \"$(echo ${b64(passphrase)} | base64 -d)\"")
                var joined = false
                for (i in 1..40) {          // up to 20 s: association + DHCP
                    Thread.sleep(500)
                    if (Regex("Wifi is connected to \"(.*)\"").find(root("cmd wifi status"))?.groupValues?.get(1) == ssid) { joined = true; break }
                }
                if (!joined) listSaved().filter { it.ssid == ssid }.forEach { root("cmd wifi forget-network ${it.id}") }
                result(joined, when { joined -> WifiAddResult.Problem.NONE; seen == null -> WifiAddResult.Problem.NOT_FOUND
                                      else -> WifiAddResult.Problem.JOIN_FAILED })
            }.getOrElse { Log.w(TAG, "host Wi-Fi add failed: $it"); result(false, WifiAddResult.Problem.JOIN_FAILED) }
            saved = listSaved()
            Log.i(TAG, "host Wi-Fi add: ok=${r.ok} ${r.problem}")
            main.post { done(r); release() }
        }
    }

    fun forget(id: Int, done: () -> Unit) = exec.execute {
        root("cmd wifi forget-network $id")
        saved = listSaved()
        Log.i(TAG, "host Wi-Fi: forgot network $id")
        main.post { changed(); done() }
    }

    /** Wi-Fi on for a scan or a join; kept for [SETUP_MS] after a scan so picking a network doesn't restart the radio. */
    private fun holdForSetup() {
        setupUntil = SystemClock.elapsedRealtime() + SETUP_MS
        main.removeCallbacks(setupEnd); main.postDelayed(setupEnd, SETUP_MS + 500)
        evaluate("setup")
    }
    private val setupEnd = Runnable { evaluate("setup over") }
    private fun release() { setupUntil = 0; main.removeCallbacks(setupEnd); evaluate("setup done") }

    private fun awaitRadio(): Boolean {
        for (i in 1..20) { if (wifiOn()) { Thread.sleep(1500); return true }; Thread.sleep(250) }   // settle before scanning
        return wifiOn()
    }

    // ------------------------------------------------------------------ cmd wifi parsing

    private data class Seen(val ssid: String, val rssi: Int, val flags: String) {
        val psk get() = "PSK" in flags
        val sae get() = "SAE" in flags
        val enterprise get() = "EAP" in flags
        val secure get() = psk || sae || "WEP" in flags || "WPA" in flags || "RSN" in flags
    }

    //   BSSID  Frequency  RSSI  Age(sec)  SSID (may contain spaces, empty when hidden)  [flags]
    //   Samsung adds per-antenna RSSI: "-78(0:-80/1:-83)" (S20 FE, 18 Oct)
    private val scanLine = Regex("^\\s*([0-9a-f:]{17})\\s+(\\d+)\\s+(-?\\d+)\\S*\\s+(\\S+)\\s+(.*?)\\s*(\\[.*)$")

    private fun scanResults(): List<Seen> = root("cmd wifi list-scan-results").lines().mapNotNull { l ->
        scanLine.find(l)?.groupValues?.let { g -> Seen(g[5], g[3].toIntOrNull() ?: -100, g[6]) }
    }

    //   Network Id  SSID  Security type   (Samsung lists one id once per security type)
    private val netLine = Regex("^\\s*(\\d+)\\s+(.*?)\\s+(\\S+)$")

    private fun listSaved(): List<HostWifiMsg.Saved> = root("cmd wifi list-networks").lines().drop(1).mapNotNull { l ->
        netLine.find(l)?.groupValues?.let { g -> HostWifiMsg.Saved.newBuilder().setId(g[1].toInt()).setSsid(g[2]).build() }
    }.distinctBy { it.id }

    private fun level(rssi: Int) = when { rssi >= -55 -> 4; rssi >= -66 -> 3; rssi >= -77 -> 2; rssi >= -88 -> 1; else -> 0 }

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

    /** One root shell command; names and passwords only ever appear base64-encoded in it. */
    private fun root(cmd: String): String = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor(); out
    }.getOrElse { Log.w(TAG, "root: $it"); "" }
}
