// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.util.Log

/**
 * What the host's Bluetooth radio does under a call, as far as an app can learn it: the link's radio mode (PHY) and who
 * is scanning. Both go into every call's numbers (CallAudioCheck), so a report says them without anyone being asked.
 *
 * A Galaxy A13 host (MediaTek) handed the caller's audio to its Bluetooth on time and the phone still got it late and
 * in bursts, or half of it (7 to 10 Nov 2026). One frame a packet or two made no difference, and the phone's audio
 * reached the A13 smoothly in the same calls: the limit sits in what the A13's radio sends, below anything the host
 * could see. Asking that host for the slower 1M mode did not help (the link really ran on it, nobody scanned, and the
 * phone got 59%), so every host asks for 2M again. What the chip itself did with the audio is read after the call
 * from Android's own record of it ([HciRing]).
 */
object LinkRadio {
    private const val TAG = "SimRelayHost"
    // BluetoothDevice.PHY_LE_*: 1 = 1M, 2 = 2M, 3 = coded; the masks to ask with: 1 = 1M, 2 = 2M
    const val MASK_1M = 1
    const val MASK_2M = 2

    fun phyName(phy: Int): String = when (phy) { 1 -> "1M"; 2 -> "2M"; 3 -> "coded"; else -> "?" }

    /** The radio mode every link is asked for: 2M, the faster one. */
    const val mask = MASK_2M

    /** The mode the link last reported, sending/receiving ("2M/2M"); "?" until the stack says. */
    @Volatile var inUse = "?"; private set

