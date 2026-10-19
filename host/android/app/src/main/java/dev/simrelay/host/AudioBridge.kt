// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges the audio daemon (simrelayd, unix SEQPACKET "@simrelayd-audio") and the iPhone (BLE L2CAP CoC).
 * The daemon owns the call-audio codec and PCM I/O; this just forwards Opus packets both ways.
 *
 * Wire formats:
 *   daemon socket : one datagram = [u16 seq LE][u8 flags][opus]  (the daemon's packet, verbatim)
 *   L2CAP stream  : [u16 len LE][ that daemon packet ]           (length-prefixed, since CoC is a stream)
 *
 * One L2CAP server channel, its PSM published in the GATT AUDIO_PSM characteristic. The iPhone reads the
 * PSM and connects when it answers a call; on connect we open the daemon socket and run two pump threads.
 */
@SuppressLint("MissingPermission")
class AudioBridge(private val adapter: BluetoothAdapter, private val ble: BleLink) {
    companion object { private const val TAG = "SimRelayHost"; private const val MAX = 512 }

    private var server: BluetoothServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    fun start() {
        val t = Thread({ acceptLoop() }, "audio-accept")
        acceptThread = t
        t.isDaemon = true
        t.start()
    }

    private fun acceptLoop() {
        var fails = 0
        while (acceptThread != null) {
            if (!adapter.isEnabled) { Thread.sleep(2000); continue }   // radio off: wait quietly (BleLink rebuilds on STATE_ON)
            val srv = try {
                adapter.listenUsingL2capChannel()      // secure: needs the bonded, encrypted link
            } catch (e: Exception) {
                // A controller without LE credit-based channels never succeeds: back off to a minute, report once
                fails++
                if (fails == 3) Diag.event("audio_channel_failed", org.json.JSONObject().put("what", e.javaClass.simpleName), withLog = false)
                if (fails <= 3 || fails % 30 == 0) Log.e(TAG, "L2CAP listen failed ($fails): $e")
                Thread.sleep(minOf(60_000L, 2_000L shl minOf(fails - 1, 5))); continue
            }
            fails = 0
            server = srv
            ble.audioPsm = srv.psm
            Log.i(TAG, "audio L2CAP listening psm=${srv.psm}")
            try {
                while (acceptThread != null) {
                    val sock = srv.accept()             // one iPhone audio connection at a time
                    Log.i(TAG, "audio L2CAP connected ${sock.remoteDevice?.address}")
                    bridge(sock)
                    Log.i(TAG, "audio L2CAP closed")
                }
            } catch (e: Exception) {
                Log.i(TAG, "audio accept ended: $e")
            } finally {
                runCatching { srv.close() }
            }
        }
    }

    /** Run until either side closes. Blocks the accept thread for the duration of one call's audio. */
    private fun bridge(l2cap: BluetoothSocket) {
        var daemon: LocalSocket? = null
        try {
            daemon = LocalSocket(LocalSocket.SOCKET_SEQPACKET)
            daemon.connect(LocalSocketAddress("simrelayd-audio", LocalSocketAddress.Namespace.ABSTRACT))
        } catch (e: Exception) {
            Log.e(TAG, "daemon audio socket connect failed: $e")
            runCatching { l2cap.close() }
            return
        }
        Log.i(TAG, "audio bridge up (L2CAP <-> simrelayd)")
        val alive = AtomicBoolean(true)
        val ds = daemon

        // daemon -> L2CAP: each datagram becomes one length-prefixed frame.
        val down = Thread({
            val din = ds.inputStream
            val out = l2cap.outputStream
            val buf = ByteArray(MAX)
            val framed = ByteArray(MAX + 2)
            try {
                while (alive.get()) {
                    val n = din.read(buf)
                    if (n <= 0) break
                    framed[0] = (n and 0xff).toByte()
                    framed[1] = (n shr 8).toByte()
                    System.arraycopy(buf, 0, framed, 2, n)
                    synchronized(out) { out.write(framed, 0, n + 2); out.flush() }
                }
            } catch (_: Exception) {} finally { alive.set(false) }
        }, "audio-down").apply { isDaemon = true; start() }

        // L2CAP -> daemon: de-frame, forward each packet as one datagram.
        try {
            val din = DataInputStream(l2cap.inputStream)
            val dout = ds.outputStream
            val pkt = ByteArray(MAX)
            while (alive.get()) {
                val lo = din.read(); val hi = din.read()
                if (lo < 0 || hi < 0) break
                val len = lo or (hi shl 8)
                if (len <= 0 || len > MAX) break        // desync: drop the channel
                din.readFully(pkt, 0, len)
                dout.write(pkt, 0, len)                 // SEQPACKET: one write = one datagram
            }
        } catch (_: Exception) {
        } finally {
            alive.set(false)
            runCatching { l2cap.close() }
            runCatching { ds.close() }
            down.join(500)
            Log.i(TAG, "audio bridge down")
        }
    }

    fun stop() {
        acceptThread = null
        runCatching { server?.close() }
    }
}
