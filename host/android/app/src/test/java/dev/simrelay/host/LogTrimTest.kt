// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A report's log excerpt (LogTrim): Telecom's call events and warnings only, clean audio lines and repeats folded, ours whole. */
class LogTrimTest {
    private val account = "11-05 13:03:01.734 I/Telecom ( 3639): PhoneAccountRegistrar: Modify account: [ComponentInfo{com.android.phone/x}]: TSI.rPA@AJU"
    private val changed = "11-05 13:03:01.740 I/Telecom ( 3639): CallsManager: handlePhoneAccountChanged: phoneAccount=[[X] PhoneAccount]: TSI.rPA@AJU"
    private val ours = "11-05 13:03:02.017 I/SimRelayHost( 5182): no client connected; HOST_STATUS not sent (SMS stay queued)"

    @Test fun phoneAccountLinesBecomeOneMarkerWithTheirCountAndTimes() {
        val out = LogTrim.trim(listOf(account, changed, account, ours).joinToString("\n"), 12_000)
        assertEquals("… 3 Telecom lines left out (13:03:01)\n$ours\n", out)
    }

    @Test fun telecomKeepsItsCallEventsAndWarningsTheRestIsCounted() {
        // A call's end on a Redmi Note 9 Pro (9 Nov 2026): 60 such lines in half a second were the whole excerpt
        val update = "11-09 21:23:21.502 I/Telecom ( 1418): InCallController: Sending updateCall [Call id=TC@15, state=DISCONNECTED, tpac=ComponentInfo{com.android.phone/x}]"
        val route = "11-09 21:23:21.509 I/Telecom ( 1418): CallAudioRouteStateMachine: Message received: SWITCH_FOCUS=4001, arg1=1: CSW.sDc"
        val event = "11-09 21:23:21.576 I/Telecom ( 1418): Event: RecordEntry TC@15: DESTROYED, null: CSW.rC->CM.pR(cap)@CgE"
        val audio = "11-09 21:23:21.557 I/Telecom ( 1418): Logging.Events: Non-call EVENT: AUDIO_ROUTE, Entering state QuiescentEarpieceRoute: CSW.sDc"
        val warn = "11-09 21:23:21.576 W/Telecom ( 1418): CallDiagnosticServiceController: maybeUnbindCallScreeningService - already unbound"
        val ended = "11-09 21:23:21.530 I/SimRelayHost( 6080): call 97f59842 ENDED incoming=true slot=0 cause=REMOTE"
        val out = LogTrim.trim(listOf(update, route, ended, audio, update, event, warn).joinToString("\n"), 12_000)
        assertEquals(listOf("… 2 Telecom lines left out (21:23:21)", ended, audio, "… 1 Telecom lines left out (21:23:21)", event, warn).joinToString("\n") + "\n", out)
    }

    @Test fun aCallsCleanAudioLinesAreTheFirstAndACountTheOddOnesStay() {
        val line = { t: String, sent: Int, txdrop: Int, fps: String ->
            "11-09 21:$t I/simrelayd( 5151): audio 5s: sent=$sent txdrop=$txdrop recv=500 fec=0 plc=0 late=0 catchup=0 caperr=0 peak=20458 played=500 depthmax=4 ($fps fps) | held: jb=22.0ms reopen=0 (tune prime=2 keep=1 period=2)" }
        val stall = "11-09 21:19:40.100 W/SimRelayHost( 6080): audio to the phone: Bluetooth isn't taking it, dropping the oldest (wifi=off hotspot=off voice=3g ble=15.0ms)"
        val log = listOf(line("19:25.000", 500, 0, "100.0"), line("19:30.000", 499, 0, "99.9"), line("19:35.000", 501, 0, "100.1"), stall,
            line("19:40.000", 500, 212, "100.0"), line("19:45.000", 431, 0, "86.2"), line("19:50.000", 500, 0, "100.0"), line("19:55.000", 500, 0, "100.0"))
        assertEquals(listOf(log[0], "… 2 more clean 'audio 5s' lines, last at 21:19:35", stall, log[4], log[5], log[6],
            "… 1 more clean 'audio 5s' lines, last at 21:19:55").joinToString("\n") + "\n", LogTrim.trim(log.joinToString("\n"), 12_000))
    }

