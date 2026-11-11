// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

/**
 * What the host's Bluetooth software and its chip said to each other in a call's last seconds, as a few numbers.
 *
 * Android keeps a short record of that traffic in memory and writes it to a file whenever its Bluetooth dump runs
 * (/data/misc/bluetooth/logs/btsnooz_hci.log, a btsnoop file; seen on Android 14, 10 Nov 2026). Commands and the
 * chip's events are whole, a data packet is its first 14 bytes: the link, its length, never a call's audio or a text.
 * The host already runs that dump at a call's end ([LinkRadio]); this reads the file and keeps numbers only.
 *
 * Why: a Galaxy A13 host hands the caller's audio to its Bluetooth on time and the phone gets about half of it, or
 * all of it late and in clumps (7 to 10 Nov 2026). The app's own counters see nothing of it, three guesses at the
 * cause were wrong (joined writes, two frames a packet, the 1M radio mode), and the same host's calls over the
 * internet are clean. What tells the cause sits here: how many packets a second the chip was handed and how many it
 * says it sent, how long each waited in the chip, and how long a radio packet the link allows each way.
 *
 * Everything here is pure (HciRingTest).
 */
object HciRing {
    /** One record: its time (µs), who said it, its kind (1 command, 2 data, 4 event), its real length, the bytes kept. */
    class Rec(val t: Long, val fromChip: Boolean, val kind: Int, val len: Int, val d: ByteArray) {
        fun u8(i: Int) = if (i < d.size) d[i].toInt() and 0xff else 0
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        /** A data packet's link (the low 12 bits) and whether it starts a new L2CAP packet. */
        val handle get() = u16(0) and 0x0fff
        val starts get() = (u16(0) shr 12) and 3 != 1
    }

    /** The file's records in order; empty when it isn't a btsnoop file of serial-line (H4) packets. */
    fun records(file: ByteArray): List<Rec> = read(file).first

    /**
     * The file's records and how they were found ("" = as Android writes them). A Galaxy A13 has the file with the right
     * name and header in Samsung's own folder, and what follows the header is not Android's layout (11 Nov 2026, seen
     * only as "not readable"). So the reader tries what a vendor could plausibly have done: the same records starting
     * later or with their numbers the other way round, or the whole record compressed (as Android's memory record was
     * before it became a file), in either of the two layouts. When nothing fits, [probe] says what the bytes look like.
     */
    fun read(file: ByteArray): Pair<List<Rec>, String> {
        if (file.size < 16 || String(file, 0, 7, Charsets.US_ASCII) != "btsnoop" || be32(file, 12) != 1002L) return emptyList<Rec>() to ""
        chain(file, 16, false).let { if (it.isNotEmpty()) return it to "" }
        resync(file, 16)?.let { return it }
        for ((at, plain) in inflated(file)) {
            chain(plain, 0, false).let { if (it.size >= 8) return it to "compressed from $at" }
            resync(plain, 0)?.let { return it.first to "compressed from $at, ${it.second}" }
            // Android 12's memory record: a byte of version and the last packet's time before the compressed part
            val lastMs = if (at >= 16 + 9) le64(file, at - 8) else 0L
            for (v in intArrayOf(2, 1)) old(plain, v, lastMs).let { if (it.size >= 8) return it to "compressed from $at, old layout $v" }
        }
        return emptyList<Rec>() to ""
    }

    /** A record's time is microseconds since the year 0: its top two bytes are these from 2024 to 2042. */
    private fun timely(t: Long) = (t ushr 48) == 0xe3L || (t ushr 48) == 0xe4L

    /**
     * Records one after another from [from], as far as they hold; [le]: the header's numbers little-endian. Nothing when
     * the first one already isn't a record (a length that can't be, a time from another age).
     */
    private fun chain(b: ByteArray, from: Int, le: Boolean): List<Rec> {
        fun u32(o: Int) = if (le) le32(b, o) else be32(b, o)
        val out = ArrayList<Rec>()
        var o = from
        while (o + 24 <= b.size) {
            val orig = u32(o); val incl = u32(o + 4); val flags = u32(o + 8)
            val t = if (le) le64(b, o + 16) else (be32(b, o + 16) shl 32) or be32(b, o + 20)
            if (incl < 1 || incl > orig || orig > 70_000 || flags > 3 || !timely(t)) break
            o += 24
            if (o + incl > b.size) break                                    // cut off mid-record: what came before stands
            out += Rec(t, flags and 1L == 1L, b[o].toInt() and 0xff, (orig - 1).toInt(), b.copyOfRange(o + 1, (o + incl).toInt()))
            o += incl.toInt()
        }
        return out
    }

