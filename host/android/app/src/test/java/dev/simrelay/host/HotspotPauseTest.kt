// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import dev.simrelay.host.PauseCore.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hotspot's call pause through whole calls (PauseCore): when it goes off, when it comes back, who wins. */
class HotspotPauseTest {
    /** A host that offers it, with the switch on, the client on Bluetooth and the hotspot up unless said otherwise. */
    private fun core() = PauseCore().apply { setting = true }
    private fun PauseCore.step(bt: Boolean = true, up: Boolean = !paused, offered: Boolean = true) = step(offered, bt, up)

    @Test fun pausedFromTheCallsAudioToItsEnd() {
        val c = core()
        assertEquals(Action.NONE, c.step())                       // no call: nothing
        c.calls(relayed = true, live = true)
        assertEquals(Action.PAUSE, c.step())
        assertTrue(c.paused)
        assertEquals(Action.NONE, c.step())                       // stays paused while the call goes on
        c.calls(relayed = false, live = false)
        assertEquals(Action.RESUME, c.step())
        assertFalse(c.paused)
        assertEquals(Action.NONE, c.step())
    }

    @Test fun aRingingCallOrOneTheHostAnsweredItselfNeverPauses() {
        val c = core()
        c.calls(relayed = false, live = true)                     // ringing, or audio not relayed (Basic mode, host's own call)
        assertEquals(Action.NONE, c.step())
    }

    @Test fun aHoldDoesntBringItBackAndASecondCallKeepsItPaused() {
        val c = core()
        c.calls(relayed = true, live = true); assertEquals(Action.PAUSE, c.step())
        c.calls(relayed = false, live = true)                     // on hold: no audio, the call is still up
        assertEquals(Action.NONE, c.step())
        c.calls(relayed = true, live = true)                      // back, or the waiting call taken
        assertEquals(Action.NONE, c.step())
        assertTrue(c.paused)
        c.calls(relayed = false, live = false)
        assertEquals(Action.RESUME, c.step())
    }

    @Test fun neverOnHostsThatDontOfferItNorWithTheSwitchOff() {
        val off = PauseCore()
        off.calls(relayed = true, live = true)
        assertEquals(Action.NONE, off.step())
        val notOffered = core()
        notOffered.calls(relayed = true, live = true)
        assertEquals(Action.NONE, notOffered.step(offered = false))
    }

    @Test fun neverOverTheInternet() {
        val c = core()
        c.calls(relayed = true, live = true)
        assertEquals(Action.NONE, c.step(bt = false))
    }

    @Test fun aHotspotThatIsOffStaysOffAndComesBackOnlyIfWeTookItDown() {
        val c = core()
        c.calls(relayed = true, live = true)
        assertEquals(Action.NONE, c.step(up = false))
        c.calls(relayed = false, live = false)
        assertEquals(Action.NONE, c.step(up = false))             // no resume: we never paused it
    }

    @Test fun theCallMovingToTheInternetBringsItBackForTheRestOfTheCall() {
        val c = core()
        c.calls(relayed = true, live = true); assertEquals(Action.PAUSE, c.step())
        assertEquals(Action.RESUME, c.step(bt = false))           // Bluetooth faded: the hotspot may carry the call now
        assertTrue(c.held)
        assertEquals(Action.NONE, c.step(bt = true, up = true))   // Bluetooth back: no second pause in this call
        c.calls(relayed = false, live = false)
        assertFalse(c.held)
        c.calls(relayed = true, live = true)                      // the next call pauses again
        assertEquals(Action.PAUSE, c.step())
    }

    @Test fun theUsersSwitchWinsForTheRestOfTheCall() {
        val c = core()
        c.calls(relayed = true, live = true); assertEquals(Action.PAUSE, c.step())
        c.userSwitched()                                          // "Turn on now" (or the hotspot switch)
        assertFalse(c.paused)
        assertEquals(Action.NONE, c.step(up = true))              // not taken down again
        c.calls(relayed = false, live = false)
        assertEquals(Action.NONE, c.step(up = true))              // and nothing to resume
    }

    @Test fun turnedOnByHandDuringACallItStaysOn() {
        val c = core()
        c.calls(relayed = true, live = true)
        assertEquals(Action.NONE, c.step(up = false))
        c.userSwitched()
        assertEquals(Action.NONE, c.step(up = true))
    }

    @Test fun turnedOffByHandWhilePausedItStaysOffAfterTheCall() {
        val c = core()
        c.calls(relayed = true, live = true); assertEquals(Action.PAUSE, c.step())
        c.userSwitched()
        c.calls(relayed = false, live = false)
        assertEquals(Action.NONE, c.step(up = false))
    }

    @Test fun onFromElsewhereWhilePausedWins() {
        val c = core()
        c.calls(relayed = true, live = true); assertEquals(Action.PAUSE, c.step())
        assertTrue(c.cameOnElsewhere())
        assertEquals(Action.NONE, c.step(up = true))
        assertFalse(c.cameOnElsewhere())                          // only while paused
    }

    @Test fun switchingItOffMidPauseBringsTheHotspotBack() {
        val c = core()
        c.calls(relayed = true, live = true); assertEquals(Action.PAUSE, c.step())
        c.setting = false
        assertEquals(Action.RESUME, c.step())
        assertFalse(c.held)                                       // the switch went off: not a transport hold
    }

    @Test fun switchedOnMidCallItPausesAtOnce() {
        val c = PauseCore()
        c.calls(relayed = true, live = true); assertEquals(Action.NONE, c.step())
        c.setting = true
        assertEquals(Action.PAUSE, c.step())
    }

    @Test fun aPauseRestoredAfterAHostRestartEndsWithNoCall() {
        val c = core().apply { paused = true }                    // from prefs; the call didn't come back
        c.calls(relayed = false, live = false)
        assertEquals(Action.RESUME, c.step(up = false))
    }
}
