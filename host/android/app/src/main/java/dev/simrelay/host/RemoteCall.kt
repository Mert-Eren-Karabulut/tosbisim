// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import dev.simrelay.protocol.v1.RemoteAudio
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Remote-mode call audio: the daemon's Opus packets <-> sealed UDP through our TURN allocation, bound to the
 * iPhone's relay address (protocol/README.md "Remote mode"). The internet twin of AudioBridge's L2CAP pump.
 *
 * One session at a time: offers run on one thread and carry a generation; a close (or a newer offer) during the
 * 1-3 s of setup wins, so a session can't outlive its call and keep the daemon's single audio client slot busy.
 */
object RemoteCall {
    private const val TAG = "SimRelayHost"
    private const val REFRESH_S = 240L
    private val setup = Bg.single("remote-call")
    private val timers = Executors.newSingleThreadScheduledExecutor()
    private val gen = AtomicInteger()
    @Volatile private var session: Session? = null

    private class Session(val turn: Turn, val daemon: LocalSocket) {
        @Volatile var alive = true
        @Volatile var rx = 0; @Volatile var tx = 0; @Volatile var bad = 0
        var refresh: java.util.concurrent.ScheduledFuture<*>? = null
        fun close() {
            alive = false
            refresh?.cancel(false)
            runCatching { daemon.close() }
            Thread { runCatching { turn.close() } }.start()
        }
    }

    private var creds: JSONObject? = null
    private var credsUntil = 0L

    /** TURN REST credentials, reused until an hour before they expire (saves an HTTPS round trip per call). */
    private fun credentials(ctx: Context): JSONObject {
        creds?.let { if (System.currentTimeMillis() < credsUntil) return it }
        val c = Api.postJson("/turn", JSONObject().put("install_id", Api.installId(ctx))) ?: error("no TURN credentials")
        creds = c
        credsUntil = System.currentTimeMillis() + (c.optLong("ttl", 3600) - 3600).coerceAtLeast(60) * 1000
        return c
    }

    /** The iPhone's offer: set up our side and answer with our relay address. */
    fun offer(ctx: Context, crypto: RemoteCrypto, peerIp: String, peerPort: Int, answer: (RemoteAudio) -> Unit) {
        close("new offer")
        val my = gen.incrementAndGet()
        setup.execute {
            var turn: Turn? = null
            var daemon: LocalSocket? = null
            try {
                val c = credentials(ctx)
                val url = (0 until c.getJSONArray("urls").length()).map { c.getJSONArray("urls").getString(it) }
                    .first { it.endsWith("transport=udp") }
                val hp = URI(url.substringBefore('?').replace("turn:", "turn://")).let { InetSocketAddress(InetAddress.getByName(it.host), it.port) }
                turn = Turn(hp, c.getString("username"), c.getString("credential"))
                val t0 = System.currentTimeMillis()
                val relay = turn.allocate()
                turn.bind(InetSocketAddress(InetAddress.getByName(peerIp), peerPort))
                check(gen.get() == my) { "superseded during setup" }
                daemon = LocalSocket(LocalSocket.SOCKET_SEQPACKET)
                daemon.connect(LocalSocketAddress("simrelayd-audio", LocalSocketAddress.Namespace.ABSTRACT))
                val s = Session(turn, daemon)
                synchronized(this) {
                    check(gen.get() == my) { "superseded during setup" }
                    session = s
                }
                // Refresh on a timer, not on packet flow: a stalled capture must not let the allocation expire.
                s.refresh = timers.scheduleWithFixedDelay({ if (s.alive) runCatching { s.turn.refresh() } }, REFRESH_S, REFRESH_S, TimeUnit.SECONDS)
                Log.i(TAG, "remote audio: relay ${relay.port} bound to iPhone relay in ${System.currentTimeMillis() - t0} ms")
                answer(RemoteAudio.newBuilder().setRelayIp(relay.address.hostAddress ?: "").setRelayPort(relay.port).build())
                Thread({ pump(s, crypto, answer) }, "remote-audio").start()
            } catch (e: Exception) {
                Log.e(TAG, "remote audio failed: ${e.message ?: e}")
                runCatching { daemon?.close() }
                turn?.let { t -> Thread { runCatching { t.close() } }.start() }
                if (gen.get() == my) answer(RemoteAudio.newBuilder().setClose(true).build())
            }
        }
    }

    private fun pump(s: Session, crypto: RemoteCrypto, answer: (RemoteAudio) -> Unit) {
        var nextLog = System.currentTimeMillis() + 5000
        val buf = ByteArray(512)
        try {
            // Inside the try: a close() racing this start makes the streams throw, and on a raw thread that killed the
            // whole host mid-call (audit, 20 Oct 2026)
            val out = s.daemon.outputStream
            s.turn.start { b, off, len ->
                val plain = crypto.openAudio(b.copyOfRange(off, off + len))
                if (plain == null) { s.bad++; return@start }
                s.rx++
                runCatching { out.write(plain) }                  // SEQPACKET: one write = one datagram
            }
            val din = s.daemon.inputStream
            while (s.alive) {
                val n = din.read(buf)
                if (n <= 0) break
                s.turn.send(crypto.sealAudio(buf, n))
                s.tx++
                val now = System.currentTimeMillis()
                if (now > nextLog) { nextLog = now + 5000; Log.i(TAG, "remote audio: sent=${s.tx} recv=${s.rx} bad=${s.bad} so far") }
            }
        } catch (_: Exception) {
        } finally {
            synchronized(this) { if (session === s) { session = null } }
            if (s.alive) {
                s.close()
                Log.i(TAG, "remote audio closed (daemon side ended): sent=${s.tx} recv=${s.rx} bad=${s.bad}")
                // Tell the iPhone (usually Bluetooth took the daemon's audio slot): closed silently, its side waited 6 s
                // for audio, then rebuilt the path and broke the call's Bluetooth audio (20 Oct 2026)
                answer(RemoteAudio.newBuilder().setClose(true).build())
            }
        }
    }

    /** Ends the current session, and any offer still being set up. */
    fun close(why: String) {
        gen.incrementAndGet()
        val s = synchronized(this) { session.also { session = null } } ?: return
        s.close()
        Log.i(TAG, "remote audio closed ($why): sent=${s.tx} recv=${s.rx} bad=${s.bad}")
    }
}
