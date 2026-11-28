// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * A pairing the host accepted that brought no Hello reports itself, with both sides of what Bluetooth did.
 *
 * A Poco X3 NFC on MIUI 14 accepted an S25 Ultra's pairing again and again, the phone subscribed, and its Hello never
 * arrived; the owner reinstalled the ROM, the host and the client several times over two days (27-28 Nov 2026). Nothing
 * was reported by itself (the silent-link watch is for a phone that is already paired), and the reports sent by hand
 * held neither what Android said about the bond nor what the Bluetooth stack did with the phone's requests.
 *
 * So the host keeps the steps of a pairing as Android tells them (links, pairing requests and their kind, bond states
 * with the reason one ended, the other radio joining in), and [WAIT_MS] after a pairing request it accepted without a
 * Hello following, sends them with the chip's record of those seconds ([HciPairing]) and its log (pairing_no_hello).
 * A report sent by hand from the pairing screen carries the same ([forReport]).
 *
 * Phones are "phone A", "phone B" by address in order of appearance: an address never leaves the host, and whether
 * the same phone came back under another address is what the steps must be able to say.
 */
object PairWatch {
    private const val TAG = "SimRelayHost"
    const val WAIT_MS = 30_000L
    private const val KEEP_MS = 10 * 60_000L
    private const val MAX_STEPS = 80

    private lateinit var app: Context
    private val steps = ArrayDeque<Pair<Long, String>>()
    private val letters = LinkedHashMap<String, Int>()
    @Volatile private var acceptedAt = 0L          // the last pairing request this host accepted (elapsed ms), 0 = none open
    @Volatile private var subscribedAt = 0L
    @Volatile private var lastMac: String? = null
    private var waits = 0

    /** For the event and a report: whether Bluetooth serves a client now, and where our service sits in the table. */
    @Volatile var place: () -> Int = { 0 }

    private fun who(mac: String?): String = synchronized(letters) {
        if (mac == null) return "a phone"
        "phone ${'A' + letters.getOrPut(mac) { letters.size } % 26}"
    }

    /** One step, as words. Any thread. */
    fun note(mac: String?, what: String) {
        val line = "${who(mac)}: $what"
        synchronized(steps) {
            steps.addLast(SystemClock.elapsedRealtime() to line)
            while (steps.size > MAX_STEPS) steps.removeFirst()
        }
    }

    fun bondName(s: Int) = when (s) { BluetoothDevice.BOND_NONE -> "none"; BluetoothDevice.BOND_BONDING -> "pairing"; BluetoothDevice.BOND_BONDED -> "paired"; else -> "$s" }
    // BluetoothDevice.UNBOND_REASON_* (hidden constants; the values are the platform's since Android 4)
    private fun reasonName(r: Int) = when (r) {
        0 -> ""; 1 -> "authentication failed"; 2 -> "authentication rejected"; 3 -> "authentication cancelled"; 4 -> "the phone was gone"
        5 -> "discovery in progress"; 6 -> "authentication timed out"; 7 -> "repeated attempts"; 8 -> "the phone cancelled"; 9 -> "removed"
        else -> "reason $r"
    }
    private fun variantName(v: Int) = when (v) {
        0 -> "PIN"; 1 -> "passkey"; 2 -> "confirm a number"; 3 -> "consent"; 4 -> "show a passkey"; 5 -> "show a PIN"; 6 -> "out of band"; 7 -> "16-digit PIN"
        else -> "kind $v"
    }
    private fun typeName(d: BluetoothDevice) = when (runCatching { d.type }.getOrDefault(0)) { 1 -> "classic"; 2 -> "LE"; 3 -> "both radios"; else -> "type unknown" }
    private fun transportName(i: Intent) = when (i.getIntExtra("android.bluetooth.device.extra.TRANSPORT", 0)) { 1 -> " (classic radio)"; 2 -> " (LE)"; else -> "" }

