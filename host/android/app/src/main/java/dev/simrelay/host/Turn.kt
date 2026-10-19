// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal TURN client (RFC 8656) for remote-mode audio over UDP: one allocation, one peer bound to channel
 * 0x4000, ChannelData both ways. Long-term credentials come from api.tosbisim.com (TURN REST scheme).
 * Requests run synchronously on the caller's thread until [start]; afterwards a reader thread owns the socket's
 * receive side and transactions are matched by id.
 */
class Turn(private val server: InetSocketAddress, private val user: String, private val pass: String) {
    companion object {
        private const val TAG = "SimRelayHost"
        private const val MAGIC = 0x2112A442
        const val CHANNEL = 0x4000
        private val rng = SecureRandom()
    }

    private val sock = DatagramSocket().apply { soTimeout = 1500 }
    private var realm = ByteArray(0)
    private var nonce = ByteArray(0)
    private var key = ByteArray(0)
    @Volatile var relay: InetSocketAddress? = null; private set
    @Volatile private var peer: InetSocketAddress? = null
    @Volatile private var reader: Thread? = null
    private val pending = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.SynchronousQueue<Msg>>()

    class Msg(val type: Int, val attrs: Map<Int, ByteArray>, val tid: ByteArray)

    /** Allocate a UDP relay; returns its public address. */
    fun allocate(lifetime: Int = 600): InetSocketAddress {
        var r = request(0x0003, listOf(attr(0x0019, byteArrayOf(17, 0, 0, 0))), auth = false)   // learn realm/nonce
        if (r.type == 0x0113) { learnAuth(r) }
        r = request(0x0003, listOf(attr(0x0019, byteArrayOf(17, 0, 0, 0)), attr(0x000D, u32(lifetime))))
        check(r.type == 0x0103) { "allocate failed: ${errorOf(r)}" }
        return xorAddr(r.attrs[0x0016]!!).also { relay = it }
    }

    /** Bind the peer's relay address to our channel (also installs the permission). */
    fun bind(p: InetSocketAddress) {
        peer = p
        val r = request(0x0009, listOf(attr(0x000C, byteArrayOf(0x40, 0, 0, 0)), attr(0x0012, xorAddrBytes(p))))
        check(r.type == 0x0109) { "channel bind failed: ${errorOf(r)}" }
    }

    /** Keep the allocation and the channel alive (call every few minutes during a call). */
    fun refresh() {
        val r = request(0x0004, listOf(attr(0x000D, u32(600))))
        if (r.type != 0x0104) Log.w(TAG, "turn refresh: ${errorOf(r)}")
        peer?.let { runCatching { bind(it) }.onFailure { e -> Log.w(TAG, "turn rebind: $e") } }
    }