    /** The first place within 8 KiB after [from] where eight records in a row start, either byte order. */
    private fun resync(b: ByteArray, from: Int): Pair<List<Rec>, String>? {
        for (le in booleanArrayOf(false, true)) for (o in from until minOf(b.size - 24, from + 8192)) {
            if (o == from && !le) continue
            val r = chain(b, o, le)
            if (r.size >= 8) return r to "records from $o${if (le) ", little-endian" else ""}"
        }
        return null
    }

    /** Every compressed stream that starts in the first bytes after the header and unpacks to something: (where, what). */
    private fun inflated(file: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        for (at in 16 until minOf(file.size - 2, 96)) {
            val b0 = file[at].toInt() and 0xff; val b1 = file[at + 1].toInt() and 0xff
            val zlib = b0 and 0x0f == 8 && (b0 shr 4) <= 7 && ((b0 shl 8) or b1) % 31 == 0
            val gzip = b0 == 0x1f && b1 == 0x8b
            if (!zlib && !gzip) continue
            // Whatever unpacks before the stream ends or breaks: a record cut short is still a record
            val plain = java.io.ByteArrayOutputStream()
            runCatching {
                val src = file.inputStream(at, file.size - at)
                (if (gzip) java.util.zip.GZIPInputStream(src) else java.util.zip.InflaterInputStream(src)).use { z ->
                    val buf = ByteArray(16_384)
                    while (plain.size() < MAX_PLAIN) { val n = z.read(buf); if (n <= 0) break; plain.write(buf, 0, n) }
                }
            }
            if (plain.size() >= 64) out += at to plain.toByteArray()
        }
        return out
    }
    private const val MAX_PLAIN = 8 * 1024 * 1024

    /**
     * Android 12's memory record once unpacked: per packet its kept length + 1, (from version 2) its real length, the
     * milliseconds since the packet before, and a kind (0x10 event, 0x11 data in, 0x20 command, 0x21 data out), all
     * little-endian, then the bytes kept. Nothing unless the whole of [b] reads that way. [lastMs]: the last packet's time.
     */
    private fun old(b: ByteArray, version: Int, lastMs: Long): List<Rec> {
        val head = if (version == 2) 9 else 7
        class E(val len: Int, val orig: Int, val delta: Long, val type: Int, val at: Int)
        val es = ArrayList<E>()
        var o = 0
        while (o + head <= b.size) {
            val len = le16(b, o)
            val orig = if (version == 2) le16(b, o + 2) else len - 1
            val delta = le32(b, o + head - 5); val type = b[o + head - 1].toInt() and 0xff
            if (len < 1 || type !in OLD_KINDS || o + head + len - 1 > b.size) return emptyList()
            es += E(len, orig, delta, type, o + head)
            o += head + len - 1
        }
        if (o != b.size) return emptyList()
        var t = (lastMs - es.sumOf { it.delta }) * 1000
        return es.map { e ->
            t += e.delta * 1000
            Rec(t, e.type < 0x20, OLD_KINDS.getValue(e.type), e.orig, b.copyOfRange(e.at, e.at + e.len - 1))
        }
    }
    private val OLD_KINDS = mapOf(0x10 to 4, 0x11 to 2, 0x12 to 3, 0x17 to 5, 0x20 to 1, 0x21 to 2, 0x22 to 3, 0x2d to 5)

