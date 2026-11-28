// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HciPairingTest {
    /** Records as [HciRing] hands them over: an event or command whole, a data packet its first 14 bytes. */
    private class Rec {
        val list = ArrayList<HciRing.Rec>()
        private fun le(v: Int) = intArrayOf(v and 0xff, v shr 8 and 0xff)
        private fun put(ms: Double, fromChip: Boolean, kind: Int, d: IntArray, len: Int = d.size) {
            list += HciRing.Rec((ms * 1000).toLong() + 1_000_000, fromChip, kind, len, ByteArray(d.size) { d[it].toByte() })
        }
        fun event(ms: Double, code: Int, vararg p: Int) = apply { put(ms, true, 4, intArrayOf(code, p.size) + p) }
        fun command(ms: Double, op: Int, vararg p: Int) = apply { put(ms, false, 1, le(op) + intArrayOf(p.size) + p) }
        fun data(ms: Double, fromPhone: Boolean, link: Int, cid: Int, vararg payload: Int) = apply {
            val whole = le(link or ((if (fromPhone) 2 else 0) shl 12)) + le(payload.size + 4) + le(payload.size) + le(cid) + payload
            put(ms, fromPhone, 2, whole.copyOf(minOf(14, whole.size)), whole.size)
        }
        /** LE link up, the host its peripheral; [type] 1 = a random address, [addr] its six bytes (lowest first). */
        fun up(ms: Double, link: Int, type: Int = 1, addr: IntArray = intArrayOf(1, 2, 3, 4, 5, 0x7a)) =
            event(ms, 0x3e, 0x01, 0, *le(link), 1, type, *addr, *le(24), 0, 0, 0xf4, 0x01, 0)
        fun down(ms: Double, link: Int, reason: Int) = event(ms, 0x05, 0, *le(link), reason)
    }

    private val H = 0x41

    @Test fun nothingFromNothing() = assertTrue(HciPairing.lines(emptyList()).isEmpty())

    @Test fun aPairingThatWorks() {
        val r = Rec().up(0.0, H)
            .data(100.0, true, H, 4, 0x02, 0x05, 0x02).data(110.0, false, H, 4, 0x03, 0x05, 0x02)             // packet size
            .data(200.0, true, H, 4, 0x10, 1, 0, 0xff, 0xff).data(210.0, false, H, 4, 0x11, 6)               // table
            .data(220.0, true, H, 4, 0x08, 1, 0, 0xff, 0xff).data(230.0, false, H, 4, 0x01, 0x08, 1, 0, 0x0a) // its end
            .data(300.0, true, H, 4, 0x12, 43, 0, 1, 0).data(310.0, false, H, 4, 0x01, 0x12, 43, 0, 0x05)    // subscribe: not yet
            .data(400.0, true, H, 6, 0x01, 3, 0, 0x2d, 16, 0x0f, 0x0f).data(410.0, false, H, 6, 0x02, 3, 0, 0x2d, 16, 0x0f, 0x0f)
            .data(420.0, true, H, 6, 0x0c, 9, 9).data(430.0, false, H, 6, 0x0c, 9, 9).data(440.0, true, H, 6, 0x03, 9).data(450.0, false, H, 6, 0x04, 9)
            .event(500.0, 0x3e, 0x05, H, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0).command(501.0, 0x201a, H, 0, 9, 9, 9)
            .event(520.0, 0x08, 0, H, 0, 1)
            .data(530.0, true, H, 6, 0x09, 0, 1, 2, 3, 4, 5, 0x7a)
            .data(600.0, true, H, 4, 0x12, 43, 0, 1, 0).data(610.0, false, H, 4, 0x13)
            .data(620.0, true, H, 4, 0x12, 45, 0, 9, 9).data(630.0, false, H, 4, 0x13)
            .data(700.0, false, H, 4, 0x1b, 42, 0, 9).data(710.0, false, H, 4, 0x1b, 42, 0, 9)
        val l = HciPairing.lines(r.list)
        val all = l.joinToString("\n")
        assertTrue(all, l[0].endsWith("link 1 up (LE, host is peripheral), phone's address A: random, private (changes)"))
        assertTrue(all, l.any { it.endsWith("link 1: phone asks for the packet size: answered") })
        assertTrue(all, l.any { it.endsWith("link 1: phone reads the table of services (2 requests, 2 answered)") })
        assertTrue(all, l.any { it.endsWith("link 1: phone writes to 43: REFUSED (insufficient authentication, 0x05)") })
        assertTrue(all, l.any { it.endsWith("link 1: phone: pairing request (no input or output, bonding, protected from a man in the middle, secure connections, CT2, wants keys: encryption + identity + signing + the other radio's key)") })
        assertTrue(all, l.any { it.endsWith("link 1: pairing values exchanged (x4)") })
        assertTrue(all, l.any { it.endsWith("link 1: phone starts encryption with a new or secure-connections key") })
        assertTrue(all, l.any { it.endsWith("link 1: host has the key and gives it to the chip") })
        assertTrue(all, l.any { it.endsWith("link 1: encryption on") })
        assertTrue(all, l.any { it.endsWith("link 1: phone sends its identity address (public): the one it connected with") })
        assertTrue(all, l.any { it.endsWith("link 1: phone writes to 43: answered") })
        assertTrue(all, l.any { it.endsWith("link 1: phone writes to 45: answered") })
        assertTrue(all, l.last().endsWith("link 1: host notifies on 42 (x2)"))
        assertFalse(all, all.contains("NO ANSWER"))
        // Times count back from the record's last entry
        assertTrue(all, l[0].startsWith("-0.7 s "))
    }

    @Test fun aWriteTheHostNeverAnswersUntilThePhoneGivesUp() {
        val r = Rec().up(0.0, H).event(100.0, 0x08, 0, H, 0, 1)
            .data(1000.0, true, H, 4, 0x12, 45, 0, 7, 7)
            .down(31_000.0, H, 0x13)
        val l = HciPairing.lines(r.list)
        assertTrue(l.joinToString("\n"), l.any { it.endsWith("link 1: phone writes to 45: NO ANSWER: the link ended 30.0 s later") })
        assertTrue(l.last().endsWith("link 1 down (the phone ended it, 0x13)"))
    }

    @Test fun aRequestStillWaitingWhenTheRecordEnds() {
        val r = Rec().up(0.0, H).data(1000.0, true, H, 4, 0x02, 0x05, 0x02).event(7000.0, 0x3e, 0x03, 0, H, 0, 24, 0)
        val l = HciPairing.lines(r.list)
        assertTrue(l.joinToString("\n"), l.any { it.endsWith("link 1: phone asks for the packet size: NO ANSWER in the 6.0 s the record goes on (link still up)") })
    }

    @Test fun aKeyTheHostDoesNotHave() {
        val r = Rec().up(0.0, H, type = 2).event(100.0, 0x3e, 0x05, H, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 9).command(101.0, 0x201b, H, 0)
            .down(200.0, H, 0x13)
        val l = HciPairing.lines(r.list)
        assertTrue(l[0], l[0].endsWith("phone's address A: private, and the host holds the key that resolves it (public identity)"))
        assertTrue(l.any { it.endsWith("link 1: phone starts encryption with a stored legacy key") })
        assertTrue(l.any { it.endsWith("link 1: host has NO key for this phone") })
    }

    @Test fun aPairingThatFailsAndAnotherAddress() {
        val r = Rec().up(0.0, H)
            .data(100.0, true, H, 6, 0x01, 3, 0, 0x09, 16, 0x0f, 0x0f)
            .data(200.0, false, H, 6, 0x05, 0x0b)
            .down(300.0, H, 0x16)
            .up(1000.0, H, addr = intArrayOf(9, 9, 9, 9, 9, 0x7b))
            .data(1100.0, true, H, 6, 0x09, 0, 6, 6, 6, 6, 6, 6)
            .event(1200.0, 0x08, 0x06, H, 0, 0)
        val l = HciPairing.lines(r.list)
        val all = l.joinToString("\n")
        assertTrue(all, l.any { it.endsWith("link 1: host: pairing FAILED (key check failed, 0x0b)") })
        assertTrue(all, l.any { it.endsWith("link 1 down (the host ended it, 0x16)") })
        assertTrue(all, l.any { it.endsWith("link 2 up (LE, host is peripheral), phone's address B: random, private (changes)") })
        assertTrue(all, l.any { it.endsWith("link 2: phone sends its identity address (public): not the one it connected with, an address not seen before") })
        assertTrue(all, l.last().endsWith("link 2: encryption FAILED (key missing, 0x06)"))
    }

    @Test fun theClassicRadioJoiningIn() {
        val a = intArrayOf(1, 2, 3, 4, 5, 6)
        val r = Rec().event(0.0, 0x04, *a, 0x0c, 0x02, 0x5a, 1).command(10.0, 0x0409, *a, 1)
            .event(100.0, 0x03, 0, 0x02, 0, *a, 1, 0)
            .event(200.0, 0x17, *a).command(201.0, 0x040c, *a)
            .data(300.0, true, 0x02, 1, 0x02, 1, 4, 0, 0x03, 0, 0x40, 0)
        val l = HciPairing.lines(r.list)
        assertEquals(listOf("-0.3 s classic: address A asks for a link", "-0.3 s classic: host accepts the link", "-0.2 s link 1 up (classic), phone's address A",
            "-0.1 s classic: the chip asks for the key", "-0.1 s classic: host has NO key for this phone",
            "-0.0 s link 1: phone opens PSM 0x03 (RFCOMM: hands-free, phone book, messages)"), l)
    }

    @Test fun enhancedChannelsAndOtherTraffic() {
        val r = Rec().up(0.0, H).data(100.0, true, H, 5, 0x17, 1, 8, 0, 0x27, 0).data(110.0, false, H, 5, 0x18, 1)
            .data(200.0, true, H, 0x41, 2, 0, 0x12).data(210.0, false, H, 0x40, 1, 0, 0x13).data(220.0, true, H, 0x41, 2, 0, 0x12)
        val l = HciPairing.lines(r.list)
        assertTrue(l.joinToString("\n"), l.any { it.endsWith("link 1: phone opens enhanced channels (PSM 0x27: enhanced control channels)") })
        assertTrue(l.last(), l.last().endsWith("link 1: 2 packets in and 1 out on data channels so far"))
    }

    @Test fun onlyTheLastSecondsAndNoMoreThanAsked() {
        val r = Rec().up(0.0, H)
        for (i in 0 until 50) r.data(200_000.0 + i * 100, true, H, 4, 0x12, 40 + i, 0).data(200_010.0 + i * 100, false, H, 4, 0x13)
        val l = HciPairing.lines(r.list, seconds = 120, max = 10)
        assertEquals(10, l.size)
        assertEquals("... 41 earlier lines left out", l[0])
        // The link came up before the window: it keeps its name, its line is not there
        assertTrue(l.last(), l.last().endsWith("link 1: phone writes to 89: answered"))
    }

    @Test fun noAddressOrKeyBytesInTheLines() {
        val r = Rec().up(0.0, H, addr = intArrayOf(0xde, 0xad, 0xbe, 0xef, 0x12, 0x7c))
            .data(100.0, true, H, 6, 0x09, 0, 0xde, 0xad, 0xbe, 0xef, 0x12, 0x7c).command(200.0, 0x201a, H, 0, 0xaa, 0xbb, 0xcc, 0xdd)
        val all = HciPairing.lines(r.list).joinToString("\n").lowercase()
        for (b in listOf("de", "ad:", "be", "ef", "aa", "bb", "cc", "dd")) assertFalse(all, Regex("""\b$b\b""").containsMatchIn(all))
    }
}

