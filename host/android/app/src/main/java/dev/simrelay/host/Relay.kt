// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import dev.simrelay.protocol.v1.CallEvent
import dev.simrelay.protocol.v1.Envelope
import dev.simrelay.protocol.v1.Error
import dev.simrelay.protocol.v1.Hello
import dev.simrelay.protocol.v1.HostStatus
import dev.simrelay.protocol.v1.Sim
import dev.simrelay.protocol.v1.Uplink
import dev.simrelay.protocol.v1.KeepAlive
import dev.simrelay.protocol.v1.Remote
import dev.simrelay.protocol.v1.SmsReceived
import dev.simrelay.protocol.v1.SmsSendResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One client connection: a Bluetooth link, or the remote (internet) room. */
sealed class Peer(val id: String) {
    abstract fun send(bytes: ByteArray)
    abstract fun drop()
    class Ble(val device: BluetoothDevice, private val ble: BleLink) : Peer(device.address) {
        override fun send(bytes: ByteArray) = ble.send(device, bytes)
        override fun drop() = ble.disconnect(device)
    }
    object Internet : Peer("remote") {
        override fun send(bytes: ByteArray) { RemoteLink.send(bytes) }
        override fun drop() {}
    }
}

/**
 * Protocol v1 session (protocol/README.md) on top of BleLink, and of RemoteLink in remote mode (Bluetooth wins
 * when both are up; pairing and the remote key only ever happen over Bluetooth).
 * Pairing: the first client to say Hello inside the pairing window has its install_id pinned; after
 * that only that install_id is served. The window is open for 10 min after the host starts while no
 * client is pinned, or for 5 min after `am start-foreground-service ... -a PAIR` (re-pairing).
 */
object Relay {
    private const val TAG = "SimRelayHost"
    private const val PROTOCOL_VERSION = 1
    private const val KEEPALIVE_MS = 60_000L
    private const val FIRST_PAIRING_WINDOW_MS = 10 * 60_000L
    private const val PAIRING_WINDOW_MS = 5 * 60_000L

