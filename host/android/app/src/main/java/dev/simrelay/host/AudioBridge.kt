// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.util.Log
import java.io.DataInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Closes a daemon audio socket so both ends notice at once. close() alone leaves the socket open while one of our
 * threads is blocked reading it (the read holds it): after a Bluetooth call the daemon kept the dead client until the
 * next call's first frame woke that read (1 Nov 2026). shutdown wakes our reader and tells the daemon now.
 */
internal fun LocalSocket.closeNow() {
    runCatching { shutdownInput() }
    runCatching { shutdownOutput() }
    runCatching { close() }
}

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
 *
 * On a host whose Bluetooth sends in small radio packets the channel is lean from its first frame ([LeanAudio]).
 */
@SuppressLint("MissingPermission")
class AudioBridge(private val adapter: BluetoothAdapter, private val ble: BleLink) {
    companion object {
        private const val TAG = "SimRelayHost"; private const val MAX = 512
        private const val BACKLOG = 10            // 100 ms of caller audio waiting for the link, then the oldest goes
    }

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
            if (!ble.allowed()) { Thread.sleep(2000); continue }       // no Bluetooth permission yet: self-repair grants it
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
        CallAudioCheck.bridgeUp()
        val alive = AtomicBoolean(true)
        val ds = daemon