class PairWatchTest {
    private val day = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT)
    private fun at(s: String) = day.parse("2026-11-28 $s")!!.time

    @Test fun theBluetoothStacksOwnLinesScrubbed() {
        val log = """
            11-28 15:17:00.000 W/bt_btif ( 2222): too old to matter
            11-28 15:18:02.100 I/bt_btif_dm( 2222): bond_state_changed: state=1, prev_state=0 for AA:BB:CC:DD:EE:FF
            11-28 15:18:02.200 I/bt_stack( 2222): [INFO:gatt_sr.cc(1320)] database out of sync, device=xx:xx:xx:xx:ee:ff, op_code=0x12
            11-28 15:18:02.300 D/BluetoothAdapterService( 2222): something routine
            11-28 15:18:02.400 E/bt_smp  ( 2222): smp_proc_pair_fail reason 0x0b key 00112233445566778899aabbccddeeff
            11-28 15:18:02.500 I/BluetoothBondStateMachine( 2222): sspRequestCallback: name: Somebody's Galaxy
            11-28 15:18:02.600 W/bt_btm_sec( 2222): btm_sec_encrypt_change status 6 bda aabbccddeeff
            not a log line
        """.trimIndent()
        val l = PairWatch.stackLog(log, at("15:17:30.000"), at("15:19:00.000"))
        assertEquals(listOf(
            "15:18:02.100 I bt_btif_dm: bond_state_changed: state=1, prev_state=0 for <mac>",
            "15:18:02.200 I bt_stack: [INFO:gatt_sr.cc(1320)] database out of sync, device=<mac>, op_code=0x12",
            "15:18:02.400 E bt_smp: smp_proc_pair_fail reason 0x0b key <hex>",
            "15:18:02.600 W bt_btm_sec: btm_sec_encrypt_change status 6 bda <hex>"), l)
    }

    @Test fun theDumpsRecordReadsLikeAFile() {
        // Android 12's dump text: a version byte, the last time (8 bytes), then the compressed entries
        val body = java.io.ByteArrayOutputStream()
        fun le(v: Int, n: Int) { for (i in 0 until n) body.write(v shr (8 * i) and 0xff) }
        fun packet(deltaMs: Int, kind: Int, real: Int, vararg d: Int) { le(d.size + 1, 2); le(real, 2); le(deltaMs, 4); body.write(kind); d.forEach { body.write(it) } }
        for (i in 0 until 20) {
            packet(10, 0x11, 15, 0x41, 0x20, 11, 0, 7, 0, 4, 0, 0x12, 45, 0, 1, 2, 3)     // data in: a write request to 45
            packet(1, 0x21, 9, 0x41, 0x00, 5, 0, 1, 0, 4, 0, 0x13)                          // data out: its answer
        }
        val packed = java.io.ByteArrayOutputStream().also { o -> java.util.zip.DeflaterOutputStream(o).use { it.write(body.toByteArray()) } }.toByteArray()
        val blob = byteArrayOf(2) + ByteArray(8) + packed
        val (recs, how) = HciRing.read(HciRing.FILE_HEAD + blob)
        assertEquals(40, recs.size)
        assertEquals("compressed from 25, old layout 2", how)
        val l = HciPairing.lines(recs)
        assertEquals(1, l.size)
        assertTrue(l[0], l[0].endsWith("link 0x41: phone writes to 45: answered (x20)"))
    }
}

class HciPairingTimeTest {
    @Test fun timesCountBackFromNowWhenTheRecordsEndIsKnown() {
        val recs = listOf(HciRing.Rec(1_000_000, true, 4, 6, byteArrayOf(0x05, 4, 0, 0x41, 0, 0x13)), HciRing.Rec(3_000_000, true, 4, 6, byteArrayOf(0x05, 4, 0, 0x42, 0, 0x08)))
        assertEquals(listOf("-14.5 s link 0x41 down (the phone ended it, 0x13)", "-12.5 s link 0x42 down (timeout: the radio lost it, 0x08)"), HciPairing.lines(recs, endAgo = 12.5))
        // A dump's lead: version, then the last entry's time in ms, lowest byte first
        assertEquals(0x0102L, HciRing.dumpEndMs(byteArrayOf(2, 2, 1, 0, 0, 0, 0, 0, 0, 9, 9)))
        assertEquals(0L, HciRing.dumpEndMs(byteArrayOf(2, 2)))
    }
}
