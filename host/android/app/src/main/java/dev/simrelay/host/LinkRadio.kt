// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.util.Log

/**
 * What the host's Bluetooth radio does under a call, as far as an app can learn it: the link's radio mode (PHY) and who
 * is scanning. Both go into every call's numbers (CallAudioCheck), so a report says them without anyone being asked.
 *
 * A Galaxy A13 host (MediaTek) handed the caller's audio to its Bluetooth on time and the phone still got it late and
 * in bursts, about two thirds of real time for two seconds, then the rest at once (10 Nov 2026). One frame a packet or
 * two made no difference, and the phone's audio reached the A13 smoothly in the same calls: the limit sits in what the
 * A13's radio sends, below anything the host could see. Two things an app can still look at are which radio mode the
 * link uses (the host asked every phone for the 2M one and never logged the answer; the one host that sounded clean
 * with the same phone can't do 2M) and whether something on the host scans for Bluetooth devices during the call.
 */
object LinkRadio {
    private const val TAG = "SimRelayHost"
    // BluetoothDevice.PHY_LE_*: 1 = 1M, 2 = 2M, 3 = coded; the masks to ask with: 1 = 1M, 2 = 2M
    const val MASK_1M = 1
    const val MASK_2M = 2
    private val MTK = Regex("""\bmt\d{4}""", RegexOption.IGNORE_CASE)

    fun phyName(phy: Int): String = when (phy) { 1 -> "1M"; 2 -> "2M"; 3 -> "coded"; else -> "?" }

    /**
     * The radio mode to ask a link for, from the chip's names: 1M on MediaTek chips, 2M elsewhere. A test (10 Nov 2026):
     * if a MediaTek host's calls are clean on 1M, its 2M sending was the trouble; the call's numbers say which mode the
     * call really ran on either way. Pure (LinkRadioTest).
     */
    fun wantedMask(names: List<String>): Int =
        if (names.any { it.contains("mediatek", ignoreCase = true) || MTK.containsMatchIn(it) }) MASK_1M else MASK_2M

    val mask: Int by lazy { wantedMask(listOf(Compat.soc(), Compat.prop("ro.board.platform"), android.os.Build.HARDWARE)) }

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
    fun linkUp() { inUse = "?"; note("link up") }

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

    /**
     * [scans] of this phone right now, as root; "" when the dump can't be had in two seconds. Asked at a call's end,
     * never during it: the dump runs inside the Bluetooth stack.
     */
    fun scansNow(): String = runCatching {
        val r = Root.sh("dumpsys bluetooth_manager | sed -n '/GATT Scanner Map/,/GATT Advertiser Map/p;/Le scan:/p' | head -c 16000", 2_000)
        scans(r.out)
    }.getOrDefault("")
}
