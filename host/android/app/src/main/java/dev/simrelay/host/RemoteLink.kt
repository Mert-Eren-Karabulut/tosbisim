// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Remote mode control path (protocol/README.md "Remote mode"): the host waits in the presence room on
 * api.tosbisim.com (Reverb, Pusher protocol) and swaps sealed envelopes with the iPhone as client events.
 * Runs only while a key is set. The key arrives over Bluetooth (RemoteSetup) and is kept in app-private prefs.
 */
object RemoteLink {
    private const val TAG = "SimRelayHost"
    private const val EVENT = "client-m"
    private const val MAX_AGE_MS = 120_000L

    private lateinit var app: Context
    private lateinit var onEnvelope: (ByteArray) -> Unit
    private lateinit var onPeer: (present: Boolean, wasThere: Boolean) -> Unit
    private val thread = HandlerThread("remote").apply { start() }
    private val h = Bg.handler("remote", thread.looper)
    private val http = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var gen = 0                       // bumps on every (re)connect: callbacks from an old socket are ignored
    private var backoffMs = 2000L
    @Volatile var crypto: RemoteCrypto? = null; private set
    @Volatile var online = false; private set      // subscribed to our room
    @Volatile var peerPresent = false; private set  // the iPhone is in the room
    private var channel = ""
    private val seen = object : LinkedHashMap<String, Long>(256, 0.75f, false) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Long>) = size > 512
    }

    val enabled get() = crypto != null

    fun start(ctx: Context, onEnvelope: (ByteArray) -> Unit, onPeer: (present: Boolean, wasThere: Boolean) -> Unit) {
        app = ctx.applicationContext
        this.onEnvelope = onEnvelope
        this.onPeer = onPeer
        prefs().getString("key", null)?.let { crypto = RemoteCrypto(Base64.decode(it, Base64.NO_WRAP)) }
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { h.post { if (enabled && !online) { backoffMs = 2000; connect() } } }
        })
        h.post { if (enabled) connect() }
    }

    /** RemoteSetup from the paired iPhone over Bluetooth. */
    fun configure(on: Boolean, key: ByteArray?) = h.post {
        if (on && key != null && key.size == 32) {
            prefs().edit().putString("key", Base64.encodeToString(key, Base64.NO_WRAP)).apply()
            crypto = RemoteCrypto(key)
            Log.i(TAG, "remote mode on (room ${crypto!!.roomId.take(8)})")
            connect()
        } else {
            prefs().edit().remove("key").apply()
            crypto = null
            Log.i(TAG, "remote mode off")
            disconnect()
        }
    }

    /** One envelope to the iPhone over the internet (sealed). */
    fun send(env: ByteArray) = h.post {
        val c = crypto ?: return@post
        val w = ws ?: return@post
        if (!online) return@post
        val b = Base64.encodeToString(c.sealEnvelope(env), Base64.NO_WRAP)
        w.send(JSONObject().put("event", EVENT).put("channel", channel).put("data", JSONObject().put("b", b)).toString())
    }

    private fun prefs() = app.getSharedPreferences("remote", Context.MODE_PRIVATE)

    private fun disconnect() {
        gen++
        ws?.close(1000, "bye"); ws = null
        setOnline(false); setPeer(false)
    }

    private fun connect() {
        disconnect()
        val my = gen
        Thread {
            val url = Api.getJson("/remote")?.optString("ws_url")
            h.post {
                if (my != gen) return@post
                if (url.isNullOrEmpty()) { retry(); return@post }
                Log.i(TAG, "remote: connecting")
                ws = http.newWebSocket(Request.Builder().url(url).build(), Listener(my))
            }
        }.start()
    }

    private fun retry() {
        val my = gen
        h.postDelayed({ if (my == gen && enabled) connect() }, backoffMs)
        backoffMs = minOf(backoffMs * 2, 60_000)
    }

    private fun setOnline(v: Boolean) { if (online != v) { online = v; Relay.remoteChanged() } }
    /** [wasThere]: the iPhone was in the room before we joined (we learn it from the subscription). */
    private fun setPeer(v: Boolean, wasThere: Boolean = false) {
        if (peerPresent == v) return
        peerPresent = v
        Log.i(TAG, "remote: iPhone ${if (v) "in the room" else "left"}")
        onPeer(v, wasThere)
    }

    private class Listener(val my: Int) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) { h.post { if (my == gen) handle(webSocket, text) } }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            h.post { if (my == gen) { Log.w(TAG, "remote: socket failed: $t"); ws = null; setOnline(false); setPeer(false); retry() } }
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            h.post { if (my == gen) { Log.i(TAG, "remote: socket closed $code"); ws = null; setOnline(false); setPeer(false); retry() } }
        }
    }

    private fun data(m: JSONObject): JSONObject = when (val d = m.opt("data")) {
        is JSONObject -> d
        is String -> runCatching { JSONObject(d) }.getOrDefault(JSONObject())
        else -> JSONObject()
    }

    private fun handle(w: WebSocket, text: String) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (m.optString("event")) {
            "pusher:connection_established" -> subscribe(w, data(m).optString("socket_id"))
            "pusher:ping" -> w.send("""{"event":"pusher:pong","data":{}}""")
            "pusher:error", "pusher:subscription_error" -> {
                // A rejected subscription leaves the socket open but useless: start over (with backoff).
                Log.w(TAG, "remote: ${m.optString("event")} ${m.opt("data")}")
                if (!online) { w.close(1000, "subscription failed") }
            }
            "pusher_internal:subscription_succeeded" -> {
                backoffMs = 2000
                setOnline(true)
                val ids = data(m).optJSONObject("presence")?.optJSONArray("ids")
                val ios = ids != null && (0 until ids.length()).any { ids.optString(it) == "ios" }
                Log.i(TAG, "remote: in the room (iPhone ${if (ios) "there" else "not there"})")
                setPeer(ios, wasThere = true)
            }
            "pusher_internal:member_added" -> if (data(m).optString("user_id") == "ios") setPeer(true)
            "pusher_internal:member_removed" -> if (data(m).optString("user_id") == "ios") setPeer(false)
            EVENT -> receive(data(m).optString("b"))
        }
    }

    private fun subscribe(w: WebSocket, socketId: String) {
        val c = crypto ?: return
        val my = gen
        channel = "presence-room.${c.roomId}"
        Thread {
            val auth = Api.postJson("/remote/auth", JSONObject().put("socket_id", socketId).put("channel_name", channel)
                .put("room_token", RemoteCrypto.hex(c.roomToken)).put("role", "host"))
            h.post {
                if (my != gen) return@post
                if (auth == null) { w.close(1000, "auth failed"); return@post }
                w.send(JSONObject().put("event", "pusher:subscribe").put("data", JSONObject().put("channel", channel)
                    .put("auth", auth.optString("auth")).put("channel_data", auth.optString("channel_data"))).toString())
                // No "subscription_succeeded" within 15 s: the socket is stuck, start over.
                h.postDelayed({ if (my == gen && !online) { Log.w(TAG, "remote: no subscription in 15 s, reconnecting"); w.close(1000, "timeout") } }, 15_000)
            }
        }.start()
    }

    private fun receive(b64: String) {
        val c = crypto ?: return
        val sealed = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull() ?: return
        val plain = c.openControl(sealed) ?: run { Log.w(TAG, "remote: message failed authentication, dropped"); return }
        if (plain.size < 8) return
        val age = System.currentTimeMillis() - ByteBuffer.wrap(plain, 0, 8).long
        val nonce = RemoteCrypto.hex(sealed.copyOf(12))
        if (age > MAX_AGE_MS || age < -MAX_AGE_MS || seen.containsKey(nonce)) {
            Log.w(TAG, "remote: stale or replayed message dropped"); return
        }
        seen[nonce] = System.currentTimeMillis()
        onEnvelope(plain.copyOfRange(8, plain.size))
    }
}