    /**
     * What an unreadable file looks like, for whoever has to teach the reader: the 32 bytes after the header (a record's
     * header and the first bytes of a packet: its kind, never an address or content), where times stand in the first
     * kilobytes (they give the records' spacing), and how a compressed stream found in it starts.
     */
    fun probe(file: ByteArray): String {
        fun hex(b: ByteArray, from: Int, n: Int) = (from until minOf(b.size, from + n)).joinToString("") { "%02x".format(b[it]) }
        fun marks(b: ByteArray): String {
            val be = ArrayList<Int>(); val le = ArrayList<Int>()
            for (o in 0 until minOf(b.size - 8, 4096)) {
                if (b[o].toInt() == 0 && (b[o + 1].toInt() and 0xff).let { it == 0xe3 || it == 0xe4 }) be += o
                if (b[o + 7].toInt() == 0 && (b[o + 6].toInt() and 0xff).let { it == 0xe3 || it == 0xe4 }) le += o
            }
            return "times at ${be.take(8).joinToString(" ").ifEmpty { "-" }} / reversed ${le.take(8).joinToString(" ").ifEmpty { "-" }}"
        }
        val packed = inflated(file).firstOrNull()
        return "${file.size} B, after the header ${hex(file, 16, 32)}, ${marks(file)}" +
            (packed?.let { (at, plain) -> ", compressed from $at: ${plain.size} B starting ${hex(plain, 0, 24)}, ${marks(plain)}" } ?: ", nothing compressed")
    }

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)
    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int): Long = le16(b, o).toLong() or (le16(b, o + 2).toLong() shl 16)
    private fun le64(b: ByteArray, o: Int): Long = le32(b, o) or (le32(b, o + 4) shl 32)

    // ---- what a link agreed

    /** What one link agreed with the other side, as far as the record holds it. 0 / "" = not seen. */
    data class Link(val role: String = "", val intervalMs: Double = 0.0, val txBytes: Int = 0, val txUs: Int = 0, val rxBytes: Int = 0,
                    val rxUs: Int = 0, val askedTxBytes: Int = 0, val phy: String = "", val sinceConnect: Boolean = false) {
        /**
         * In words for a call's numbers. A link that never changed its packet length since it connected runs on the
         * smallest one, 27 bytes: said so only when the record holds the link's start.
         */
        fun text(): String = listOfNotNull(
            role.ifEmpty { null },
            if (intervalMs > 0) "every ${"%.2f".format(java.util.Locale.ROOT, intervalMs).trimEnd('0').trimEnd('.')} ms" else null,
            when {
                txBytes > 0 -> "packets out ${txBytes} B / ${txUs} us, in ${rxBytes} B / ${rxUs} us"
                sinceConnect -> "packets 27 B both ways (length never changed)"
                else -> "packet length not in the record"
            },
            if (askedTxBytes > 0) "host asked ${askedTxBytes} B" else null,
            phy.ifEmpty { null }).joinToString(", ")
    }

    /**
     * Every link in [recs], on top of what was [known] before (an earlier look, taken when the link came up: by a
     * call's end the record no longer holds that). A link that connects in [recs] starts empty, one that ends is gone.
     */
    fun links(recs: List<Rec>, known: Map<Int, Link> = emptyMap()): Map<Int, Link> {
        val m = HashMap(known)
        fun upd(h: Int, f: (Link) -> Link) { m[h] = f(m[h] ?: Link()) }
        for (r in recs) when (r.kind) {
            1 -> if (r.u16(0) == 0x2022) upd(r.u16(3) and 0x0fff) { it.copy(askedTxBytes = r.u16(5)) }          // LE Set Data Length
            4 -> when (r.u8(0)) {
                0x05 -> if (r.u8(2) == 0) m.remove(r.u16(3) and 0x0fff)                                          // disconnected
                0x3e -> when (r.u8(2)) {
                    // LE connection complete, and its two longer forms (the interval sits after two more addresses)
                    0x01, 0x0a, 0x29 -> if (r.u8(3) == 0) m[r.u16(4) and 0x0fff] = Link(
                        role = if (r.u8(6) == 1) "peripheral" else "central",
                        intervalMs = r.u16(if (r.u8(2) == 0x01) 14 else 26) * 1.25, sinceConnect = true)
                    0x03 -> if (r.u8(3) == 0) upd(r.u16(4) and 0x0fff) { it.copy(intervalMs = r.u16(6) * 1.25) }
                    0x07 -> upd(r.u16(3) and 0x0fff) { it.copy(txBytes = r.u16(5), txUs = r.u16(7), rxBytes = r.u16(9), rxUs = r.u16(11)) }
                    0x0c -> if (r.u8(3) == 0) upd(r.u16(4) and 0x0fff) { it.copy(phy = "${LinkRadio.phyName(r.u8(6))}/${LinkRadio.phyName(r.u8(7))}") }
                }
            }
        }
        return m
    }

    /** The chip's own packet room when the record holds its answer ("251 B x 8"), else "". */
    fun buffers(recs: List<Rec>): String {
        var s = ""
        for (r in recs) if (r.kind == 4 && r.u8(0) == 0x0e && r.u8(5) == 0) when (r.u16(3)) {
            0x2002, 0x2060 -> if (r.u16(6) > 0) s = "${r.u16(6)} B x ${r.u8(8)}"                                // LE Read Buffer Size
            0x1005 -> if (s.isEmpty() && r.u16(6) > 0) s = "${r.u16(6)} B x ${r.u16(9)} (shared)"               // the chip has one pool
        }
        return s
    }

    // ---- how the sending went

    /**
     * The busiest link's last seconds. [handed]: data packets the host's Bluetooth gave the chip; [sent]: packets the
     * chip said it had sent, in [reports] reports; [waitMs]: how long a packet sat in the chip (median, 95%, longest);
     * [queueMax]: most packets in the chip at once; [pauseMs]: longest time without a report; [handPauseMs]: longest
     * time the host's Bluetooth handed the chip nothing (the stream is a packet every 10 ms); [handedHalf] and
     * [sentHalf]: fewest and most in a half second; [received]: packets from the other side; [credits]: packets in
     * which the other side allowed more audio packets (the amount itself is cut off).
     */
    data class Sending(val handle: Int, val seconds: Double, val handed: Int, val bytesAvg: Int, val bytesMax: Int, val sent: Int,
                       val reports: Int, val waitMs: Triple<Int, Int, Int>, val queueMax: Int, val pauseMs: Int, val handPauseMs: Int,
                       val handedHalf: Pair<Int, Int>, val sentHalf: Pair<Int, Int>, val received: Int, val credits: Int) {
        private fun perS(n: Int) = if (seconds > 0) Math.round(n / seconds).toInt() else 0

        /**
         * The audio left late: one packet in twenty waited a tenth of a second or more in the chip, or the host's
         * Bluetooth handed the chip nothing for that long, or some half second got under two thirds of the usual. Only
         * with enough to judge (two seconds of a stream).
         */
        val late: Boolean get() = handed >= 200 && seconds >= 2 &&
            (waitMs.second >= 100 || handPauseMs >= 100 || (handedHalf.second > 0 && handedHalf.first * 3 < handed / (seconds * 2) * 2))

        fun text(): String = "last ${"%.1f".format(java.util.Locale.ROOT, seconds)} s: " +
            (if (handed == 0) "handed: no data packets in the record, chip sent"
             else "handed ${perS(handed)}/s ($bytesAvg B, longest $bytesMax B, per half second ${handedHalf.first}-${handedHalf.second}, " +
                "longest pause $handPauseMs ms), chip sent") + " ${perS(sent)}/s in ${perS(reports)} reports/s " +
            "(per half second ${sentHalf.first}-${sentHalf.second}), waited in the chip ${waitMs.first}/${waitMs.second}/${waitMs.third} ms, " +
            "most waiting $queueMax, longest pause $pauseMs ms, received ${perS(received)}/s, credit packets $credits"
    }

    private const val WINDOW_US = 10_000_000L
    private const val HALF_US = 500_000L

    /**
     * [Sending] of the link the host sent most data on, over the last ten seconds it sent; null when it sent none.
     * A record that keeps the chip's reports but no data packets still gives what the chip sent (handed 0).
     */
    fun sending(recs: List<Rec>): Sending? {
        val out = recs.filter { it.kind == 2 && !it.fromChip && it.d.size >= 4 }
        /** What a record of the chip says it sent on [link]: 0 unless it is such a report. */
        fun reported(r: Rec, link: Int): Int {
            if (r.kind != 4 || r.u8(0) != 0x13) return 0
            return (0 until r.u8(2)).sumOf { i -> if (r.u16(3 + i * 4) and 0x0fff == link) r.u16(5 + i * 4) else 0 }
        }
        val h = out.groupingBy { it.handle }.eachCount().maxByOrNull { it.value }?.key ?: run {
            val perLink = HashMap<Int, Int>()
            for (r in recs) if (r.kind == 4 && r.u8(0) == 0x13) for (i in 0 until r.u8(2)) perLink.merge(r.u16(3 + i * 4) and 0x0fff, r.u16(5 + i * 4), Int::plus)
            perLink.maxByOrNull { it.value }?.key ?: return null
        }
        val t1 = out.lastOrNull { it.handle == h }?.t ?: recs.last { reported(it, h) > 0 }.t
        val t0 = maxOf(recs.first().t, t1 - WINDOW_US)
        if (t1 <= t0) return null
        // Packets already in the chip when the record starts are reported too: without them every later packet would
        // look sent a few places early. Their number is how far the reports ever run ahead of what the record saw handed.
        var ahead = 0
        run {
            var sentSoFar = 0; var doneSoFar = 0
            for (r in recs) {
                if (r.kind == 2 && !r.fromChip && r.d.size >= 4 && r.handle == h) sentSoFar++
                else reported(r, h).let { if (it > 0) { doneSoFar += it; ahead = maxOf(ahead, doneSoFar - sentSoFar) } }
            }
        }

        val queue = ArrayDeque<Long>().apply { repeat(ahead) { addLast(-1L) } }
        val waits = ArrayList<Int>()
        val bins = ((t1 - t0) / HALF_US).toInt()                           // whole half seconds, counted back from the end
        val handedBin = IntArray(bins); val sentBin = IntArray(bins)
        fun bin(t: Long) = if (t in (t0 + 1)..t1) bins - 1 - ((t1 - t) / HALF_US).toInt() else -1
        var handed = 0; var bytes = 0L; var bytesMax = 0; var sent = 0; var reports = 0; var queueMax = 0
        var received = 0; var credits = 0; var pause = 0L; var lastReport = 0L; var handPause = 0L; var lastHanded = 0L
        fun report(t: Long, n: Int) {
            repeat(n) { val s = queue.removeFirstOrNull() ?: return@repeat; if (s >= t0 && t <= t1 + 1_000_000) waits += ((t - s) / 1000).toInt() }
            if (t !in (t0 + 1)..t1) return
            sent += n; reports++
            bin(t).let { if (it in 0 until bins) sentBin[it] += n }
            if (lastReport != 0L) pause = maxOf(pause, t - lastReport)
            lastReport = t
        }
        for (r in recs) {
            // In the record's own order: a packet and its report can carry the same time
            reported(r, h).let { if (it > 0) report(r.t, it) }
            if (r.kind != 2 || r.d.size < 4 || r.handle != h) continue
            val inWindow = r.t in (t0 + 1)..t1
            if (r.fromChip) {
                if (!inWindow) continue
                received++
                // LE signalling (channel 5), "flow control credit" (0x16): the other side lets more audio packets come
                if (r.starts && r.u16(6) == 5 && r.u8(8) == 0x16) credits++
                continue
            }
            queue.addLast(r.t)
            if (!inWindow) continue
            if (lastHanded != 0L) handPause = maxOf(handPause, r.t - lastHanded)
            lastHanded = r.t
            handed++; bytes += r.u16(2); bytesMax = maxOf(bytesMax, r.u16(2)); queueMax = maxOf(queueMax, queue.size)
            bin(r.t).let { if (it in 0 until bins) handedBin[it]++ }
        }
        if (handed == 0 && sent == 0) return null
        waits.sort()
        fun at(q: Double) = if (waits.isEmpty()) 0 else waits[((waits.size - 1) * q).toInt()]
        fun span(a: IntArray) = (a.minOrNull() ?: 0) to (a.maxOrNull() ?: 0)
        return Sending(h, (t1 - t0) / 1e6, handed, if (handed > 0) (bytes / handed).toInt() else 0, bytesMax, sent, reports, Triple(at(0.5), at(0.95), at(1.0)),
            queueMax, (pause / 1000).toInt(), (handPause / 1000).toInt(), span(handedBin), span(sentBin), received, credits)
    }

    /** What the record holds, for when it says nothing about a link: "174 records over 10.2 s". */
    fun size(recs: List<Rec>): String =
        if (recs.isEmpty()) "0 records" else "${recs.size} records over ${"%.1f".format(java.util.Locale.ROOT, (recs.last().t - recs.first().t) / 1e6)} s"
}