    /** Start delivering ChannelData payloads to [onData] (reader thread). */
    fun start(onData: (ByteArray, Int, Int) -> Unit) {
        sock.soTimeout = 0
        reader = Thread({
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)
            while (reader != null) {
                try { pkt.setData(buf); sock.receive(pkt) } catch (_: Exception) { break }
                val n = pkt.length
                if (n >= 4 && (buf[0].toInt() and 0xC0) == 0x40) {             // ChannelData
                    val len = ((buf[2].toInt() and 0xff) shl 8) or (buf[3].toInt() and 0xff)
                    if (4 + len <= n) onData(buf, 4, len)
                } else if (n >= 20) {
                    val m = parse(buf.copyOf(n)) ?: continue
                    pending[tidKey(m.tid)]?.offer(m)
                }
            }
        }, "turn-rx").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
    }

    private val sendBuf = ByteArray(2048)
    /** One ChannelData datagram to the bound peer. */
    fun send(data: ByteArray) {
        val n = data.size
        synchronized(sendBuf) {
            sendBuf[0] = 0x40; sendBuf[1] = 0; sendBuf[2] = (n shr 8).toByte(); sendBuf[3] = n.toByte()
            System.arraycopy(data, 0, sendBuf, 4, n)
            val padded = (4 + n + 3) and 3.inv()                               // UDP: padding optional, harmless
            runCatching { sock.send(DatagramPacket(sendBuf, padded, server)) }
        }
    }

    fun close() {
        if (relay != null) runCatching { requestNoWait(0x0004, listOf(attr(0x000D, u32(0)))) }   // release
        reader = null
        sock.close()
    }

    // ---------------------------------------------------------------- STUN messages

    private fun request(type: Int, attrs: List<ByteArray>, auth: Boolean = true): Msg {
        repeat(2) {
            val tid = ByteArray(12).also { rng.nextBytes(it) }
            val r = transact(build(type, tid, attrs, auth), tid)
            if (r.type and 0x0110 == 0x0110 && errorCode(r) == 438) { learnAuth(r); return@repeat }   // stale nonce
            return r
        }
        error("turn: stale nonce twice")
    }

    private fun requestNoWait(type: Int, attrs: List<ByteArray>) {
        val tid = ByteArray(12).also { rng.nextBytes(it) }
        val b = build(type, tid, attrs, true)
        sock.send(DatagramPacket(b, b.size, server))
    }

    private fun transact(bytes: ByteArray, tid: ByteArray): Msg {
        if (reader != null) {
            val q = java.util.concurrent.SynchronousQueue<Msg>()
            pending[tidKey(tid)] = q
            try {
                repeat(3) {
                    sock.send(DatagramPacket(bytes, bytes.size, server))
                    q.poll(1500, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { return it }
                }
            } finally { pending.remove(tidKey(tid)) }
            error("turn: no answer")
        }
        val buf = ByteArray(2048)
        repeat(3) {
            sock.send(DatagramPacket(bytes, bytes.size, server))
            val deadline = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < deadline) {
                val p = DatagramPacket(buf, buf.size)
                try { sock.receive(p) } catch (_: java.net.SocketTimeoutException) { break }
                val m = parse(buf.copyOf(p.length)) ?: continue
                if (m.tid.contentEquals(tid)) return m
            }
        }
        error("turn: no answer from $server")
    }

    private fun learnAuth(r: Msg) {
        realm = r.attrs[0x0014] ?: realm
        nonce = r.attrs[0x0015] ?: nonce
        key = MessageDigest.getInstance("MD5").digest("$user:${String(realm)}:$pass".toByteArray())
    }

    private fun build(type: Int, tid: ByteArray, attrs: List<ByteArray>, auth: Boolean): ByteArray {
        var body = attrs.fold(ByteArray(0)) { a, b -> a + b }
        if (auth && key.isNotEmpty()) {
            body += attr(0x0006, user.toByteArray()) + attr(0x0014, realm) + attr(0x0015, nonce)
            val hdr = header(type, body.size + 24, tid)
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }.doFinal(hdr + body)
            body += attr(0x0008, mac)
        }
        return header(type, body.size, tid) + body
    }

    private fun header(type: Int, len: Int, tid: ByteArray) =
        ByteBuffer.allocate(20).putShort(type.toShort()).putShort(len.toShort()).putInt(MAGIC).put(tid).array()

    private fun attr(t: Int, v: ByteArray): ByteArray {
        val pad = (4 - v.size % 4) % 4
        return ByteBuffer.allocate(4 + v.size + pad).putShort(t.toShort()).putShort(v.size.toShort()).put(v).array()
    }

    private fun u32(v: Int) = ByteBuffer.allocate(4).putInt(v).array()

    private fun parse(d: ByteArray): Msg? {
        if (d.size < 20 || ByteBuffer.wrap(d, 4, 4).int != MAGIC) return null
        val bb = ByteBuffer.wrap(d)
        val type = bb.short.toInt() and 0xffff
        val len = bb.short.toInt() and 0xffff
        bb.int
        val tid = ByteArray(12).also { bb.get(it) }
        val attrs = HashMap<Int, ByteArray>()
        var i = 20
        while (i + 4 <= minOf(d.size, 20 + len)) {
            val t = ((d[i].toInt() and 0xff) shl 8) or (d[i + 1].toInt() and 0xff)
            val l = ((d[i + 2].toInt() and 0xff) shl 8) or (d[i + 3].toInt() and 0xff)
            if (i + 4 + l > d.size) break
            attrs.putIfAbsent(t, d.copyOfRange(i + 4, i + 4 + l))
            i += 4 + l + (4 - l % 4) % 4
        }
        return Msg(type, attrs, tid)
    }

    private fun errorCode(r: Msg): Int = r.attrs[0x0009]?.let { (it[2].toInt() and 7) * 100 + (it[3].toInt() and 0xff) } ?: 0
    private fun errorOf(r: Msg) = "type=0x%04x code=%d %s".format(r.type, errorCode(r),
        r.attrs[0x0009]?.let { String(it, 4, it.size - 4) } ?: "")
    private fun tidKey(t: ByteArray) = RemoteCrypto.hex(t)

    private fun xorAddr(v: ByteArray): InetSocketAddress {
        val port = (((v[2].toInt() and 0xff) shl 8) or (v[3].toInt() and 0xff)) xor (MAGIC ushr 16)
        val ip = ByteBuffer.wrap(v, 4, 4).int xor MAGIC
        return InetSocketAddress(InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(ip).array()), port)
    }

    private fun xorAddrBytes(a: InetSocketAddress): ByteArray {
        val ip = ByteBuffer.wrap(a.address.address).int xor MAGIC
        return ByteBuffer.allocate(8).put(0).put(1).putShort((a.port xor (MAGIC ushr 16)).toShort()).putInt(ip).array()
    }
}
