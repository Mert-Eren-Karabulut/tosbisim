// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import dev.simrelay.host.CallAudioCheck.Bridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When a call reports itself as poor (CallAudioCheck.judge), from the daemon's AUDIO? totals and the bridge's numbers. */
class CallAudioCheckTest {
    private fun d(sent: Long = 3000, noclient: Long = 20, txdrop: Long = 0, plc: Long = 0, starved: Long = 0, live: Long = 2950,
                  cap: Long = sent, capms: Long = cap * 10, caperr: Long = 0) =
        mapOf("sent" to sent, "noclient" to noclient, "txdrop" to txdrop, "plc" to plc, "starved" to starved, "live" to live,
            "cap" to cap, "capms" to capms, "caperr" to caperr)
    private fun b(dropped: Long = 0, stalls: Long = 0, out: Long = 2980) = Bridge(1, 2980, out, out, dropped, if (stalls > 0) 1500 else 40, stalls)

    @Test fun aCleanCallSaysNothing() = assertNull(CallAudioCheck.judge(30, d(), b(), held = false))

    @Test fun halfTheCallersFramesLostOnTheWayToThePhone() {
        // a Galaxy A13 host, 7 Nov 2026: the phone got ~50 of 100 frames a second
        assertEquals("to_phone", CallAudioCheck.judge(30, d(), b(dropped = 1490, out = 1490), held = false))
        assertEquals("to_phone", CallAudioCheck.judge(30, d(txdrop = 1490), b(out = 1490), held = false))
    }

    @Test fun twelvePercentIsPoorTwoIsNot() {
        assertEquals("to_phone", CallAudioCheck.judge(30, d(txdrop = 360), b(), held = false))   // #233: 88 of 100
        assertNull(CallAudioCheck.judge(30, d(txdrop = 60), b(), held = false))
    }

    @Test fun thePhonesAudioMissingInTheCall() {
        assertEquals("from_phone", CallAudioCheck.judge(30, d(plc = 100, starved = 100), b(), held = false))
        assertEquals("both", CallAudioCheck.judge(30, d(txdrop = 900, starved = 400), b(), held = false))
    }

    @Test fun aHoldIsNotTheClientsAudioMissing() =
        assertNull(CallAudioCheck.judge(30, d(starved = 1500), b(), held = true))

    @Test fun aCaptureSlowerThanRealTimeIsCallerAudioLostTooAndNothingElseShowsIt() {
        // a Galaxy A13 host, 10 Nov 2026: its phone got 85 of 100 frames a second, nothing dropped anywhere
        assertEquals("to_phone", CallAudioCheck.judge(30, d(sent = 2550, cap = 2550, capms = 30_000), b(), held = false))
        assertNull(CallAudioCheck.judge(30, d(sent = 2950, cap = 2950, capms = 30_000), b(), held = false))      // 98%: clocks differ
        assertEquals("to_phone", CallAudioCheck.judge(30, d(caperr = 120), b(), held = false))                   // 4% of the reads failed
        assertNull(CallAudioCheck.judge(30, d(sent = 2550, cap = 2550, capms = 30_000), b(), held = true))       // a hold stops the voice path
        assertNull(CallAudioCheck.judge(30, d(cap = 300, capms = 4_000), b(), held = false))                     // too little to judge
        assertNull(CallAudioCheck.judge(30, mapOf("sent" to 3000L, "noclient" to 0L, "live" to 2950L), b(), held = false))   // an older daemon
    }

    @Test fun aStallAloneIsReported() =
        assertEquals("stall", CallAudioCheck.judge(30, d(), b(stalls = 1), held = false))

    @Test fun tooShortOrTooLittleAudioToJudge() {
        assertNull(CallAudioCheck.judge(8, d(txdrop = 1500), b(), held = false))
        assertNull(CallAudioCheck.judge(30, d(sent = 400, txdrop = 200), b(), held = false))     // < 5 s reached the client
        assertNull(CallAudioCheck.judge(30, d(starved = 200, live = 300), b(), held = false))
    }

    @Test fun framesCapturedWithNoClientDontCountAsLost() =
        // dialing before the channel opened: captured, nobody to send to
        assertNull(CallAudioCheck.judge(30, d(sent = 3500, noclient = 600), b(), held = false))

    @Test fun theDaemonsReplyReadsAsNumbers() {
        val t = CallAudioCheck.totals("OK call=1 session=1 silence=3 cap=2434 voiced=284 sent=2434 played=2437 peak=26680 " +
            "txdrop=1210 noclient=12 recv=2400 plc=3 starved=10 live=2420")
        assertEquals(2434L, t["sent"]); assertEquals(1210L, t["txdrop"]); assertEquals(12L, t["noclient"]); assertEquals(2420L, t["live"])
        assertEquals("to_phone", CallAudioCheck.judge(25, t, b(out = 1200), held = false))
        assertNull(CallAudioCheck.totals("OK call=0 session=0 silence=0 cap=0 voiced=0 sent=0 played=0 peak=0")["live"])   // older daemon
    }
}
