// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Length-prefixed frames over a byte stream (protocol/README.md): [u16 LE length][payload]. */
object Framing {
    const val MAX_FRAME = 4096

    fun frame(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME) { "frame too large: ${payload.size}" }
        return byteArrayOf((payload.size and 0xff).toByte(), (payload.size shr 8).toByte()) + payload
    }

    class Reader {
        private var buf = ByteArray(0)

        /** Feed received bytes, get every complete frame. Returns null on a corrupt stream. */
        fun feed(bytes: ByteArray): List<ByteArray>? {
            buf += bytes
            val out = ArrayList<ByteArray>()
            while (buf.size >= 2) {
                val len = (buf[0].toInt() and 0xff) or ((buf[1].toInt() and 0xff) shl 8)
                if (len > MAX_FRAME) { buf = ByteArray(0); return null }
                if (buf.size < 2 + len) break
                out += buf.copyOfRange(2, 2 + len)
                buf = buf.copyOfRange(2 + len, buf.size)
            }
            return out
        }
    }
}

/**
 * BLE peripheral side of the control channel (protocol/README.md). Lessons from the M1 spike:
 * restart advertising after a real disconnect; verify DISCONNECTED against the stack (a short duplicate
 * connection produced a false one); persist CCCD state for bonded clients; one notification in flight
 * per device (Android drops the rest), so frames are chunked to MTU-3 and queued.
 */
