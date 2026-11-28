// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

/**
 * How a pairing went between the host's Bluetooth and a phone, read from the record of the chip's traffic ([HciRing]):
 * links coming up and ending (with the chip's reason), pairing messages each way, a pairing that failed and why,
 * encryption starting or failing, whether the host had a key the phone asked for, and every request on the control
 * channel with its outcome: answered, refused (and how), or never answered.
 *
 * Why: a Poco X3 NFC on MIUI 14 accepted an S25 Ultra's pairing every time, the phone subscribed, and its Hello never
 * reached the host app; the phone's link ended 30 s after that write, Bluetooth's own wait for an answer (28 Nov 2026).
 * Both apps' logs stop at the edge of the Bluetooth stack, so neither said which side went silent or why a pairing the
 * host called done did not stick.
 *
 * What leaves here are lines of words and numbers: no address (a phone's addresses are told apart as "address A",
 * "address B"), no key, no value written or read. The record itself keeps only a data packet's first 14 bytes.
 * Pure (HciPairingTest).
 */
object HciPairing {
    private class Entry(val t: Long, val key: String, var text: String) {
        var n = 1; var answers = 0; var outcome = ""; var last = t
        /** Still without its answer: a single request with no outcome, or a table read whose last request got none. */
        fun waits() = outcome.isEmpty() && (!key.startsWith("table:") || answers < n)
    }

    private val SMP_FAIL = mapOf(1 to "passkey entry failed", 2 to "no out-of-band data", 3 to "authentication requirements", 4 to "confirm value failed",
        5 to "pairing not supported", 6 to "encryption key size", 7 to "command not supported", 8 to "unspecified reason", 9 to "repeated attempts",
        0x0a to "invalid parameters", 0x0b to "key check failed", 0x0c to "number comparison failed", 0x0d to "classic pairing in progress",
        0x0e to "key derivation between the two radios not allowed", 0x0f to "key rejected")
    private val ATT_ERR = mapOf(0x01 to "invalid handle", 0x02 to "read not permitted", 0x03 to "write not permitted", 0x05 to "insufficient authentication",
        0x06 to "request not supported", 0x08 to "insufficient authorization", 0x0a to "attribute not found", 0x0c to "insufficient key size",
        0x0d to "invalid value length", 0x0e to "unlikely error", 0x0f to "insufficient encryption", 0x11 to "insufficient resources",
        0x12 to "database out of sync")
    private val REASON = mapOf(0x05 to "authentication failure", 0x06 to "key missing", 0x08 to "timeout: the radio lost it", 0x13 to "the phone ended it",
        0x14 to "the phone ended it (low resources)", 0x15 to "the phone ended it (power off)", 0x16 to "the host ended it", 0x1a to "unsupported feature",
        0x1f to "unspecified error", 0x22 to "no response in time", 0x28 to "instant passed", 0x2a to "transaction collision",
        0x2f to "insufficient security", 0x3b to "unacceptable link timing", 0x3d to "encryption check failed", 0x3e to "never established")
    private val IO = mapOf(0 to "display only", 1 to "display and yes/no", 2 to "keyboard only", 3 to "no input or output", 4 to "keyboard and display")
    private val PSM = mapOf(0x01 to "service lookup", 0x03 to "RFCOMM: hands-free, phone book, messages", 0x0f to "network", 0x11 to "input device",
        0x13 to "input device", 0x17 to "media control", 0x19 to "media audio", 0x1b to "media control", 0x1f to "control channel", 0x27 to "enhanced control channels")
    private val DISCOVERY = setOf(0x04, 0x06, 0x08, 0x10)

    private fun hex(v: Int) = "0x%02x".format(v)
    private fun reason(code: Int) = REASON[code]?.let { "$it, ${hex(code)}" } ?: hex(code)

