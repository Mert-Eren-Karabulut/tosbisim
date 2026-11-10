// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/** Frames per Bluetooth packet by chip (AudioPack.frames) and the queue that pairs them (SendQueue). */
class AudioPackTest {
    @Test fun aMediatekHostPairsItsFrames() {
        assertEquals(2, AudioPack.frames(listOf("Mediatek MT6769V/CT", "mt6768", "mt6768")))   // Galaxy A13, 10 Nov 2026
        assertEquals(2, AudioPack.frames(listOf("", "mt6765", "mt6765")))                       // Android 11: no SOC_* names
        assertEquals(2, AudioPack.frames(listOf("MediaTek Dimensity 700", "", "")))
    }

    @Test fun otherChipsSendEachFrameAlone() {
        assertEquals(1, AudioPack.frames(listOf("QTI SM8250", "kona", "qcom")))                 // Galaxy S20 FE
        assertEquals(1, AudioPack.frames(listOf("universal7870", "exynos7870", "samsungexynos7870")))   // Galaxy J7 Prime
        assertEquals(1, AudioPack.frames(listOf("Unisoc T606", "ums9230", "ums9230")))
        assertEquals(1, AudioPack.frames(emptyList()))
    }

    @Test fun adbCanForceAnyHost() {
        assertEquals(3, AudioPack.frames(listOf("QTI SM8250"), forced = 3))
        assertEquals(1, AudioPack.frames(listOf("mt6768"), forced = 1))
        assertEquals(2, AudioPack.frames(listOf("mt6768"), forced = 9))                         // out of range: by the chip
    }

    private fun frame(n: Int) = ByteArray(40) { n.toByte() }
    private val buf = ByteArray(514)

    @Test fun aloneEveryFrameGoesAtOnce() {
        val q = SendQueue(10)
        q.offer(frame(1))
        val t0 = System.nanoTime()
        val t = q.take(buf, 514, pack = 1)!!
        assertEquals(1, t.frames); assertEquals(40, t.bytes)
        assertTrue("no wait", System.nanoTime() - t0 < 8_000_000)
    }

    @Test fun aPairWaitsForItsSecondFrame() {
        val q = SendQueue(10)
        q.offer(frame(1))
        thread { Thread.sleep(8); q.offer(frame(2)) }
        val t = q.take(buf, 514, pack = 2)!!
        assertEquals(2, t.frames); assertEquals(80, t.bytes)
        assertEquals(1, buf[0].toInt()); assertEquals(2, buf[40].toInt())                       // oldest first
    }

    @Test fun twoWaitingGoAtOnce() {
        val q = SendQueue(10)
        q.offer(frame(1)); q.offer(frame(2))
        val t0 = System.nanoTime()
        assertEquals(2, q.take(buf, 514, pack = 2)!!.frames)
        assertTrue("no wait", System.nanoTime() - t0 < 8_000_000)
    }

    @Test fun aFrameWithNoPartnerStillGoesSoon() {
        // the daemon stopped sending: the last frame waits 15 ms, not for ever
        val q = SendQueue(10)
        q.offer(frame(1))
        val t0 = System.nanoTime()
        assertEquals(1, q.take(buf, 514, pack = 2)!!.frames)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("waited $ms ms", ms in 13..120)
    }

    @Test fun aLinkThatLagsGetsEverythingWaitingInOneWrite() {
        val q = SendQueue(10)
        repeat(5) { q.offer(frame(it)) }
        assertEquals(5, q.take(buf, 514, pack = 2)!!.frames)
        repeat(5) { q.offer(frame(it)) }
        assertEquals(5, q.take(buf, 514, pack = 1)!!.frames)
    }

    @Test fun onlyWhatFitsThePacket() {
        val q = SendQueue(10)
        repeat(5) { q.offer(frame(it)) }
        assertEquals(3, q.take(buf, 120, pack = 2)!!.frames)                                     // 3 x 40 = 120
        assertEquals(2, q.take(buf, 120, pack = 2)!!.frames)
    }

    @Test fun theBacklogDropsTheOldest() {
        val q = SendQueue(10)
        var dropped = 0
        repeat(14) { dropped += q.offer(frame(it)) }
        assertEquals(4, dropped)
        val t = q.take(buf, 514, pack = 1)!!
        assertEquals(10, t.frames); assertEquals(4, buf[0].toInt())
    }

    @Test fun closingWakesTheSender() {
        val q = SendQueue(10)
        thread { Thread.sleep(20); q.close() }
        assertNull(q.take(buf, 514, pack = 2))
        q.offer(frame(1))
        assertEquals(1, q.take(buf, 514, pack = 2)!!.frames)                                     // what's left still goes, without the wait
    }
}
