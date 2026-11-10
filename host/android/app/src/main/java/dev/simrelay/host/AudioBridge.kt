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
 * How many of the daemon's 10 ms frames share one Bluetooth packet from a call's start (1: each goes alone).
 *
 * A MediaTek host starts with two. A Galaxy A13 (MT6769) took every single-frame write at once (slowest write 1 ms,
 * nothing dropped, its capture at 100%), yet its Bluetooth passed 91 to 98 packets a second of the 100 and paused for
 * up to 190 ms: the phone got the caller late and in clumps, 14% of its playback had nothing to play, and the host
 * saw none of it, because what the link can't carry waits inside Android's Bluetooth, below this socket (10 Nov
 * 2026). Fifty packets a second is half that link's rate, so a pause is caught up instead of piling on. The cost is
 * 10 ms on the first frame of each pair, which is why hosts whose link carries 100 a second keep one.
 */
object AudioPack {
    const val MAX = 4
    private val MTK = Regex("""\bmt\d{4}""", RegexOption.IGNORE_CASE)

    /** adb debug (AUDIO_PACK): frames per packet on any host until the app restarts; 0 = by the chip. */
    @Volatile var forced = 0

    /** [names]: what the phone calls its chip (Build.SOC_*, ro.board.platform, Build.HARDWARE). Pure (AudioPackTest). */
    fun frames(names: List<String>, forced: Int = 0): Int = when {
        forced in 1..MAX -> forced
        names.any { it.contains("mediatek", ignoreCase = true) || MTK.containsMatchIn(it) } -> 2
        else -> 1
    }

    private val byChip by lazy { frames(listOf(Compat.soc(), Compat.prop("ro.board.platform"), android.os.Build.HARDWARE)) }

    /** For the call that starts now. The first use asks the system for the chip's name (AudioBridge does, before any call). */
    fun now(): Int = if (forced in 1..MAX) forced else byChip
}

/**
 * The caller's frames waiting for the Bluetooth link, oldest first: at most [backlog], then the oldest goes.
 * [take] hands over every frame waiting, as one write; with [pack] over 1 it first waits for that many (a frame
 * comes every 10 ms), but never long: a daemon that stops sending (a hold) must not keep a frame back.
 */
internal class SendQueue(private val backlog: Int) {
    class Taken(val bytes: Int, val frames: Int)

    private val q = ArrayDeque<ByteArray>()
    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private var closed = false

    /** Adds [frame]; how many of the oldest fell out for it. */
    fun offer(frame: ByteArray): Int {
        lock.lock()
        try {
            q.addLast(frame)
            var dropped = 0
            while (q.size > backlog) { q.removeFirst(); dropped++ }
            ready.signalAll()
            return dropped
        } finally { lock.unlock() }
    }

    /** Blocks for the next write's frames, copied into [buf] back to back (as many as fit [maxTx]); null once closed. */
    fun take(buf: ByteArray, maxTx: Int, pack: Int): Taken? {
        lock.lock()
        try {
            while (q.isEmpty()) { if (closed) return null; ready.await(200, TimeUnit.MILLISECONDS) }
            if (pack > 1) {
                var left = TimeUnit.MILLISECONDS.toNanos((pack - 1) * FRAME_MS + SLACK_MS)
                while (q.size < pack && !closed && left > 0) left = ready.awaitNanos(left)
            }
            var n = 0; var frames = 0
            while (q.isNotEmpty() && (n == 0 || n + q.first().size <= maxTx)) {
                val f = q.removeFirst(); System.arraycopy(f, 0, buf, n, f.size); n += f.size; frames++
            }
            return Taken(n, frames)
        } finally { lock.unlock() }
    }

    fun close() { lock.lock(); try { closed = true; ready.signalAll() } finally { lock.unlock() } }

    companion object {
        const val FRAME_MS = 10L
        /** Past the pair's own 10 ms: the daemon's frames come off the sound card's clock, a few ms either way. */
        const val SLACK_MS = 5L
    }
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
        AudioPack.now().let { if (it > 1) Log.i(TAG, "audio to the phone: this host packs $it frames a Bluetooth packet") }
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
        // Not every Bluetooth blocks the write, so some hosts pair their frames from the start (AudioPack).
        val pack = AudioPack.now()
        val q = SendQueue(BACKLOG)
        val st = IntArray(5)                         // frames in, frames out, writes, dropped, slowest write (ms)
        val down = Thread({
            val din = ds.inputStream
            val buf = ByteArray(MAX)
            var stallLogged = 0L
            try {
                while (alive.get()) {
                    val n = din.read(buf)
                    if (n <= 0) break
                    val framed = ByteArray(n + 2)
                    framed[0] = (n and 0xff).toByte()
                    framed[1] = (n shr 8).toByte()
                    System.arraycopy(buf, 0, framed, 2, n)
                    val dropped = q.offer(framed)
                    CallAudioCheck.frameIn()
                    repeat(dropped) { CallAudioCheck.dropped() }
                    synchronized(st) { st[0]++; st[3] += dropped }
                    // A write that never returns logs nothing itself: say so from here (a client got no caller audio
                    // for 15 s while its own audio still reached the host, 6 Nov 2026)
                    val now = SystemClock.elapsedRealtime()
                    if (dropped > 0 && now - stallLogged > 5000) {
                        stallLogged = now
                        // With what the radios were doing: a stall's cause is one of them, and the line alone never said
                        Log.w(TAG, "audio to the phone: Bluetooth isn't taking it, dropping the oldest (${runCatching { CallAudioCheck.radio() }.getOrDefault("")})")
                    }
                }
            } catch (_: Exception) {} finally { alive.set(false); q.close() }
        }, "audio-down").apply { isDaemon = true; start() }
        val send = Thread({
            val out = l2cap.outputStream
            val maxTx = runCatching { l2cap.maxTransmitPacketSize }.getOrDefault(0).let { if (it in 64..4096) it else MAX + 2 }
            val buf = ByteArray(maxOf(maxTx, MAX + 2))
            var nextLog = SystemClock.elapsedRealtime() + 5000
            if (pack > 1) Log.i(TAG, "audio to the phone: $pack frames a packet on this host")
            try {
                while (alive.get()) {
                    val t = q.take(buf, maxTx, pack) ?: break
                    if (t.bytes == 0) continue
                    val t0 = SystemClock.elapsedRealtime()
                    out.write(buf, 0, t.bytes)
                    val ms = (SystemClock.elapsedRealtime() - t0).toInt()
                    CallAudioCheck.wrote(t.frames, ms.toLong())
                    synchronized(st) {
                        st[1] += t.frames; st[2]++; if (ms > st[4]) st[4] = ms
                        if (t0 > nextLog) {
                            nextLog = t0 + 5000
                            // Only when the link lagged (more frames shared a write than this host packs, a drop, a
                            // slow write): healthy calls already log every 5 s in the daemon's line
                            if (st[3] > 0 || st[1] > st[2] * pack || st[4] >= 40)
                                Log.i(TAG, "audio to the phone 5s: frames=${st[1]}/${st[0]} writes=${st[2]} dropped=${st[3]} slowest write=${st[4]} ms (max $maxTx B, $pack a packet)")
                            st.fill(0)
                        }
                    }
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
            q.close()
            runCatching { l2cap.close() }
            ds.closeNow()
            down.join(500); send.join(500)
            Log.i(TAG, "audio bridge down")
        }
    }

    fun stop() {
        acceptThread = null
        runCatching { server?.close() }
    }
}