@SuppressLint("MissingPermission") // runtime permissions are granted by the Magisk module (headless host)
class BleLink(
    private val ctx: Context,
    private val onFrame: (BluetoothDevice, ByteArray) -> Unit,
    private val onLink: (BluetoothDevice, Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "SimRelayHost"
        val SERVICE: UUID = UUID.fromString("0e9c0001-0971-48af-ba0b-2c5c254faec0")
        val CTRL_TX: UUID = UUID.fromString("0e9c0002-0971-48af-ba0b-2c5c254faec0")
        val CTRL_RX: UUID = UUID.fromString("0e9c0003-0971-48af-ba0b-2c5c254faec0")
        val AUDIO_PSM: UUID = UUID.fromString("0e9c0004-0971-48af-ba0b-2c5c254faec0")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val main = Handler(Looper.getMainLooper())
    private val bt = ctx.getSystemService(BluetoothManager::class.java)
    private val prefs = ctx.getSharedPreferences("ble", Context.MODE_PRIVATE)
    private var gatt: BluetoothGattServer? = null
    private lateinit var tx: BluetoothGattCharacteristic
    private val readers = ConcurrentHashMap<String, Framing.Reader>()
    private val mtu = ConcurrentHashMap<String, Int>()
    private val queues = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private val notifyFails = ConcurrentHashMap<String, Int>()
    @Volatile var audioPsm = 0

    /** GATT service + advertising; with the radio off (left off, or mid-toggle) STATE_ON calls this again. */
    fun start() {
        if (!radioWatch) {
            radioWatch = true
            ctx.registerReceiver(radioReceiver, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
        }
        if (bt.adapter?.isEnabled != true) { Log.w(TAG, "Bluetooth is off: GATT service waits for it"); return }
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        tx = BluetoothGattCharacteristic(CTRL_TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED).apply {
            addDescriptor(BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED))
        }
        service.addCharacteristic(tx)
        service.addCharacteristic(BluetoothGattCharacteristic(CTRL_RX, BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED))
        service.addCharacteristic(BluetoothGattCharacteristic(AUDIO_PSM, BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED))
        val server = bt.openGattServer(ctx, callback) ?: run { Log.w(TAG, "GATT server unavailable (radio turning off?)"); return }
        server.addService(service)
        gatt = server
        advertise()
    }

    /**
     * Bluetooth switched off and on (our own link reset after a restart, the user, the stack): Android drops our GATT
     * service and advertising with the radio, and nothing brought them back, so the host stayed deaf until the app
     * restarted (19 Oct). Rebuild on STATE_ON; per-link state is stale after STATE_OFF.
     */
    @Volatile private var radioWatch = false
    private val radioReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            when (i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF -> main.post {
                    Log.i(TAG, "Bluetooth turning off: GATT server down")
                    runCatching { gatt?.close() }; gatt = null
                    readers.clear(); queues.clear(); inFlight.clear(); mtu.clear(); notifyFails.clear()
                }
                android.bluetooth.BluetoothAdapter.STATE_ON -> main.post {
                    if (gatt != null) return@post
                    Log.i(TAG, "Bluetooth on again: rebuilding the GATT service and advertising")
                    start()
                }
            }
        }
    }

    fun stop() {
        runCatching { bt.adapter.bluetoothLeAdvertiser.stopAdvertising(advCallback) }
        gatt?.close()
        gatt = null
    }

    /** Devices the stack reports connected that have subscribed to CTRL_TX (now or in an earlier connection). */
    fun subscribers(): List<BluetoothDevice> =
        bt.getConnectedDevices(BluetoothProfile.GATT).filter { it.address in subscribed() }

    fun send(device: BluetoothDevice, payload: ByteArray) {
        val bytes = Framing.frame(payload)
        // ATT caps an attribute value at 512 bytes: a 514-byte chunk (MTU 517) reached iOS 2 bytes short and broke
        // every message longer than one chunk ("corrupt stream", 18 Oct: the status with update notes).
        val chunk = minOf((mtu[device.address] ?: 23) - 3, 512)
        val q = queues.getOrPut(device.address) { ArrayDeque() }
        synchronized(q) { for (i in bytes.indices step chunk) q.addLast(bytes.copyOfRange(i, minOf(i + chunk, bytes.size))) }
        pump(device)
    }

    fun disconnect(device: BluetoothDevice) { gatt?.cancelConnection(device) }

    /** Links that are already up (they outlive an app restart: the Bluetooth stack keeps the connection). */
    @SuppressLint("MissingPermission")
    fun connectedDevices(): List<BluetoothDevice> = runCatching { bt.getConnectedDevices(BluetoothProfile.GATT_SERVER) }.getOrDefault(emptyList())

    // ---- Connection interval for calls ----
    // As the peripheral, Android can only ask for a faster link through a GATT *client* on the same ACL link
    // (BluetoothGatt.requestConnectionPriority). HIGH asks for ~11.25-15 ms; iOS grants within its own rules
    // (15 ms steps). A short interval cuts audio transit time and burstiness; BALANCED saves battery when idle.
    private val clients = ConcurrentHashMap<String, BluetoothGatt>()
    @Volatile private var wantHigh = false

    fun setCallPriority(high: Boolean) {
        if (wantHigh == high) return
        wantHigh = high
        for (d in subscribers()) applyPriority(d)
    }

    private fun applyPriority(d: BluetoothDevice) {
        val prio = if (wantHigh) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        val existing = clients[d.address]
        if (existing != null) {
            Log.i(TAG, "connection priority ${if (wantHigh) "HIGH" else "BALANCED"} -> ${existing.requestConnectionPriority(prio)}")
            return
        }
        // First use: attach a client role to the existing link; request priority once connected.
        clients[d.address] = d.connectGatt(ctx, false, object : android.bluetooth.BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    val p = if (wantHigh) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
                    Log.i(TAG, "client role up; connection priority ${if (wantHigh) "HIGH" else "BALANCED"} -> ${g.requestConnectionPriority(p)}")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    clients.remove(d.address); g.close()
                }
            }
            // Hidden in the SDK stubs but invoked by the framework (same signature = override at runtime).
            @Suppress("unused")
            fun onConnectionUpdated(g: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
                Log.i(TAG, "BLE connection updated: interval ${interval * 1.25} ms, latency $latency, timeout ${timeout * 10} ms, status $status")
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    private fun pump(device: BluetoothDevice) {
        val q = queues[device.address] ?: return
        synchronized(q) {
            if (inFlight[device.address] == true) return
            val chunk = q.removeFirstOrNull() ?: return
            inFlight[device.address] = true
            val rc = notify(device, chunk)
            if (rc != BluetoothStatusCodes.SUCCESS) {
                val n = (notifyFails[device.address] ?: 0) + 1
                inFlight[device.address] = false
                if (n > 100) {                       // ~5 s: the link is gone; its next Hello replays the state
                    Log.w(TAG, "notify to ${device.address} keeps failing (rc=$rc): dropping ${q.size + 1} queued frames")
                    q.clear(); notifyFails.remove(device.address)
                    return
                }
                if (n == 1 || n % 20 == 0) Log.w(TAG, "notify to ${device.address} rc=$rc, retrying (#$n)")
                notifyFails[device.address] = n
                q.addFirst(chunk)
                main.postDelayed({ pump(device) }, 50)
            } else notifyFails.remove(device.address)
        }
    }

    /** notifyCharacteristicChanged(device, char, confirm, value) is API 33; Android 11-12 set the value first. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun notify(device: BluetoothDevice, chunk: ByteArray): Int? {
        val g = gatt ?: return null
        if (android.os.Build.VERSION.SDK_INT >= 33) return g.notifyCharacteristicChanged(device, tx, false, chunk)
        tx.value = chunk
        return if (g.notifyCharacteristicChanged(device, tx, false)) BluetoothStatusCodes.SUCCESS else -1
    }

    private fun subscribed(): Set<String> = prefs.getStringSet("cccd", emptySet()) ?: emptySet()

    private fun setSubscribed(address: String, on: Boolean) {
        val s = subscribed().toMutableSet()
        if (on) s.add(address) else s.remove(address)
        prefs.edit().putStringSet("cccd", s).apply()
    }

    private fun resetStream(address: String) {
        readers.remove(address)
        if (bt.getConnectedDevices(BluetoothProfile.GATT).none { it.address == address }) mtu.remove(address)
        queues[address]?.let { synchronized(it) { it.clear() } }
        inFlight.remove(address)
    }

    /** Our GATT service is registered: notifications can reach clients from now on. */
    var onServiceReady: (() -> Unit)? = null

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.i(TAG, "GATT service added status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) main.post { onServiceReady?.invoke() }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BLE connected ${device.address} bond=${device.bondState}")
                resetStream(device.address)
                gatt?.setPreferredPhy(device, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED)
                return
            }
            val stillUp = bt.getConnectedDevices(BluetoothProfile.GATT).any { it.address == device.address }
            Log.i(TAG, "BLE disconnected ${device.address} status=$status (stack says ${if (stillUp) "STILL UP, ignored" else "down"})")
            if (stillUp) return
            resetStream(device.address)
            onLink(device, false)
            restartAdvertising()
        }

        // Hidden in the SDK stubs but invoked by the framework: the link's actual connection parameters.
        @Suppress("unused")
        fun onConnectionUpdated(device: BluetoothDevice, interval: Int, latency: Int, timeout: Int, status: Int) {
            Log.i(TAG, "BLE link ${device.address}: interval ${interval * 1.25} ms, latency $latency, timeout ${timeout * 10} ms (status $status)")
        }

        override fun onMtuChanged(device: BluetoothDevice, m: Int) {
            mtu[device.address] = m
            Log.i(TAG, "mtu ${device.address} = $m")
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
                                              prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (d.uuid == CCCD) {
                val on = value.isNotEmpty() && value[0].toInt() and 1 == 1
                setSubscribed(device.address, on)
                Log.i(TAG, "CTRL_TX notify=$on for ${device.address}")
                if (on) { resetStream(device.address); onLink(device, true) }
            }
            if (responseNeeded) gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                 c: BluetoothGattCharacteristic) {
            val v = if (c.uuid == AUDIO_PSM) byteArrayOf((audioPsm and 0xff).toByte(), (audioPsm shr 8).toByte()) else ByteArray(0)
            gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v)
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
                                                  prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (responseNeeded) gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (c.uuid != CTRL_RX) return
            val frames = readers.getOrPut(device.address) { Framing.Reader() }.feed(value)
            if (frames == null) { Log.w(TAG, "corrupt stream from ${device.address}, resetting"); readers.remove(device.address); return }
            frames.forEach { onFrame(device, it) }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "notification to ${device.address} status=$status")
            inFlight[device.address] = false
            pump(device)
        }
    }

    // ---------------------------------------------------------------- advertising

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(s: AdvertiseSettings) { Log.i(TAG, "advertising") }
        override fun onStartFailure(code: Int) { Log.e(TAG, "advertising failed $code") }
    }

    private fun advertise() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val adv = bt.adapter?.bluetoothLeAdvertiser ?: run { Log.w(TAG, "no advertiser: Bluetooth is off"); return }
        adv.startAdvertising(settings,
            AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).build(),
            AdvertiseData.Builder().setIncludeDeviceName(true).build(), advCallback)
    }

    /** Connectable advertising stops once a central connects; restart it after every real disconnect. */
    private fun restartAdvertising() = main.post {
        runCatching { bt.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advCallback) }
        if (gatt != null) advertise()
    }
}