    private val notes = ArrayDeque<String>()
    private val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)

    /** The last few things asked and heard, with their time: a call's numbers carry them. */
    fun history(): String = synchronized(notes) { notes.joinToString(", ") }

    private fun note(what: String) = synchronized(notes) {
        notes.addLast("${clock.format(java.util.Date())} $what")
        while (notes.size > 8) notes.removeFirst()
    }

    /** The host asked the link for a mode (BleLink). */
    fun asked(mask: Int, why: String) {
        Log.i(TAG, "BLE radio mode: asking for ${phyName(mask)} ($why)")
        note("asked ${phyName(mask)}")
    }

    /** The stack reported the link's mode: after a change, or because the host read it. */
    fun seen(tx: Int, rx: Int, status: Int, how: String) {
        Log.i(TAG, "BLE radio mode $how: sending ${phyName(tx)}, receiving ${phyName(rx)} (status $status)")
        if (status != 0) { note("$how failed ($status)"); return }
        inUse = "${phyName(tx)}/${phyName(rx)}"
        note("$how $inUse")
    }

    /** A new link starts on 1M whatever the last one ran on. */
    fun linkUp() { inUse = "?"; links = emptyMap(); note("link up") }

    // ---- who scans

    /** App names worth naming: the system's and the phone maker's. Anything else a user installed stays unnamed. */
    private val SYSTEM = listOf("android", "com.android.", "com.google.", "com.samsung.", "com.sec.", "com.mediatek.",
        "com.xiaomi.", "com.miui.", "com.qualcomm.", "com.qti.", "dev.simrelay.")
    private val APP = Regex("""^(\S+) \((?:Un)?[Rr]egistered\)$""")
    private val ENTRY = Regex("""^\d\d-\d\d (\d\d:\d\d:\d\d) - (\d+)ms\b""")
    private val MODE = Regex("""ScanMode=(\w+)""")
    private val STACK = Regex("""Le scan:(\w+)""")

    /**
     * The Bluetooth scans in `dumpsys bluetooth_manager`'s scanner list, in a line: per app the scans running now and
     * its last ones ("com.google.uid.shared: now BALANCED 79s + 21:50:04 LOW_LATENCY 6s | stack scan on"). A scan
     * shares the one radio with the call's link. Pure (LinkRadioTest); "" when the dump has no scanner list.
     */
    fun scans(dump: String): String {
        val apps = LinkedHashMap<String, MutableList<String>>()
        var app = ""; var ongoing = false; var listed = false
        var pending: Pair<String, Long>? = null                // a scan whose mode line hasn't come yet: (start, ms)
        var stack = ""
        fun flush(mode: String) {
            val p = pending ?: return
            pending = null
            apps.getOrPut(app) { ArrayList() } += "${if (ongoing) "now" else p.first} $mode ${p.second / 1000}s"
        }
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("GATT Scanner Map")) { listed = true; continue }
            STACK.find(line)?.let { stack = if (it.groupValues[1] == "enabled") "stack scan on" else "stack scan off" }
            APP.matchEntire(line)?.let { m ->
                flush("?")
                val name = m.groupValues[1].substringBefore(':')
                app = if (SYSTEM.any { name == it || name.startsWith(it) }) name else "another app"
                ongoing = false
            }
            when {
                line.startsWith("Ongoing scans") -> { flush("?"); ongoing = true }
                line.startsWith("Last ") && "scans" in line -> { flush("?"); ongoing = false }
                else -> ENTRY.find(line)?.let { m ->
                    flush("?")
                    pending = m.groupValues[1] to (m.groupValues[2].toLongOrNull() ?: 0)
                } ?: MODE.find(line)?.let { flush(it.groupValues[1]) }
            }
        }
        flush("?")
        if (!listed && apps.isEmpty()) return stack
        val list = if (apps.isEmpty()) "no scans" else apps.entries.joinToString("; ") { (a, s) -> "$a: ${s.take(4).joinToString(" + ")}" }
        return (list.take(480) + if (stack.isEmpty()) "" else " | $stack")
    }

    // ---- one look at the Bluetooth stack: who scans, and what the chip did

    private const val RING = "/data/misc/bluetooth/logs/btsnooz_hci.log"
    private const val MARK = "@ring"

    /** [scans]; how the chip sent the busiest link's last seconds, as numbers and in words; what that link agreed. */
    class Look(val scans: String, val sending: HciRing.Sending?, val hci: String, val link: String)

    @Volatile private var links: Map<Int, HciRing.Link> = emptyMap()
    @Volatile private var buffers = ""
    @Volatile private var lastLinkLook = 0L

    /**
     * One root command, two seconds or three: the Bluetooth dump's scanner list, then the record of the chip's traffic
     * that the same dump has just written ([HciRing]). Only numbers leave here. Asked at a call's end and a few seconds
     * after a link comes up, never during a call: the dump runs inside the Bluetooth stack. Never throws.
     */
    fun look(): Look = runCatching {
        val r = Root.sh("dumpsys bluetooth_manager | sed -n '/GATT Scanner Map/,/GATT Advertiser Map/p;/Le scan:/p' | head -c 16000; " +
            "echo; echo $MARK; f=$RING; if [ -f \$f ]; then echo age \$(( \$(date +%s) - \$(stat -c %Y \$f) )); base64 \$f | head -c 8000000; " +
            "else echo none; ls /data/misc/bluetooth/logs /data/log/bt 2>&1 | head -12; fi", 3_000)
        val dump = r.out.substringBefore("\n$MARK\n")
        val rest = r.out.substringAfter("\n$MARK\n", "")
        val scans = scans(dump)
        if (rest.isEmpty()) return@runCatching Look(scans, null, "no answer", "")
        if (!rest.startsWith("age ")) {
            // No such file on this phone: what its Bluetooth keeps instead, by name (a folder listing, no content)
            val names = rest.lines().drop(1).map { it.trim() }.filter { it.isNotEmpty() && it != "timeout" }.joinToString(" ").take(200)
            return@runCatching Look(scans, null, "no record file ($names)", "")
        }
        val age = rest.substringBefore('\n').removePrefix("age ").trim().toLongOrNull() ?: -1
        val b64 = rest.substringAfter('\n').filterNot { it.isWhitespace() }.removeSuffix("timeout")
        val file = java.util.Base64.getDecoder().decode(b64.take(b64.length - b64.length % 4))
        val recs = HciRing.records(file)
        if (recs.isEmpty()) return@runCatching Look(scans, null,
            "record not readable (${file.size} B, starts ${file.take(16).joinToString("") { "%02x".format(it) }})", "")
        links = HciRing.links(recs, links)
        HciRing.buffers(recs).let { if (it.isNotEmpty()) buffers = it }
        val s = HciRing.sending(recs)
        val stale = if (age in 0..5) "" else ", written $age s ago"
        val link = (s?.let { links[it.handle] } ?: links.values.singleOrNull())?.text().orEmpty() +
            if (buffers.isEmpty()) "" else ", chip room $buffers"
        Look(scans, s, (s?.text() ?: "no data sent") + " | ${HciRing.size(recs)}$stale", link.removePrefix(", "))
    }.getOrElse { Look("", null, "failed: ${it.javaClass.simpleName}", "") }

    /**
     * A link came up a few seconds ago: what it agreed on (packet length, interval), while the record still holds it.
     * A call's audio pushes that out of the record within seconds. Not in a call, at most once a minute.
     * [onlyIfUnknown]: a client said hello, which it does every few minutes; one look is enough. Any thread.
     */
    fun linkLook(onlyIfUnknown: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!VoiceGate.fullVoice || CallRegistry.anyCall() || (lastLinkLook != 0L && now - lastLinkLook < 60_000)) return
        if (onlyIfUnknown && lastLinkLook != 0L) return
        lastLinkLook = now
        Thread {
            val l = look()
            Log.i(TAG, "BLE link: ${l.link.ifEmpty { l.hci }}")
        }.apply { isDaemon = true }.start()
    }
}
