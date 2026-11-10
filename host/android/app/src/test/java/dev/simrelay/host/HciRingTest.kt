// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class HciRingTest {
    /** A btsnoop file as Android writes it: big-endian record headers, each packet led by its kind byte. */
    private class File(val datalink: Int = 1002) {
        private val out = ByteArrayOutputStream()
        private fun be32(v: Long) { for (s in intArrayOf(24, 16, 8, 0)) out.write((v shr s).toInt() and 0xff) }
        init { out.write("btsnoop".toByteArray()); out.write(0); be32(1); be32(datalink.toLong()) }
        private val t0 = 0x00E33DA52CE4FDBCL                                        // a time as the emulator wrote it
        fun rec(ms: Double, fromChip: Boolean, kind: Int, body: IntArray, realLen: Int = body.size): File {
            val t = t0 + (ms * 1000).toLong()
            be32(realLen + 1L); be32(body.size + 1L); be32((if (fromChip) 1L else 0L) or (if (kind == 2) 0L else 2L)); be32(0)
            be32(t ushr 32); be32(t and 0xffffffffL)
            out.write(kind); body.forEach { out.write(it) }
            return this
        }
        private fun le16(v: Int) = intArrayOf(v and 0xff, v shr 8 and 0xff)
        fun event(ms: Double, code: Int, vararg params: Int) = rec(ms, true, 4, intArrayOf(code, params.size) + params)
        fun command(ms: Double, opcode: Int, vararg params: Int) = rec(ms, false, 1, le16(opcode) + intArrayOf(params.size) + params)
        /** A data packet the way the record keeps it: the first 14 bytes, the real length beside it. */
        fun data(ms: Double, fromChip: Boolean, handle: Int, l2capLen: Int, cid: Int = 0x40, vararg more: Int): File {
            val pb = if (fromChip) 2 else 0
            val whole = le16(handle or (pb shl 12)) + le16(l2capLen + 4) + le16(l2capLen) + le16(cid) + more + IntArray(6) { 0 }
            return rec(ms, fromChip, 2, whole.copyOf(14), 4 + 4 + l2capLen)
        }
        fun completed(ms: Double, handle: Int, n: Int) = event(ms, 0x13, 1, *le16(handle), *le16(n))
        fun bytes(): ByteArray = out.toByteArray()
        fun le(v: Int) = le16(v)
    }

    private val H = 0x0002

    @Test fun notARecordFile() {
        assertTrue(HciRing.records(ByteArray(0)).isEmpty())
        assertTrue(HciRing.records("hello, this is no btsnoop file at all".toByteArray()).isEmpty())
        assertTrue(HciRing.records(File(datalink = 1001).event(0.0, 0x13, 0).bytes()).isEmpty())   // not serial-line packets
    }

    @Test fun aFileCutMidRecordKeepsWhatCameBefore() {
        val whole = File().completed(0.0, H, 1).completed(5.0, H, 1).bytes()
        assertEquals(2, HciRing.records(whole).size)
        assertEquals(1, HciRing.records(whole.copyOf(whole.size - 3)).size)
    }

    @Test fun whatALinkAgreed() {
        val f = File()
        // LE enhanced connection complete: status 0, handle, role 1 = peripheral, address type + 3 addresses, interval 24 x 1.25 ms
        f.event(0.0, 0x3e, 0x0a, 0, *f.le(H), 1, 0, *IntArray(18) { 0x11 }, *f.le(24), 0, 0, 0xf4, 0x01, 0)
        f.command(300.0, 0x2022, *f.le(H), *f.le(251), *f.le(2120))                               // LE Set Data Length
        f.event(320.0, 0x3e, 0x07, *f.le(H), *f.le(251), *f.le(2120), *f.le(251), *f.le(2120))   // data length change
        f.event(400.0, 0x3e, 0x0c, 0, *f.le(H), 2, 2)                                             // radio mode 2M both ways
        f.event(900.0, 0x3e, 0x03, 0, *f.le(H), *f.le(12), 0, 0, 0xf4, 0x01)                     // interval 12 x 1.25 ms
        val l = HciRing.links(HciRing.records(f.bytes()))
        assertEquals(setOf(H), l.keys)
        assertEquals("peripheral, every 15 ms, packets out 251 B / 2120 us, in 251 B / 2120 us, host asked 251 B, 2M/2M", l[H]!!.text())
    }

    @Test fun aLinkThatNeverChangedItsPacketLengthSaysSo() {
        val f = File()
        f.event(0.0, 0x3e, 0x01, 0, *f.le(H), 1, 0, *IntArray(6) { 0x11 }, *f.le(36), 0, 0, 0xf4, 0x01, 0)
        assertEquals("peripheral, every 45 ms, packets 27 B both ways (length never changed)", HciRing.links(HciRing.records(f.bytes()))[H]!!.text())
        // Without the link's start in the record nothing can be said about its packet length
        val later = File().event(0.0, 0x3e, 0x03, 0, *f.le(H), *f.le(12), 0, 0, 0xf4, 0x01)
        assertEquals("every 15 ms, packet length not in the record", HciRing.links(HciRing.records(later.bytes()))[H]!!.text())
    }

    @Test fun anEarlierLookFillsWhatACallPushedOut() {
        val known = mapOf(H to HciRing.Link(role = "peripheral", intervalMs = 30.0, txBytes = 27, txUs = 328, rxBytes = 251, rxUs = 2120))
        val f = File()
        f.event(0.0, 0x3e, 0x03, 0, *f.le(H), *f.le(12), 0, 0, 0xf4, 0x01)
        assertEquals("peripheral, every 15 ms, packets out 27 B / 328 us, in 251 B / 2120 us", HciRing.links(HciRing.records(f.bytes()), known)[H]!!.text())
        // The link ended: nothing is known of the next one on that number
        val gone = File().event(0.0, 0x05, 0, *f.le(H), 0x13)
        assertTrue(HciRing.links(HciRing.records(gone.bytes()), known).isEmpty())
    }

    @Test fun theChipsRoom() {
        val f = File()
        f.event(0.0, 0x0e, 1, *f.le(0x2002), 0, *f.le(251), 8)
        assertEquals("251 B x 8", HciRing.buffers(HciRing.records(f.bytes())))
        assertEquals("", HciRing.buffers(HciRing.records(File().completed(0.0, H, 1).bytes())))
    }

    /** Ten seconds of a stream: a packet every 10 ms, the chip reporting [perReport] of them every [reportMs]. */
    private fun stream(reportMs: Double, perReport: Int, seconds: Int = 10): File {
        val f = File()
        var nextReport = reportMs; var waiting = 0
        for (i in 0..seconds * 100) {
            val t = i * 10.0
            while (nextReport <= t) { val n = minOf(perReport, waiting); if (n > 0) f.completed(nextReport, H, n); waiting -= n; nextReport += reportMs }
            f.data(t + 0.5, false, H, 48); waiting++
            f.data(t + 0.7, true, H, 40)
        }
        return f
    }

    @Test fun aChipThatKeepsUp() {
        // Every 15 ms the chip reports all it holds: nothing waits longer than that
        val s = HciRing.sending(HciRing.records(stream(15.0, 4).bytes()))!!
        assertEquals(H, s.handle)
        assertEquals(10.0, s.seconds, 0.01)
        assertEquals(1000, s.handed); assertEquals(52, s.bytesAvg); assertEquals(52, s.bytesMax)
        assertTrue(s.sent in 995..1000)
        assertTrue("waited ${s.waitMs}", s.waitMs.third <= 15)
        assertTrue(s.queueMax <= 2)
        assertEquals(50 to 50, s.handedHalf); assertEquals(10, s.handPauseMs)
        assertEquals(1000, s.received)
        assertFalse(s.late)
        assertTrue(s.text(), s.text().startsWith("last 10.0 s: handed 100/s (52 B, longest 52 B, per half second 50-50, longest pause 10 ms), chip sent 100/s in 67 reports/s"))
    }

    @Test fun aChipThatSendsHalf() {
        // One packet every 20 ms whatever it holds: the queue grows and every packet waits longer
        val s = HciRing.sending(HciRing.records(stream(20.0, 1).bytes()))!!
        assertEquals(1000, s.handed)
        assertTrue("sent ${s.sent}", s.sent in 495..500)
        assertTrue(s.waitMs.second > 1000)
        assertTrue(s.queueMax > 400)
        assertTrue(s.late)
    }

    @Test fun packetsAlreadyInTheChipWhenTheRecordStarts() {
        // Three packets from before the record are reported first: the record's own first packet is the fourth out
        val f = File()
        f.completed(1.0, H, 3)
        for (i in 0 until 300) { f.data(10.0 + i * 10, false, H, 48); f.completed(10.0 + i * 10 + 4, H, 1) }
        val s = HciRing.sending(HciRing.records(f.bytes()))!!
        assertEquals(Triple(4, 4, 4), s.waitMs)
        assertEquals(1, s.queueMax)
    }

    @Test fun aHostThatHandsOverInClumps() {
        // The chip is quick, but the host's Bluetooth gives it nothing for 400 ms every second, then the backlog
        val f = File()
        for (sec in 0 until 6) for (i in 0 until 100) {
            val t = sec * 1000.0 + if (i < 40) 400.0 + i * 0.5 else i * 10.0
            f.data(t, false, H, 48); f.completed(t + 3, H, 1)
        }
        val recs = HciRing.records(f.bytes()).sortedBy { it.t }
        val s = HciRing.sending(recs)!!
        assertEquals(50 to 50, s.handedHalf)                               // too short a gap for the half seconds to show
        assertTrue("pause ${s.handPauseMs}", s.handPauseMs in 390..420)
        assertTrue(s.waitMs.second < 10)
        assertTrue(s.late)
    }

    @Test fun creditPacketsAreCounted() {
        val f = File()
        for (i in 0 until 300) {
            f.data(i * 10.0, false, H, 48); f.completed(i * 10.0 + 2, H, 1)
            if (i % 50 == 0) f.data(i * 10.0 + 1, true, H, 8, 0x0005, 0x16, 1, 4, 0, 0x40, 0)   // LE flow control credit, cut after 14 bytes
        }
        assertEquals(6, HciRing.sending(HciRing.records(f.bytes()))!!.credits)
    }

    @Test fun theBusiestLinkIsTheOneLookedAt() {
        val f = File()
        for (i in 0 until 300) { f.data(i * 10.0, false, 0x0007, 48); f.completed(i * 10.0 + 2, 0x0007, 1) }
        for (i in 0 until 5) f.data(i * 100.0, false, H, 20, 0x0004)
        assertEquals(0x0007, HciRing.sending(HciRing.records(f.bytes()))!!.handle)
    }

    @Test fun aRecordWithoutDataPacketsStillSaysWhatTheChipSent() {
        val f = File()
        for (i in 0 until 400) f.completed(i * 20.0, H, 1)
        val s = HciRing.sending(HciRing.records(f.bytes()))!!
        assertEquals(0, s.handed); assertEquals(399, s.sent); assertEquals(20, s.pauseMs)
        assertFalse(s.late)
        assertTrue(s.text(), s.text().startsWith("last 8.0 s: handed: no data packets in the record, chip sent 50/s in 50 reports/s (per half second 25-25)"))
    }

    @Test fun nothingSent() {
        assertNull(HciRing.sending(HciRing.records(File().event(0.0, 0x0e, 1, 0x03, 0x0c, 0).bytes())))
        assertEquals("1 records over 0.0 s", HciRing.size(HciRing.records(File().event(0.0, 0x0e, 1, 0x03, 0x0c, 0).bytes())))
    }
}