    fun start(ctx: Context) {
        app = ctx.applicationContext
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val mac = d?.address
                when (i.action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val now = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        val before = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                        val why = reasonName(i.getIntExtra("android.bluetooth.device.extra.REASON", 0))
                        val text = "bond ${bondName(before)} -> ${bondName(now)}${if (why.isEmpty()) "" else " ($why)"}"
                        note(mac, text)
                        Log.i(TAG, "pairing: ${who(mac)} $text")
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> note(mac, "radio link up${transportName(i).ifEmpty { d?.let { " (${typeName(it)})" }.orEmpty() }}")
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> note(mac, "radio link down${transportName(i)}")
                    "android.bluetooth.device.action.PAIRING_CANCEL" -> { note(mac, "Android cancelled the pairing"); Log.i(TAG, "pairing: ${who(mac)} cancelled by Android") }
                }
            }
        }, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED); addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED); addAction("android.bluetooth.device.action.PAIRING_CANCEL")
        })
    }

    /** Android asked for a pairing to be confirmed. [ours]: this host answers it (its window is open, or its own phone). */
    fun request(i: Intent, d: BluetoothDevice, ours: Boolean) {
        val variant = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
        note(d.address, "pairing request (${variantName(variant)}, ${typeName(d)}, bond ${bondName(runCatching { d.bondState }.getOrDefault(-1))})" +
            if (ours) "" else ": left to Android")
    }

    /** The host's answer to a pairing request went to Android ([ok]: taken). Pairing thread. */
    fun confirmed(mac: String, ok: Boolean) {
        note(mac, if (ok) "host accepted" else "host's accept REFUSED by Android")
        if (!ok || !::app.isInitialized) return
        lastMac = mac
        acceptedAt = SystemClock.elapsedRealtime()
        waits = 0
        WakeAlarm.set(app, "pair-watch", WAIT_MS) { check() }
    }

    fun subscribed(mac: String) { subscribedAt = SystemClock.elapsedRealtime(); note(mac, "subscribed") }

    /** A Hello over Bluetooth: whatever pairing was open ended well. */
    fun hello(mac: String) {
        if (acceptedAt != 0L) note(mac, "said Hello")
        acceptedAt = 0
        if (::app.isInitialized) WakeAlarm.cancel(app, "pair-watch")
    }

    private fun check() {
        val at = acceptedAt
        if (at == 0L) return
        val mac = lastMac
        val bond = mac?.let { m -> runCatching { app.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.getRemoteDevice(m).bondState }.getOrNull() } ?: -1
        // Someone may still be answering a pairing dialog on the phone; and never a Bluetooth dump during a call
        if ((bond == BluetoothDevice.BOND_BONDING || CallRegistry.anyCall()) && waits < 4) {
            waits++
            WakeAlarm.set(app, "pair-watch", WAIT_MS) { check() }
            return
        }
        acceptedAt = 0
        val seconds = (SystemClock.elapsedRealtime() - at) / 1000
        val subscribed = subscribedAt >= at - 60_000
        Log.w(TAG, "pairing accepted $seconds s ago and no Hello followed (${who(mac)}, bond ${bondName(bond)}${if (subscribed) ", subscribed" else ""})")
        Thread {
            val detail = JSONObject().put("what", if (subscribed) "subscribed" else "not subscribed").put("seconds", seconds)
                .put("bond", bondName(bond)).put("still_pairing", bond == BluetoothDevice.BOND_BONDING)
            fill(detail)
            Diag.event("pairing_no_hello", detail)
        }.apply { isDaemon = true; name = "pair-watch" }.start()
    }

    /** The steps and the chip's record into [detail]. Blocks for the Bluetooth dump (a few seconds); not in a call. */
    private fun fill(detail: JSONObject) {
        if (Relay.started()) detail.put("paired", Relay.pairedClient() != null).put("window", Relay.pairingOpen())
        detail.put("place", place())
            .put("steps", JSONArray(steps()))
        if (CallRegistry.anyCall()) { detail.put("hci_note", "not read: a call is on"); return }
        val r = LinkRadio.record()
        detail.put("hci_note", r.how).put("hci", JSONArray(HciPairing.lines(r.recs, endAgo = r.endAgo)))
        detail.put("bt_log", JSONArray(stackLog(Root.sh("p=\$(pidof com.android.bluetooth || pidof com.google.android.bluetooth); " +
            "[ -n \"\$p\" ] && logcat -d -v time --pid=\$p -t 3000 2>/dev/null", 8_000).out, System.currentTimeMillis() - STACK_LOG_MS)))
    }

    private const val STACK_LOG_MS = 150_000L
    private val STACK_TAGS = Regex("(?i)smp|bond|pair|btif_dm|bt_btm|sec|gatt|l2c")
    private val STACK_LINE = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}) ([VDIWEF])/([^(]*)\(\s*\d+\): ?(.*)$""")
    private val HEX_RUN = Regex("""\b(?:0x)?[0-9a-fA-F]{12,}\b""")
    private val MASKED_MAC = Regex("""(?i)\b(?:xx:){2,5}[0-9a-f:]{2,11}\b""")

    /**
     * What Android's Bluetooth process logged since [since] (epoch ms), from [logcat] ("-v time" lines): its warnings
     * and errors, and what it says about pairing, bonds, security and the control channel at any level. Scrubbed like
     * every log that leaves the host, with long runs of hex (keys, addresses written without colons) and half-hidden
     * addresses out as well, and no line that speaks of a name. The newest 150 lines, 200 characters each. Pure.
     */
    internal fun stackLog(logcat: String, since: Long, now: Long = System.currentTimeMillis()): List<String> {
        val f = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT)
        val from = f.format(java.util.Date(since)); val to = f.format(java.util.Date(now))
        return logcat.lineSequence().mapNotNull { line ->
            val m = STACK_LINE.find(line) ?: return@mapNotNull null
            val (time, level, tag, msg) = m.destructured
            // The log's dates carry no year: a window across New Year's midnight keeps nothing, which is the safe side
            if (time < from || time > to) return@mapNotNull null
            if (level !in "WEF" && !STACK_TAGS.containsMatchIn(tag) && !STACK_TAGS.containsMatchIn(msg)) return@mapNotNull null
            if (msg.contains("name", ignoreCase = true)) return@mapNotNull null
            "${time.substring(6)} $level ${tag.trim()}: ${MASKED_MAC.replace(HEX_RUN.replace(Scrub.text(msg), "<hex>"), "<mac>")}".take(200)
        }.toList().takeLast(150)
    }

    /** The last ten minutes' steps, oldest first, each with how long ago ("-31.2 s phone A: ..."). */
    fun steps(): List<String> {
        val now = SystemClock.elapsedRealtime()
        return synchronized(steps) { steps.filter { now - it.first <= KEEP_MS } }
            .map { "-${"%.1f".format(java.util.Locale.ROOT, (now - it.first) / 1000.0)} s ${it.second}" }
    }

    /**
     * For a report sent by hand: the same as the event, when a pairing was asked for in the last ten minutes and no
     * phone is being served over Bluetooth now; else null. Blocks like [fill].
     */
    fun forReport(serving: Boolean): JSONObject? {
        if (serving || steps().none { "pairing request" in it }) return null
        return JSONObject().also(::fill)
    }

    /** adb debug (PAIR_LOOK): what the event would carry now, into the log. */
    fun debug() = Thread {
        val d = JSONObject().also(::fill)
        Log.i(TAG, "PAIR_LOOK: ${d.optString("hci_note")}; place ${d.optInt("place")}")
        (0 until d.getJSONArray("steps").length()).forEach { Log.i(TAG, "PAIR_LOOK step: ${d.getJSONArray("steps").getString(it)}") }
        d.optJSONArray("hci")?.let { a -> (0 until a.length()).forEach { Log.i(TAG, "PAIR_LOOK hci: ${a.getString(it)}") } }
    }.apply { isDaemon = true }.start()
}