        // daemon -> L2CAP: each datagram becomes one length-prefixed frame, and every frame waiting goes out in ONE
        // write. On Android one write is one L2CAP packet: a link that carries one packet per connection event (11.25
        // ms) can't take 100 single-frame writes a second. The write then blocked, the daemon's socket filled and it
        // dropped what didn't fit, silently: a client got 88 frames a second, steadily, through whole calls (a Mi 11
        // Pro host with a Meizu client, 6 Nov 2026). The reader keeps the daemon drained; the backlog stays short.
        val q = ArrayDeque<ByteArray>()
        val qLock = ReentrantLock()
        val ready = qLock.newCondition()
        val st = IntArray(7)                         // frames in, frames out, writes, dropped, slowest write (ms), bytes out, ms blocked
        // Lean ([LeanAudio]): only on a host whose link is one of small radio packets, from this channel's first frame
        val lean = AtomicBoolean(false)
        val leanAsked = AtomicBoolean(false)
        val goLean = { why: String ->
            Log.w(TAG, "audio to the phone: $why: asking the daemon for ${LeanAudio.FRAME}-byte frames, two a write")
            Daemon.frameMax(LeanAudio.FRAME) { ok ->
                if (ok && alive.get()) { lean.set(true); CallAudioCheck.leanOn() }
                else if (!ok) Log.w(TAG, "audio to the phone: the daemon doesn't cap frames, staying as it is")
            }
        }
        val down = Thread({
            val din = ds.inputStream
            val buf = ByteArray(MAX)
            var stallLogged = 0L
            var leanLooked = 0L
            try {
                while (alive.get()) {
                    val n = din.read(buf)
                    if (n <= 0) break
                    val framed = ByteArray(n + 2)
                    framed[0] = (n and 0xff).toByte()
                    framed[1] = (n shr 8).toByte()
                    System.arraycopy(buf, 0, framed, 2, n)
                    var dropped = false
                    qLock.lock()
                    try {
                        q.addLast(framed); st[0]++; CallAudioCheck.frameIn()
                        while (q.size > BACKLOG) { q.removeFirst(); st[3]++; dropped = true; CallAudioCheck.dropped() }
                        ready.signal()
                    } finally { qLock.unlock() }
                    val now = SystemClock.elapsedRealtime()
                    // Asked at the first frame (the daemon has this client by then: a cap asked for earlier could
                    // meet the daemon before the client did, and a new client starts without one), then once a second:
                    // the host may learn what its link is during a call
                    if (!leanAsked.get() && now - leanLooked >= 1000) {
                        leanLooked = now
                        LeanAudio.why(LinkRadio.smallPackets)?.let { if (leanAsked.compareAndSet(false, true)) goLean(it) }
                    }
                    // A write that never returns logs nothing itself: say so from here (a client got no caller audio
                    // for 15 s while its own audio still reached the host, 6 Nov 2026)
                    if (dropped && now - stallLogged > 5000) {
                        stallLogged = now
                        // With what the radios were doing: a stall's cause is one of them, and the line alone never said
                        Log.w(TAG, "audio to the phone: Bluetooth isn't taking it, dropping the oldest (${runCatching { CallAudioCheck.radio() }.getOrDefault("")})")
                    }
                }
            } catch (_: Exception) {} finally { alive.set(false); qLock.lock(); try { ready.signalAll() } finally { qLock.unlock() } }
        }, "audio-down").apply { isDaemon = true; start() }
        val send = Thread({
            val out = l2cap.outputStream
            val maxTx = runCatching { l2cap.maxTransmitPacketSize }.getOrDefault(0).let { if (it in 64..4096) it else MAX + 2 }
            val buf = ByteArray(maxOf(maxTx, MAX + 2))
            var nextLog = SystemClock.elapsedRealtime() + 5000
            try {
                while (alive.get()) {
                    var n = 0; var frames = 0
                    val small = lean.get()
                    qLock.lock()
                    try {
                        while (q.isEmpty() && alive.get()) ready.await(200, TimeUnit.MILLISECONDS)
                        // Lean: two frames a write, never more than two radio packets' worth. A frame from before the
                        // cap is larger and goes alone.
                        if (small) {
                            var left = TimeUnit.MILLISECONDS.toNanos(LeanAudio.PAIR_WAIT_MS)
                            while (q.size < 2 && alive.get() && left > 0) left = ready.awaitNanos(left)
                        }
                        val room = if (small) LeanAudio.WRITE else maxTx
                        while (q.isNotEmpty() && (n == 0 || n + q.first().size <= room)) {
                            val f = q.removeFirst(); System.arraycopy(f, 0, buf, n, f.size); n += f.size; frames++
                        }
                    } finally { qLock.unlock() }
                    if (n == 0) continue
                    val t0 = SystemClock.elapsedRealtime()
                    out.write(buf, 0, n)
                    val ms = (SystemClock.elapsedRealtime() - t0).toInt()
                    CallAudioCheck.wrote(frames, n, ms.toLong())
                    qLock.lock()
                    try {
                        st[1] += frames; st[2]++; if (ms > st[4]) st[4] = ms
                        st[5] += n; if (ms >= CallAudioCheck.BLOCKED_MS) st[6] += ms
                        if (t0 > nextLog) {
                            nextLog = t0 + 5000
                            // Only when the link lagged (frames shared a write, a drop, a slow write): healthy calls
                            // already log every 5 s in the daemon's line
                            if (st[3] > 0 || st[2] < st[1] || st[4] >= 40)
                                Log.i(TAG, "audio to the phone 5s: frames=${st[1]}/${st[0]} writes=${st[2]} dropped=${st[3]} slowest write=${st[4]} ms " +
                                    "bytes=${st[5]} blocked=${st[6]} ms (max $maxTx B${if (small) ", lean" else ""})")
                            st.fill(0)
                        }
                    } finally { qLock.unlock() }
                }
            } catch (_: Exception) {} finally { alive.set(false) }
        }, "audio-send").apply { isDaemon = true; start() }

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
            qLock.lock(); try { ready.signalAll() } finally { qLock.unlock() }
            runCatching { l2cap.close() }
            ds.closeNow()
            down.join(500); send.join(500)
            // The cap was this channel's: whatever carries the call next starts without it (the daemon lifts it for a
            // new audio client by itself; this covers the answer to a late request)
            if (leanAsked.get()) Daemon.frameMax(0)
            Log.i(TAG, "audio bridge down")
        }
    }

    fun stop() {
        acceptThread = null
        runCatching { server?.close() }
    }
}