    private lateinit var app: Context
    private lateinit var ble: BleLink
    private var audio: AudioBridge? = null
    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger()
    private val trusted = ConcurrentHashMap.newKeySet<String>()   // peer ids that passed Hello
    private val droppedOnce = ConcurrentHashMap.newKeySet<String>()   // links already dropped for a missing Hello
    @Volatile private var pairingUntil = 0L
    private var kaN = 0
    private var lastHotspotOn = false
    private val pairExec = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun start(ctx: Context) {
        app = ctx.applicationContext
        CallRegistry.init(app)
        VoiceGate.init(app)
        Api.refreshSupportTable(app)          // newest phone tiers from api.tosbisim.com (bundled copy as fallback)
        if (pinned() == null) openPairing(FIRST_PAIRING_WINDOW_MS)
        ble = BleLink(app, ::onFrame, ::onLink)
        ble.onServiceReady = { askForHello() }
        ble.start()
        val adapter = (app.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        audio = AudioBridge(adapter, ble).also { it.start() }
        RemoteLink.start(app, { bytes -> main.post { runCatching { onPeerFrame(Peer.Internet, bytes) }.onFailure { Log.e(TAG, "remote frame failed: $it") } } }) { present, wasThere ->
            main.post {
                // A room blip mid-call doesn't touch the call's audio (TURN carries it); it ends with the call.
                if (!present) { trusted.remove(Peer.Internet.id); if (CallRegistry.snapshot().none { it.state != CallEvent.State.ENDED }) RemoteCall.close("iPhone left the room") }
                // The iPhone was already in the room when we (re)joined: it said Hello to an earlier us. A joining
                // iPhone says Hello by itself, so no request then (that doubled every Hello, 17 Oct).
                else if (wasThere && Peer.Internet.id !in trusted && pinned() != null) error(Peer.Internet, 0, Error.Code.HELLO_REQUIRED, "host restarted")
            }
        }
        MobileData.watch(app) { main.removeCallbacks(pushStatus); main.postDelayed(pushStatus, 800) }
        Updater.start(app) { main.removeCallbacks(pushStatus); main.postDelayed(pushStatus, 500) }

        // Hotspot on/off, connected clients and data usage -> push status to the client (debounced).
        val statusChanged = {
            val hsOn = Hotspot.isOn(app)
            if (hsOn != lastHotspotOn) { lastHotspotOn = hsOn; Box.hotspotChanged(hsOn); HostWifi.evaluate("hotspot ${if (hsOn) "on" else "off"}") }
            main.removeCallbacks(pushStatus); main.postDelayed(pushStatus, 250); Unit   // widgets: as fast as it's sane
        }
        Box.start(app, statusChanged)
        // Battery level / charging changes reach the iPhone (app and widget) right away, not with the next other update.
        var lastBattery = -1 to false
        app.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val plugged = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
                if ((level to plugged) != lastBattery) { lastBattery = level to plugged; statusChanged() }
            }
        }, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        DataUsage.init(app, statusChanged)
        Hotspot.watch(app, statusChanged)
        HostWifi.start(app, near = ::bleNear, inCall = { CallRegistry.snapshot().any { it.state != CallEvent.State.ENDED } },
            paired = { pinned() != null }, hotspotOn = { Hotspot.isOn(app) }, changed = statusChanged)
        // Pairing window open: accept Bluetooth pairing by ourselves (headless hosts can't tap "Pair"). The Hello pin
        // still decides who is served; outside the window Android handles requests as usual.
        app.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                if (!pairingOpen()) return
                val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                val mac = dev.address
                pairExec.execute { RootBt.confirm(app, mac) }
            }
        }, android.content.IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply { priority = 1000 })
        Sims.protectNewSims(app)
        Sims.log(app)
        Sims.watch(app) { Sims.protectNewSims(app); Sims.log(app); statusChanged() }
        main.postDelayed(keepAlive, KEEPALIVE_MS)
        main.postDelayed({ guardSmsRole() }, 20_000)   // after the daemon is up (service.sh claims it 90 s after boot too)
        Log.i(TAG, "relay up; pinned client=${pinned() ?: "none"}")
    }

    /** For the setup screen: paired client model (null = not paired), connected now, pairing window open. */
    fun pairedClient(): String? = pinned()?.let {
        app.getSharedPreferences("relay", Context.MODE_PRIVATE).getString("client_model", null) ?: "iPhone"
    }
    /** The paired client is here on Bluetooth (said Hello on a subscribed link). */
    private fun bleNear(): Boolean = ::ble.isInitialized && ble.subscribers().any { it.address in trusted }

    fun clientConnected(): Boolean = ::ble.isInitialized && ble.subscribers().any { it.address in trusted } ||
        (RemoteLink.peerPresent && Peer.Internet.id in trusted)

    /** Remote link up/down: the client's Host tab shows it. */
    fun remoteChanged() = main.post(pushStatus)
    fun pairingOpen(): Boolean = SystemClock.elapsedRealtime() < pairingUntil

    fun openPairing(ms: Long = PAIRING_WINDOW_MS) {
        pairingUntil = SystemClock.elapsedRealtime() + ms
        Log.i(TAG, "pairing window open for ${ms / 1000} s")
    }

    // ------------------------------------------------------------- host -> client

    fun callEvent(e: CallEvent) {
        // Fast BLE link while any call is ringing/dialing/active (alert latency + audio), relaxed when idle.
        if (::ble.isInitialized) {
            val othersLive = CallRegistry.snapshot().any { it.callId != e.callId && it.state != CallEvent.State.ENDED }
            ble.setCallPriority(e.state != CallEvent.State.ENDED || othersLive)
        }
        Log.i(TAG, "call ${e.callId.take(8)} ${e.state} incoming=${e.incoming} slot=${e.slot} cause=${e.cause}")
        if (e.state == CallEvent.State.ENDED && CallRegistry.snapshot().none { it.callId != e.callId && it.state != CallEvent.State.ENDED }) {
            RemoteCall.close("call ended")
            main.post { HostWifi.evaluate("call ended") }
        }
        broadcast(Envelope.newBuilder().setCallEvent(e))
    }

    fun smsReceived(m: SmsReceived) = broadcast(Envelope.newBuilder().setSmsReceived(m))

    fun smsSendResult(r: SmsSendResult) = broadcast(Envelope.newBuilder().setSmsSendResult(r))

    /** Bluetooth clients when any is serving, else the remote room. */
    private fun targets(): List<Peer> {
        if (!::ble.isInitialized) return emptyList()
        val viaBle = ble.subscribers().filter { it.address in trusted }.map { Peer.Ble(it, ble) }
        if (viaBle.isNotEmpty()) return viaBle
        return if (RemoteLink.peerPresent && Peer.Internet.id in trusted) listOf(Peer.Internet) else emptyList()
    }

    private fun broadcast(b: Envelope.Builder) {
        val targets = targets()
        if (b.bodyCase == Envelope.BodyCase.HOST_STATUS && targets.isNotEmpty()) Updater.informed()
        if (targets.isEmpty()) Log.i(TAG, "no client connected; ${b.bodyCase} not sent (SMS stay queued)")
        targets.forEach { send(it, b) }
    }

    private fun send(p: Peer, b: Envelope.Builder, replyTo: Int = 0) {
        val bytes = b.setSeq(seq.incrementAndGet()).setReplyTo(replyTo).build().toByteArray()
        if (bytes.size > Framing.MAX_FRAME) { Log.e(TAG, "${b.bodyCase} is ${bytes.size} bytes: too big to send, dropped"); return }
        runCatching { p.send(bytes) }.onFailure { Log.e(TAG, "send ${b.bodyCase} to ${p.id} failed: $it") }
    }

    // ------------------------------------------------------------- client -> host

    private fun onLink(d: BluetoothDevice, up: Boolean) {
        if (!up) { trusted.remove(d.address); Log.i(TAG, "client ${d.address} gone"); main.post { HostWifi.evaluate("Bluetooth gone") } }
        else Log.i(TAG, "client ${d.address} subscribed; waiting for Hello")
    }

    /** BLE frames arrive on binder threads: handle them on main, and never let one take the process down. */
    private fun onFrame(dev: BluetoothDevice, bytes: ByteArray) {
        main.post { runCatching { onPeerFrame(Peer.Ble(dev, ble), bytes) }.onFailure { Log.e(TAG, "frame from ${dev.address} failed: $it") } }
    }

    private fun onPeerFrame(d: Peer, bytes: ByteArray) {
        val env = runCatching { Envelope.parseFrom(bytes) }.getOrElse { Log.w(TAG, "bad frame: $it"); return }
        if (env.bodyCase == Envelope.BodyCase.HELLO) return hello(d, env)
        if (d.id !in trusted) {
            // A paired host whose app restarted while the link stayed up: ask for a fresh Hello instead of
            // rejecting (the client would otherwise sit on "not paired" until restarted, seen 16 Oct).
            if (pinned() != null) return error(d, env.seq, Error.Code.HELLO_REQUIRED, "say hello first")
            return reject(d, env.seq)
        }
        // Test rig running (adb override): the real client's switches would fight the scenario.
        if (HostWifi.clientOverride != null && env.bodyCase in setOf(Envelope.BodyCase.SET_HOTSPOT,
                Envelope.BodyCase.SET_HOST_WIFI, Envelope.BodyCase.SET_MOBILE_DATA))
            return Log.i(TAG, "rig running: ${env.bodyCase} from the client ignored").let { }
        when (env.bodyCase) {
            Envelope.BodyCase.KEEP_ALIVE -> Log.i(TAG, "keep-alive ack n=${env.keepAlive.n}")
            // Basic mode (no measured call audio yet): TosbiSim stays out of calls, except the guided call test.
            // A call answered without relayed audio would play through the host itself (18 Oct).
            Envelope.BodyCase.CALL_ACTION ->
                if (env.callAction.action == dev.simrelay.protocol.v1.CallAction.Action.ANSWER && !VoiceGate.fullVoice)
                    error(d, env.seq, Error.Code.BAD_REQUEST, "no call audio on this host yet: run the call test")
                else CallRegistry.action(env.callAction)?.let { error(d, env.seq, Error.Code.NO_SUCH_CALL, it) }
            Envelope.BodyCase.DIAL ->
                if (!VoiceGate.fullVoice && !env.dial.voiceTest)
                    error(d, env.seq, Error.Code.BAD_REQUEST, "no call audio on this host yet: run the call test")
                else if (env.dial.voiceTest && !Daemon.hasProfile())   // no audio routes: a test call would unsilence the host
                    error(d, env.seq, Error.Code.BAD_REQUEST, "this host has no audio profile: the call test can't run")
                else CallRegistry.dial(env.dial)?.let { error(d, env.seq, Error.Code.BAD_REQUEST, it) }
            Envelope.BodyCase.SMS_ACK -> { SmsStore.ack(app, env.smsAck.id); Log.i(TAG, "SMS ${env.smsAck.id.take(8)} acked") }
            Envelope.BodyCase.SMS_SEND -> SmsSender.send(app, env.smsSend)
            Envelope.BodyCase.SET_HOTSPOT_CONFIG -> Hotspot.configure(app, env.setHotspotConfig.config) { ok, detail ->
                if (!ok) error(d, env.seq, Error.Code.BAD_REQUEST, "hotspot: $detail")
                main.postDelayed({ broadcast(Envelope.newBuilder().setHostStatus(status())) }, 1500)
            }
            Envelope.BodyCase.SET_SIM -> {
                Sims.apply(app, env.setSim)?.let { error(d, env.seq, Error.Code.BAD_REQUEST, "SIM: $it") }
                // Switching a SIM on/off or moving data takes a few seconds to settle.
                main.postDelayed(pushStatus, 1500); main.postDelayed(pushStatus, 6000)
            }
            Envelope.BodyCase.SET_BOX -> {
                if (env.setBox.hasChargeLimit()) Box.setChargeLimit(env.setBox.chargeLimit)
                if (env.setBox.hasOn()) Box.set(env.setBox.on)
                main.postDelayed(pushStatus, 1500)
            }
            Envelope.BodyCase.UNLOCK_SIM -> Sims.unlock(app, env.unlockSim) { r ->
                main.post {
                    send(d, Envelope.newBuilder().setUnlockSimResult(r), env.seq)
                    main.postDelayed(pushStatus, 2000); main.postDelayed(pushStatus, 8000)   // SIM loads, then registers
                }
            }
            Envelope.BodyCase.VOICE_TEST_CHECK -> VoiceGate.check { r ->
                main.post { send(d, Envelope.newBuilder().setVoiceTestHostResult(r), env.seq) }
            }
            Envelope.BodyCase.VOICE_TEST_VERDICT -> {
                VoiceGate.verdict(env.voiceTestVerdict)?.let { error(d, env.seq, Error.Code.BAD_REQUEST, "call test: $it") }
                main.post(pushStatus)
            }
            Envelope.BodyCase.SET_HOTSPOT -> {
                // Starting the hotspot can take the host off Wi-Fi, which may carry this internet call (18 Oct).
                if (env.setHotspot.on && !bleNear() && CallRegistry.snapshot().any { it.state != CallEvent.State.ENDED })
                    return error(d, env.seq, Error.Code.BAD_REQUEST, "hotspot: after the call")
                Hotspot.set(app, env.setHotspot.on) { ok, detail ->
                    if (!ok) error(d, env.seq, Error.Code.INTERNAL, "hotspot: $detail")
                    // The AP state change itself pushes status (Hotspot.watch); this catches a settle without one.
                    main.postDelayed(pushStatus, 1500)
                }
                main.post(pushStatus)                   // "Starting…" / off right away, not after the AP settles
                // A start Android accepted can still die before the AP is up (no state change then): end "Starting…"
                // as soon as its 15 s window closes.
                if (env.setHotspot.on) main.postDelayed(pushStatus, 15_500)
            }
            Envelope.BodyCase.SET_HOST_WIFI -> HostWifi.setUseWhenAway(env.setHostWifi.useWhenAway, remote = d !is Peer.Ble)
            // Network names and passwords travel over Bluetooth only (18 Oct).
            Envelope.BodyCase.WIFI_SCAN, Envelope.BodyCase.WIFI_ADD, Envelope.BodyCase.WIFI_FORGET -> {
                if (d !is Peer.Ble) return error(d, env.seq, Error.Code.BAD_REQUEST, "Wi-Fi networks: over Bluetooth only")
                when (env.bodyCase) {
                    Envelope.BodyCase.WIFI_SCAN -> HostWifi.scan { r, why ->
                        if (r != null) send(d, Envelope.newBuilder().setWifiScanResult(r), env.seq)
                        else error(d, env.seq, Error.Code.BAD_REQUEST, "Wi-Fi scan: $why")
                    }
                    Envelope.BodyCase.WIFI_ADD -> HostWifi.add(env.wifiAdd.ssid, env.wifiAdd.passphrase) { r ->
                        send(d, Envelope.newBuilder().setWifiAddResult(r), env.seq); main.post(pushStatus)
                    }
                    else -> HostWifi.forget(env.wifiForget.id) { main.post(pushStatus) }
                }
            }
            Envelope.BodyCase.REMOTE_SETUP -> {
                // The key only ever travels over Bluetooth (the encrypted, bonded link next to the host); turning
                // remote mode off is fine from anywhere.
                if (d !is Peer.Ble && env.remoteSetup.enabled) return error(d, env.seq, Error.Code.BAD_REQUEST, "remote setup over Bluetooth only")
                RemoteLink.configure(env.remoteSetup.enabled, env.remoteSetup.key.toByteArray())
                main.postDelayed(pushStatus, 500)
            }
            Envelope.BodyCase.UPDATE_ACTION -> Updater.action(env.updateAction.action) { why ->
                main.post {
                    if (why != null) error(d, env.seq, Error.Code.BAD_REQUEST, "update: $why")
                    pushStatus.run()
                }
            }
            Envelope.BodyCase.SET_MOBILE_DATA -> {
                // Off over the internet would cut this very link (remote mode runs on the host's mobile data).
                if (!env.setMobileData.on && d is Peer.Internet) return error(d, env.seq, Error.Code.BAD_REQUEST,
                    "mobile data can only be turned off over Bluetooth: it carries this connection")
                if (!MobileData.set(app, env.setMobileData.on)) error(d, env.seq, Error.Code.INTERNAL, "could not change mobile data")
                main.postDelayed(pushStatus, 1500)
            }
            Envelope.BodyCase.REMOTE_AUDIO -> {
                val ra = env.remoteAudio
                val c = RemoteLink.crypto
                if (ra.close || c == null) RemoteCall.close("client closed")
                else RemoteCall.offer(app, c, ra.relayIp, ra.relayPort) { answer ->
                    main.post { send(d, Envelope.newBuilder().setRemoteAudio(answer), env.seq) }
                }
            }
            else -> error(d, env.seq, Error.Code.BAD_REQUEST, "unexpected ${env.bodyCase}")
        }
    }

    private fun hello(d: Peer, env: Envelope) {
        val id = env.hello.installId
        val pin = pinned()
        val windowOpen = SystemClock.elapsedRealtime() < pairingUntil
        when {
            id.isEmpty() -> return reject(d, env.seq)
            pin == id -> {}
            windowOpen && d is Peer.Ble -> {
                app.getSharedPreferences("relay", Context.MODE_PRIVATE).edit().putString("pinned_install_id", id).apply()
                pairingUntil = 0
                Log.i(TAG, "PAIRED with client ${env.hello.deviceModel} install_id=${id.take(8)}")
            }
            else -> return reject(d, env.seq)
        }
        trusted.add(d.id)
        if (d is Peer.Ble) HostWifi.evaluate("Bluetooth up")
        app.getSharedPreferences("relay", Context.MODE_PRIVATE).edit().putString("client_model", env.hello.deviceModel).apply()
        Log.i(TAG, "Hello from ${env.hello.deviceModel} app ${env.hello.appVersion} (protocol ${env.hello.protocolVersion}) via ${if (d is Peer.Ble) "Bluetooth" else "internet"}")
        send(d, Envelope.newBuilder().setHello(Hello.newBuilder()
            .setProtocolVersion(PROTOCOL_VERSION).setAppVersion(BuildConfigInfo.version(app))
            .setDeviceModel("${Build.MANUFACTURER} ${Build.MODEL}")), env.seq)
        send(d, Envelope.newBuilder().setHostStatus(status()))
        Updater.informed()
        CallRegistry.snapshot().forEach { send(d, Envelope.newBuilder().setCallEvent(it)) }
        SmsStore.pending(app).forEach { send(d, Envelope.newBuilder().setSmsReceived(it)) }
    }

    private fun reject(d: Peer, replyTo: Int) {
        Log.w(TAG, "rejecting ${d.id}: not the paired client")
        error(d, replyTo, Error.Code.NOT_PAIRED, "not paired; open the pairing window on the host")
        main.postDelayed({ d.drop() }, 500)
    }

    private fun error(d: Peer, replyTo: Int, code: Error.Code, detail: String) {
        Log.w(TAG, "error to ${d.id}: $code $detail")
        send(d, Envelope.newBuilder().setError(Error.newBuilder().setCode(code).setDetail(detail)), replyTo)
    }

    // The setup screen can ask before the service has started (fresh install: crashed on the Redmi, 18 Oct).
    private fun pinned(): String? = if (!::app.isInitialized) null else
        app.getSharedPreferences("relay", Context.MODE_PRIVATE).getString("pinned_install_id", null)

    /**
     * The host must stay the default SMS app, or incoming SMS go to another app and never reach the client (Redmi,
     * 19 Oct: Google Messages had taken the role back after the SIM went in; "Hello" from the S20 FE was never relayed).
     * Checked at start and every keep-alive; taken back as root when lost.
     */
    private fun guardSmsRole() {
        val rm = app.getSystemService(android.app.role.RoleManager::class.java) ?: return
        if (rm.isRoleHeld(android.app.role.RoleManager.ROLE_SMS)) return
        Log.w(TAG, "not the default SMS app any more: taking the role back")
        Daemon.role(android.app.role.RoleManager.ROLE_SMS, app.packageName)
    }

    private fun uplink(sims: List<Sim>): Uplink {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = runCatching { cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
        val kind = when {
            caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) -> Uplink.Kind.NONE
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> Uplink.Kind.WIFI
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> Uplink.Kind.MOBILE
            else -> Uplink.Kind.OTHER
        }
        val name = when (kind) {
            Uplink.Kind.WIFI -> HostWifi.connectedSsid
            Uplink.Kind.MOBILE -> sims.firstOrNull { it.defaultData }?.operatorName.orEmpty()
            else -> ""
        }
        return Uplink.newBuilder().setKind(kind).setName(name).build()
    }

    private val pushStatus = Runnable { broadcast(Envelope.newBuilder().setHostStatus(status())) }

    /**
     * This process may be new while a client's link stayed up (app update or crash): the client doesn't know
     * we forgot its Hello, so ask for one rather than wait for it to speak (seen 16 Oct). Sent once the GATT
     * service is up and again with every keep-alive until the client answers.
     */
    private fun askForHello() = ble.connectedDevices().filter { it.address !in trusted }.forEach { d ->
        Log.i(TAG, "link ${d.address} is up without a Hello: asking for one")
        error(Peer.Ble(d, ble), 0, Error.Code.HELLO_REQUIRED, "host restarted")
        // After an app restart the iPhone's subscription belongs to the old process: our request may never reach it
        // (18 Oct, after a self-update: 3 min of silence). No Hello in 5 s -> drop the link; it reconnects at once.
        if (!droppedOnce.add(d.address)) return@forEach              // once per link after a restart, not every minute
        main.postDelayed({
            // Never during first pairing: a new iPhone may be in iOS's pairing dialog.
            if (d.address !in trusted && !pairingOpen() && d.bondState != BluetoothDevice.BOND_BONDING &&
                ble.connectedDevices().any { it.address == d.address }) {
                Log.i(TAG, "no Hello from ${d.address} after 5 s: dropping the link so it reconnects fresh")
                ble.disconnect(d)
                // The iPhone owns the link (it's the central): cancelConnection rarely drops it, and a locked iPhone's
                // app then hears nothing for minutes (19 Oct, after a self-update: 170 s+, reproduced). Switching our
                // Bluetooth off and on is a real disconnect, which iOS always delivers: Hello within 3 s. Never in a call.
                main.postDelayed({
                    if (d.address !in trusted && ble.connectedDevices().any { it.address == d.address } &&
                        CallRegistry.snapshot().none { it.state != CallEvent.State.ENDED }) {
                        Log.i(TAG, "link ${d.address} still up without a Hello: resetting Bluetooth so the iPhone reconnects")
                        Root.background("svc bluetooth disable; sleep 2; svc bluetooth enable")
                    }
                }, 3000)
            }
        }, 5000)
    }

    private val keepAlive = object : Runnable {
        override fun run() {
            askForHello()
            HostWifi.evaluate("keep-alive")
            guardSmsRole()
            broadcast(Envelope.newBuilder().setKeepAlive(KeepAlive.newBuilder().setN(++kaN)))
            main.postDelayed(this, KEEPALIVE_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun status(): HostStatus {
        val bm = app.getSystemService(BatteryManager::class.java)
        val tm = app.getSystemService(TelephonyManager::class.java)
        val b = HostStatus.newBuilder()
            .setBatteryPercent(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .setCharging(bm.isCharging)
            .setSignalLevel(tm.signalStrength?.level ?: 0)
            .setVoiceTier(if (VoiceGate.fullVoice) HostStatus.VoiceTier.FULL_VOICE else HostStatus.VoiceTier.BASIC)
            .setVoice(VoiceGate.status())
        val hs = Hotspot.info(app)
        b.setHotspotOn(hs.on).setHotspot(hs)
        b.addAllSims(Sims.status(app))
        b.setBox(dev.simrelay.protocol.v1.Box.newBuilder()
            .setOn(Box.on).setSuspended(Box.suspended).setSuspendedUntilMs(if (Box.suspended) Box.suspendedUntil else 0)
            .setChargeLimit(Box.chargeLimit).setChargeLimitSupported(Box.chargeSupported)
            .setCallScreenHidden(Box.callScreenHidden()))
        b.setRemote(Remote.newBuilder().setEnabled(RemoteLink.enabled).setOnline(RemoteLink.online))
        b.setMobileData(MobileData.isOn(app))
        b.setHostWifi(HostWifi.status())
        b.setUplink(uplink(b.simsList))
        b.setUpdate(Updater.status())
        return b.build()
    }
}

object BuildConfigInfo {
    fun version(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
}
