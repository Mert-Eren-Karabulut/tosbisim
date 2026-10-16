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
import dev.simrelay.protocol.v1.KeepAlive
import dev.simrelay.protocol.v1.SmsReceived
import dev.simrelay.protocol.v1.SmsSendResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Protocol v1 session (protocol/README.md) on top of BleLink.
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
    private val trusted = ConcurrentHashMap.newKeySet<String>()   // device addresses that passed Hello
    @Volatile private var pairingUntil = 0L
    private var kaN = 0

    fun start(ctx: Context) {
        app = ctx.applicationContext
        CallRegistry.init(app)
        VoiceGate.init(app)
        if (pinned() == null) openPairing(FIRST_PAIRING_WINDOW_MS)
        ble = BleLink(app, ::onFrame, ::onLink)
        ble.onServiceReady = { askForHello() }
        ble.start()
        val adapter = (app.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        audio = AudioBridge(adapter, ble).also { it.start() }

        // Hotspot on/off, connected clients and data usage -> push status to the client (debounced).
        val statusChanged = {
            Box.hotspotChanged(Hotspot.info(app).on)
            main.removeCallbacks(pushStatus); main.postDelayed(pushStatus, 800); Unit
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
        Sims.protectNewSims(app)
        Sims.log(app)
        Sims.watch(app) { Sims.protectNewSims(app); Sims.log(app); statusChanged() }
        main.postDelayed(keepAlive, KEEPALIVE_MS)
        Log.i(TAG, "relay up; pinned client=${pinned() ?: "none"}")
    }

    /** For the setup screen: paired client model (null = not paired), connected now, pairing window open. */
    fun pairedClient(): String? = pinned()?.let {
        app.getSharedPreferences("relay", Context.MODE_PRIVATE).getString("client_model", null) ?: "iPhone"
    }
    fun clientConnected(): Boolean = ::ble.isInitialized && ble.subscribers().any { it.address in trusted }
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
        broadcast(Envelope.newBuilder().setCallEvent(e))
    }

    fun smsReceived(m: SmsReceived) = broadcast(Envelope.newBuilder().setSmsReceived(m))

    fun smsSendResult(r: SmsSendResult) = broadcast(Envelope.newBuilder().setSmsSendResult(r))

    private fun broadcast(b: Envelope.Builder) {
        if (!::ble.isInitialized) return
        val targets = ble.subscribers().filter { it.address in trusted }
        if (targets.isEmpty()) Log.i(TAG, "no client connected; ${b.bodyCase} not sent (SMS stay queued)")
        targets.forEach { send(it, b) }
    }

    private fun send(d: BluetoothDevice, b: Envelope.Builder, replyTo: Int = 0) =
        ble.send(d, b.setSeq(seq.incrementAndGet()).setReplyTo(replyTo).build().toByteArray())

    // ------------------------------------------------------------- client -> host

    private fun onLink(d: BluetoothDevice, up: Boolean) {
        if (!up) { trusted.remove(d.address); Log.i(TAG, "client ${d.address} gone") }
        else Log.i(TAG, "client ${d.address} subscribed; waiting for Hello")
    }

    private fun onFrame(d: BluetoothDevice, bytes: ByteArray) {
        val env = runCatching { Envelope.parseFrom(bytes) }.getOrElse { Log.w(TAG, "bad frame: $it"); return }
        if (env.bodyCase == Envelope.BodyCase.HELLO) return hello(d, env)
        if (d.address !in trusted) {
            // A paired host whose app restarted while the link stayed up: ask for a fresh Hello instead of
            // rejecting (the client would otherwise sit on "not paired" until restarted, seen 16 Oct).
            if (pinned() != null) return error(d, env.seq, Error.Code.HELLO_REQUIRED, "say hello first")
            return reject(d, env.seq)
        }
        when (env.bodyCase) {
            Envelope.BodyCase.KEEP_ALIVE -> Log.i(TAG, "keep-alive ack n=${env.keepAlive.n}")
            Envelope.BodyCase.CALL_ACTION -> CallRegistry.action(env.callAction)?.let { error(d, env.seq, Error.Code.NO_SUCH_CALL, it) }
            Envelope.BodyCase.DIAL -> CallRegistry.dial(env.dial)?.let { error(d, env.seq, Error.Code.BAD_REQUEST, it) }
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
            Envelope.BodyCase.SET_HOTSPOT -> Hotspot.set(app, env.setHotspot.on) { ok, detail ->
                if (!ok) error(d, env.seq, Error.Code.INTERNAL, "hotspot: $detail")
                // The state settles a moment after the callback; push fresh status to the client.
                main.postDelayed({ broadcast(Envelope.newBuilder().setHostStatus(status())) }, 1500)
            }
            else -> error(d, env.seq, Error.Code.BAD_REQUEST, "unexpected ${env.bodyCase}")
        }
    }

    private fun hello(d: BluetoothDevice, env: Envelope) {
        val id = env.hello.installId
        val pin = pinned()
        val windowOpen = SystemClock.elapsedRealtime() < pairingUntil
        when {
            id.isEmpty() -> return reject(d, env.seq)
            pin == id -> {}
            windowOpen -> {
                app.getSharedPreferences("relay", Context.MODE_PRIVATE).edit().putString("pinned_install_id", id).apply()
                pairingUntil = 0
                Log.i(TAG, "PAIRED with client ${env.hello.deviceModel} install_id=${id.take(8)}")
            }
            else -> return reject(d, env.seq)
        }
        trusted.add(d.address)
        app.getSharedPreferences("relay", Context.MODE_PRIVATE).edit().putString("client_model", env.hello.deviceModel).apply()
        Log.i(TAG, "Hello from ${env.hello.deviceModel} app ${env.hello.appVersion} (protocol ${env.hello.protocolVersion})")
        send(d, Envelope.newBuilder().setHello(Hello.newBuilder()
            .setProtocolVersion(PROTOCOL_VERSION).setAppVersion(BuildConfigInfo.version(app))
            .setDeviceModel("${Build.MANUFACTURER} ${Build.MODEL}")), env.seq)
        send(d, Envelope.newBuilder().setHostStatus(status()))
        CallRegistry.snapshot().forEach { send(d, Envelope.newBuilder().setCallEvent(it)) }
        SmsStore.pending(app).forEach { send(d, Envelope.newBuilder().setSmsReceived(it)) }
    }

    private fun reject(d: BluetoothDevice, replyTo: Int) {
        Log.w(TAG, "rejecting ${d.address}: not the paired client")
        error(d, replyTo, Error.Code.NOT_PAIRED, "not paired; open the pairing window on the host")
        main.postDelayed({ ble.disconnect(d) }, 500)
    }

    private fun error(d: BluetoothDevice, replyTo: Int, code: Error.Code, detail: String) {
        Log.w(TAG, "error to ${d.address}: $code $detail")
        send(d, Envelope.newBuilder().setError(Error.newBuilder().setCode(code).setDetail(detail)), replyTo)
    }

    private fun pinned(): String? =
        app.getSharedPreferences("relay", Context.MODE_PRIVATE).getString("pinned_install_id", null)

    private val pushStatus = Runnable { broadcast(Envelope.newBuilder().setHostStatus(status())) }

    /**
     * This process may be new while a client's link stayed up (app update or crash): the client doesn't know
     * we forgot its Hello, so ask for one rather than wait for it to speak (seen 16 Oct). Sent once the GATT
     * service is up and again with every keep-alive until the client answers.
     */
    private fun askForHello() = ble.connectedDevices().filter { it.address !in trusted }.forEach { d ->
        Log.i(TAG, "link ${d.address} is up without a Hello: asking for one")
        error(d, 0, Error.Code.HELLO_REQUIRED, "host restarted")
    }

    private val keepAlive = object : Runnable {
        override fun run() {
            askForHello()
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
        return b.build()
    }
}

object BuildConfigInfo {
    fun version(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
}
