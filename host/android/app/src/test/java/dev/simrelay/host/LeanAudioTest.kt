// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Bluetooth audio channels are lean (LeanAudio.why), what a lean write holds, and which links count as small. */
class LeanAudioTest {
    @Test fun aLeanWriteIsTwoRadioPackets() {
        // two frames, each [u16 length][u16 seq][u8 flags][19 bytes of Opus], behind L2CAP's 4-byte header and 2-byte length
        assertEquals(48, LeanAudio.WRITE)
        assertEquals(2 * 27, LeanAudio.WRITE + 4 + 2)
    }

    @Test fun onlyAHostOfSmallPacketsIsLean() {
        assertNull(LeanAudio.why(small = false, always = false))                       // every other host: nothing changes
        assertEquals("this host's Bluetooth sends in small radio packets", LeanAudio.why(small = true, always = false))
        assertEquals("lean on every host (debug)", LeanAudio.why(small = false, always = true))
        assertNull(LeanAudio.why(small = false))                                       // the debug switch is off unless set
    }

    @Test fun onlyWhatTheRecordHoldsMakesALinkSmall() {
        // a Galaxy A13: its own Bluetooth asked for 27 bytes and the length never changed
        assertEquals(true, HciRing.Link(role = "peripheral", askedTxBytes = 27, sinceConnect = true).small)
        assertEquals(true, HciRing.Link(askedTxBytes = 27).small)
        assertEquals(true, HciRing.Link(txBytes = 27, rxBytes = 251).small)
        // the usual link: both sides agreed on 251
        assertEquals(false, HciRing.Link(txBytes = 251, rxBytes = 251, askedTxBytes = 251, sinceConnect = true).small)
        assertEquals(false, HciRing.Link(txBytes = 251, askedTxBytes = 27).small)       // what was agreed beats what was asked
        // no change in the record proves nothing: it may leave such events out
        assertNull(HciRing.Link(role = "peripheral", sinceConnect = true).small)
        assertNull(HciRing.Link(askedTxBytes = 251, sinceConnect = true).small)
        assertNull(HciRing.Link().small)
    }

    @Test fun aCallsNumbersSayWhatLeanDid() {
        assertEquals("no", CallAudioCheck.Bridge(1, 3000, 3000, 2980, 0, 4, 0).lean())
        // lean from the channel's first frames (a Galaxy A13: the daemon's answer came two frames in)
        assertEquals("from the start: 14 of 5598 dropped",
            CallAudioCheck.Bridge(1, 5600, 5586, 2800, 14, 3, 0, leanFromIn = 2, leanFromDropped = 0).lean())
        // the host learned what its link is during the call
        assertEquals("after 412 frames: 96 dropped before, 14 of 2600 after",
            CallAudioCheck.Bridge(1, 3012, 2902, 1600, 110, 930, 0, leanFromIn = 412, leanFromDropped = 96).lean())
        assertTrue(LeanAudio.FRAME * 8 * 100 < 24000); assertFalse(LeanAudio.always)
    }
}