    @Test fun otherTagsLongLinesAreCutOursNever() {
        val theirs = "11-09 21:23:21.000 I/Tethering( 1418): " + "x".repeat(900)
        val mine = "11-09 21:23:22.000 W/SimRelayHost( 6080): call audio poor (stall): " + "y".repeat(900)
        val out = LogTrim.trim("$theirs\n$mine", 12_000).split('\n')
        assertEquals(301, out[0].length); assertTrue(out[0].endsWith("x…")); assertEquals(mine, out[1])
    }

    @Test fun repeatsInARowFoldIntoTheFirstWithTheLastTime() {
        val hello = { t: String -> "11-05 13:03:$t I/SimRelayHost( 5182): daemon <- HELLO -> OK simrelayd 0.1 card=0 call=0 session=0" }
        val out = LogTrim.trim(listOf(hello("05.102"), hello("07.103"), account, hello("09.105"), ours).joinToString("\n"), 12_000)
        assertEquals(listOf(hello("05.102"), "… the line above 2 more times, last at 13:03:09",
            "… 1 Telecom lines left out (13:03:01)", ours).joinToString("\n") + "\n", out)
    }

    @Test fun threadtimeLinesAreReadToo() {
        val noise = "11-05 02:34:08.393  1852  1900 I Telecom : PhoneAccountRegistrar: getSimCallManager: SimCallManager for subId 1 queried"
        val gatt = "11-05 02:34:08.434  4844  4893 I SimRelayHost: GATT service added status=0"
        assertEquals("… 1 Telecom lines left out (02:34:08)\n$gatt\n", LogTrim.trim("$noise\n$gatt", 12_000))
    }

    @Test fun androidFourteensNamelessTelecomLinesToo() {
        val emergency = "11-05 23:07:34.469 I/Telecom (  602): : isInEmergencyCall: false: TSI.iIEC@AL4"
        val register = "11-05 19:51:16.140 W/Telecom ( 1806): : registerPhoneAccount - attempt to set a group from a non-system caller.: TSI.rPA@C6M"
        assertEquals("… 2 Telecom lines left out (23:07:34–19:51:16)\n$ours\n", LogTrim.trim("$emergency\n$register\n$ours", 12_000))
    }

    @Test fun crashesAndOtherTagsAreNeverDropped() {
        val log = listOf(
            "11-05 13:00:22.089 E/AndroidRuntime( 7973): FATAL EXCEPTION: main",
            "11-05 13:00:22.089 E/AndroidRuntime( 7973): \tat android.os.Parcel.readException(Parcel.java:2357)",
            "\tat android.os.Parcel.readException(Parcel.java:2357)",
            "\tat android.os.Parcel.readException(Parcel.java:2357)",          // not a logcat line: never folded
            "11-05 13:00:23.000 I/simrelayd( 5018): role android.app.role.SMS -> dev.simrelay.host",
        ).joinToString("\n")
        assertEquals("$log\n", LogTrim.trim(log, 12_000))
    }

    @Test fun theNewestCharactersAreKeptFromAWholeLine() {
        val lines = (0 until 400).map { i -> "11-05 13:%02d:%02d.000 I/SimRelayHost( 5182): keep-alive ack n=$i".format(i / 60, i % 60) }
        val out = LogTrim.trim(lines.joinToString("\n"), 2_000)
        assertTrue(out.length <= 2_000)
        assertTrue(out.endsWith("keep-alive ack n=399\n"))
        assertTrue(out.startsWith("11-05 "))                                  // no half line at the start
        assertFalse(out.contains("n=0\n"))
    }
}