    /**
     * The last [seconds] of [recs] as lines, oldest first, each led by how long before the record's end it happened
     * ("-31.2 s"); at most [max] (the newest stay, the first line then says how many were left out). Links and
     * addresses are learned from the whole record, so a link that came up earlier is still named. [endAgo]: how many
     * seconds before now the record's last entry was made, when that is known: the times then count back from now,
     * like the steps Android told ([PairWatch]).
     */
    fun lines(recs: List<HciRing.Rec>, seconds: Int = 120, max: Int = 120, endAgo: Double = 0.0): List<String> {
        if (recs.isEmpty()) return emptyList()
        val end = recs.last().t
        val from = end - seconds * 1_000_000L
        val out = ArrayList<Entry>()
        val links = HashMap<Int, String>()                           // the chip's link number -> "link 1"
        val classic = HashSet<Int>()
        val peer = HashMap<Int, List<Int>>()                         // a link's phone address: compared, never printed
        val addresses = ArrayList<List<Int>>()
        val pending = HashMap<Pair<Int, Boolean>, Entry>()           // (link, asked by the phone) -> the request waiting
        val other = HashMap<Int, IntArray>()                         // data-channel packets per link: in, out
        var linkNo = 0

        fun letter(a: List<Int>): String {
            var i = addresses.indexOfFirst { it == a || (a.size < 6 && it.take(a.size) == a) }
            if (i < 0 && a.size == 6) { addresses += a; i = addresses.size - 1 }
            return if (i < 0) "an address not seen before" else "address ${'A' + i % 26}"
        }
        fun name(h: Int) = links[h] ?: "link ${hex(h)}"
        fun add(t: Long, key: String, text: String): Entry? {
            if (t < from) return null
            val prev = out.lastOrNull()
            if (prev != null && prev.key == key && key.isNotEmpty()) { prev.n++; prev.last = t; return prev }
            return Entry(t, key, text).also { out += it }
        }
        fun secs(a: Long, b: Long) = "%.1f".format(java.util.Locale.ROOT, (b - a) / 1e6)
        fun close(h: Int, t: Long, how: String) {
            for (fromPhone in booleanArrayOf(true, false)) pending.remove(h to fromPhone)?.let { if (it.waits()) it.outcome = "NO ANSWER: $how ${secs(it.last, t)} s later" }
        }
        fun authBits(a: Int) = listOfNotNull(if (a and 3 == 1) "bonding" else "no bonding", if (a and 4 != 0) "protected from a man in the middle" else null,
            if (a and 8 != 0) "secure connections" else "legacy pairing", if (a and 0x20 != 0) "CT2" else null).joinToString(", ")
        fun keys(k: Int) = listOfNotNull(if (k and 1 != 0) "encryption" else null, if (k and 2 != 0) "identity" else null, if (k and 4 != 0) "signing" else null,
            if (k and 8 != 0) "the other radio's key" else null).joinToString(" + ").ifEmpty { "none" }

        for (r in recs) when (r.kind) {
            1 -> {
                val op = r.u16(0)
                when (op) {
                    0x2019 -> add(r.t, "", "${name(r.u16(3) and 0x0fff)}: host starts encryption with a stored key")
                    0x201a -> add(r.t, "", "${name(r.u16(3) and 0x0fff)}: host has the key and gives it to the chip")
                    0x201b -> add(r.t, "", "${name(r.u16(3) and 0x0fff)}: host has NO key for this phone")
                    0x040b -> add(r.t, "", "classic: host has the key and gives it to the chip")
                    0x040c -> add(r.t, "", "classic: host has NO key for this phone")
                    0x0406 -> add(r.t, "", "${name(r.u16(3) and 0x0fff)}: host asks the chip to end it (${reason(r.u8(5))})")
                    0x0405 -> add(r.t, "", "classic: host connects to ${letter((3..8).map(r::u8))}")
                    0x0409 -> add(r.t, "", "classic: host accepts the link")
                    0x042c -> add(r.t, "", "classic pairing: host confirms")
                    0x042d -> add(r.t, "", "classic pairing: host declines")
                }
            }
            4 -> when (r.u8(0)) {
                0x3e -> when (r.u8(2)) {
                    0x01, 0x0a, 0x29 -> {
                        val h = r.u16(4) and 0x0fff
                        if (r.u8(3) != 0) add(r.t, "", "LE link failed to come up (${reason(r.u8(3))})")
                        else {
                            close(h, r.t, "a new link took its number")
                            links[h] = "link ${++linkNo}"; classic.remove(h)
                            val a = (8..13).map(r::u8); peer[h] = a
                            val kind = when (r.u8(7)) {
                                0 -> "public"
                                1 -> when (a[5] and 0xc0) { 0xc0 -> "random, fixed"; 0x40 -> "random, private (changes)"; else -> "random, one-time" }
                                2 -> "private, and the host holds the key that resolves it (public identity)"
                                else -> "private, and the host holds the key that resolves it (random identity)"
                            }
                            add(r.t, "", "${name(h)} up (LE, host is ${if (r.u8(6) == 1) "peripheral" else "central"}), phone's ${letter(a)}: $kind")
                        }
                    }
                    0x05 -> {
                        val fresh = (5..14).all { r.u8(it) == 0 }
                        add(r.t, "", "${name(r.u16(3) and 0x0fff)}: phone starts encryption with ${if (fresh) "a new or secure-connections key" else "a stored legacy key"}")
                    }
                }
                0x08, 0x59 -> {
                    val h = r.u16(3) and 0x0fff
                    add(r.t, "", if (r.u8(2) != 0) "${name(h)}: encryption FAILED (${reason(r.u8(2))})" else "${name(h)}: encryption ${if (r.u8(5) != 0) "on" else "off"}")
                }
                0x30 -> add(r.t, "", "${name(r.u16(3) and 0x0fff)}: key refreshed${if (r.u8(2) != 0) " FAILED (${reason(r.u8(2))})" else ""}")
                0x05 -> if (r.u8(2) == 0) {
                    val h = r.u16(3) and 0x0fff
                    close(h, r.t, "the link ended")
                    add(r.t, "", "${name(h)} down (${reason(r.u8(5))})")
                    other.remove(h)?.let { if (it[0] + it[1] > 0) add(r.t, "", "${name(h)}: ${it[0]} packets in and ${it[1]} out on data channels while it was up") }
                    links.remove(h); classic.remove(h); peer.remove(h)
                }
                0x04 -> add(r.t, "", "classic: ${letter((2..7).map(r::u8))} asks for a link")
                0x03 -> {
                    val h = r.u16(3) and 0x0fff
                    if (r.u8(2) != 0) add(r.t, "", "classic link failed to come up (${reason(r.u8(2))})")
                    else { links[h] = "link ${++linkNo}"; classic += h; val a = (5..10).map(r::u8); peer[h] = a; add(r.t, "", "${name(h)} up (classic), phone's ${letter(a)}") }
                }
                0x17 -> add(r.t, "", "classic: the chip asks for the key")
                0x18 -> add(r.t, "", "classic: a new key is stored (type ${r.u8(24)})")
                0x33 -> add(r.t, "", "classic pairing: confirmation asked")
                0x36 -> add(r.t, "", "classic pairing ${if (r.u8(2) == 0) "done" else "FAILED (${reason(r.u8(2))})"}")
                0x06 -> if (r.u8(2) != 0) add(r.t, "", "${name(r.u16(3) and 0x0fff)}: authentication FAILED (${reason(r.u8(2))})")
            }
            2 -> if (r.starts && r.d.size >= 9) {
                val h = r.handle
                val who = if (r.fromChip) "phone" else "host"
                val code = r.u8(8)
                when (val cid = r.u16(6)) {
                    6, 7 -> {
                        val l = "${name(h)}${if (cid == 7) " (pairing over the classic radio)" else ""}: $who"
                        when (code) {
                            1, 2 -> add(r.t, "", "$l: pairing ${if (code == 1) "request" else "response"} (${IO[r.u8(9)] ?: hex(r.u8(9))}, ${authBits(r.u8(11))}" +
                                (if (code == 1 && r.d.size > 13) ", wants keys: ${keys(r.u8(13))}" else "") + ")")
                            3, 4, 0x0c, 0x0d, 0x0e -> add(r.t, "values:$h", "${name(h)}: pairing values exchanged")
                            5 -> add(r.t, "", "$l: pairing FAILED (${SMP_FAIL[r.u8(9)] ?: "reason"}, ${hex(r.u8(9))})")
                            6, 7 -> add(r.t, "enc:$h:$who", "$l sends an encryption key")
                            8 -> add(r.t, "", "$l sends its identity key")
                            9 -> {
                                val a = (10 until minOf(16, r.d.size)).map(r::u8)
                                val same = peer[h]?.let { a.isNotEmpty() && it.take(a.size) == a }
                                add(r.t, "", "$l sends its identity address (${if (r.u8(9) == 0) "public" else "random, fixed"}): " +
                                    (if (!r.fromChip) "its own" else if (same == true) "the one it connected with" else "not the one it connected with, ${letter(a)}"))
                            }
                            0x0a -> add(r.t, "", "$l sends a signing key")
                            0x0b -> add(r.t, "", "$l asks for security (${authBits(r.u8(9))})")
                        }
                    }
                    4 -> att(r, h, code, who, ::name, ::add, pending)
                    5 -> when (code) {
                        0x14 -> add(r.t, "", "${name(h)}: $who opens a data channel (PSM ${hex(r.u16(12))})")
                        0x15 -> add(r.t, "", "${name(h)}: $who answers the data channel request")
                        0x17 -> add(r.t, "", "${name(h)}: $who opens enhanced channels (PSM ${hex(r.u16(12))}${PSM[r.u16(12)]?.let { ": $it" }.orEmpty()})")
                        0x18 -> add(r.t, "", "${name(h)}: $who answers the enhanced channels request")
                        0x06 -> add(r.t, "", "${name(h)}: $who closes a data channel")
                        0x01 -> add(r.t, "", "${name(h)}: $who rejects a signalling command")
                    }
                    1 -> if (code == 0x02) add(r.t, "", "${name(h)}: $who opens PSM ${hex(r.u16(12))}${PSM[r.u16(12)]?.let { " ($it)" }.orEmpty()}")
                    else -> if (cid >= 0x40 && r.t >= from) other.getOrPut(h) { IntArray(2) }[if (r.fromChip) 0 else 1]++
                }
            }
        }
        for ((k, e) in pending) if (e.waits()) e.outcome = "NO ANSWER in the ${secs(e.last, end)} s the record goes on" + if (k.first !in links) "" else " (link still up)"
        for ((h, n) in other) if (n[0] + n[1] > 0) out += Entry(end, "", "${name(h)}: ${n[0]} packets in and ${n[1]} out on data channels so far")
        // The same line again and again (a keep-alive written and answered every minute) is its first one and a count
        val text = ArrayList<String>()
        var same = ""; var times = 0
        fun flush() { if (times > 1) text[text.size - 1] = text.last() + " (x$times)" }
        for (e in out) {
            val body = e.text + (if (e.key.startsWith("table:")) " (${e.n} requests, ${e.answers} answered)" else if (e.n > 1) " (x${e.n})" else "") +
                (if (e.outcome.isEmpty()) "" else ": ${e.outcome}")
            if (body == same) { times++; continue }
            flush(); same = body; times = 1
            text += "-${"%.1f".format(java.util.Locale.ROOT, (end - e.t) / 1e6 + endAgo)} s $body"
        }
        flush()
        return if (text.size <= max) text else listOf("... ${text.size - max + 1} earlier lines left out") + text.takeLast(max - 1)
    }

    /**
     * One packet on the control channel (ATT). A request becomes a line and waits in [pending] for its answer, which
     * becomes that line's outcome; reading the table of services is many requests and one line.
     */
    private fun att(r: HciRing.Rec, h: Int, op: Int, who: String, name: (Int) -> String, add: (Long, String, String) -> Entry?,
                    pending: HashMap<Pair<Int, Boolean>, Entry>) {
        val l = "${name(h)}: $who"
        val at = r.u16(9)
        fun ask(key: String, text: String) {
            val k = h to r.fromChip
            val before = pending[k]
            val e = add(r.t, key, text)
            if (before != null && before !== e && before.waits()) before.outcome = "NO ANSWER before the next request"
            if (e != null) pending[k] = e else pending.remove(k)
        }
        fun answer(text: String, counts: Boolean = true) {
            val e = pending[h to !r.fromChip]
            if (e == null) { add(r.t, "", "$l answers an earlier request: $text"); return }
            if (e.key.startsWith("table:")) { if (counts) e.answers++ } else e.outcome = text
            if (!e.key.startsWith("table:")) pending.remove(h to !r.fromChip)
        }
        when (op) {
            0x01 -> {
                val asked = r.u8(9); val err = r.u8(12)
                val e = pending[h to !r.fromChip]
                // The table's end is told by "attribute not found": an answer like any other
                if (e != null && e.key.startsWith("table:") && asked in DISCOVERY && err == 0x0a) e.answers++
                else if (e != null && e.key.startsWith("table:")) { e.answers++; add(r.t, "", "$l refuses a table request (${ATT_ERR[err] ?: "error"}, ${hex(err)})") }
                else answer("REFUSED (${ATT_ERR[err] ?: "error"}, ${hex(err)})")
            }
            0x02 -> ask("", "$l asks for the packet size")
            0x04, 0x06, 0x08, 0x10 -> ask("table:$h:${r.fromChip}", "$l reads the table of services")
            0x0a, 0x0c -> ask("", "$l reads $at")
            0x0e, 0x20 -> ask("", "$l reads several values")
            0x12 -> ask("", "$l writes to $at")
            0x16 -> ask("", "$l writes a long value to $at")
            0x18 -> ask("", "$l ends a long write")
            0x1d -> ask("", "$l indicates on $at")
            0x03, 0x05, 0x07, 0x09, 0x11, 0x0b, 0x0d, 0x0f, 0x21, 0x13, 0x17, 0x19 -> answer("answered")
            0x1e -> answer("confirmed")
            0x52, 0xd2 -> add(r.t, "cmd:$h:${r.fromChip}:$at", "$l writes to $at without asking for an answer")
            0x1b, 0x23 -> add(r.t, "note:$h:${r.fromChip}:$at", "$l notifies on $at")
        }
    }
}
