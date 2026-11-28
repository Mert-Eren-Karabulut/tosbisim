// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qualcomm's legacy voice path from a phone's own files, and from its sound card when the files say nothing: a Mi 6
 * (msm8998) on LineageOS 22 whose host sent its hardware inventory on 27 Nov 2026. Test resource
 * sagit-call-controls.txt: the call-audio controls of that card's 4402 (inventory lines, "numid\tname\ttype\tcount\trange").
 */
class AutoProfileTest {
    private val sagit = javaClass.getResource("/sagit-call-controls.txt")!!.readText().lines().mapNotNull { it.split('\t').getOrNull(1) }.toSet()
    // /proc/asound/pcm on that phone, the streams that matter (MultiMedia3 and the hostless ones have no say here)
    private val sagitPcms = mapOf("MultiMedia1" to 0, "MultiMedia2" to 1, "VoiceMMode1" to 2, "MultiMedia5" to 13, "MultiMedia9" to 27)
    private val stockPaths = """<mixer><path name="incall_music_uplink">
        <ctl name="Incall_Music Audio Mixer MultiMedia9" value="1" /></path>
        <path name="incall_music_uplink2"><ctl name="Incall_Music_2 Audio Mixer MultiMedia9" value="1" /></path></mixer>"""

    private fun build(mixers: List<String>, pcmIds: List<Int> = emptyList(), card: Set<String> = sagit,
                      pcms: Map<String, Int> = sagitPcms, playback: Map<String, Int> = pcms) =
        AutoProfile.qcom("sagit", mixers, pcmIds, { it in card }, { pcms }, { playback })

    @Test fun theMi6OnACustomRomGetsTheCardsOwnRoute() {
        // Its only mixer file names no in-call music route at all
        val (p, said) = build(listOf("""<mixer><path name="deep-buffer-playback"><ctl name="SLIMBUS_0_RX Audio Mixer MultiMedia1" value="1" /></path></mixer>"""))
        assertTrue(said, p != null)
        val j = p!!.json
        assertEquals("auto/sagit", p.id)
        assertEquals("qcom-legacy", j.getString("router"))
        assertEquals(1, j.getJSONObject("inject").getInt("pcm"))
        assertEquals("Incall_Music Audio Mixer MultiMedia2", j.getJSONObject("inject").getJSONObject("route_by_session").getString("1"))
        assertEquals("Incall_Music_2 Audio Mixer MultiMedia2", j.getJSONObject("inject").getJSONObject("route_by_session").getString("2"))
        assertEquals("MultiMedia1 Mixer VOC_REC_DL", j.getJSONObject("capture").getString("route"))
        assertEquals("", j.getJSONObject("capture").getString("rec_config"))      // this kernel has no Voc Rec Config
        assertEquals("sound card", j.getString("route_from"))
        assertTrue(said, "from the sound card" in said)
    }

    @Test fun theVendorsPathStillWins() {
        val (p, said) = build(listOf(stockPaths))
        val j = p!!.json
        assertEquals(27, j.getJSONObject("inject").getInt("pcm"))                 // MultiMedia9 by name
        assertEquals("Incall_Music Audio Mixer MultiMedia9", j.getJSONObject("inject").getJSONObject("route_by_session").getString("1"))
        assertEquals("Incall_Music_2 Audio Mixer MultiMedia9", j.getJSONObject("inject").getJSONObject("route_by_session").getString("2"))
        assertFalse(j.has("route_from"))
        assertFalse(said, "sound card" in said)
        // ... and the PCM the vendor lists for it, when it lists one
        assertEquals(40, build(listOf(stockPaths), pcmIds = listOf(40)).first!!.json.getJSONObject("inject").getInt("pcm"))
    }

    @Test fun theCardsRouteNeedsItsPlaybackStreamAndItsControls() {
        val none = listOf("<mixer></mixer>")
        // No MultiMedia2 playback: MultiMedia9 is next
        assertEquals(27, build(none, playback = sagitPcms - "MultiMedia2").first!!.json.getJSONObject("inject").getInt("pcm"))
        // The front end the vendor lists a PCM for comes first, by that PCM's name
        assertEquals("Incall_Music Audio Mixer MultiMedia5", VendorAudio.kernelRoute(listOf(13), { it in sagit }, sagitPcms))
        // Never MultiMedia1 (the capture front end), listed or not
        assertEquals("Incall_Music Audio Mixer MultiMedia2", VendorAudio.kernelRoute(listOf(0), { it in sagit }, sagitPcms))
        // A card without the controls (Exynos, a newer Qualcomm audio stack): nothing, and it says so
        val (p, said) = build(none, card = setOf("Voice Tx Device Mute", "Voice Rx Device Mute"))
        assertNull(p)
        assertTrue(said, "no incall music control" in said)
        // The route without the second session's, or without the call record route: not built
        assertNull(build(none, card = sagit - "Incall_Music_2 Audio Mixer MultiMedia2").first)
        assertNull(build(none, card = sagit - "MultiMedia1 Mixer VOC_REC_DL").first)
    }

    @Test fun theHostsOwnVolumesAreHeldSilentInACall() {
        // A Poco X3 NFC's codec: three playback volumes and their mix twins; no call control where the card has none
        val poco = sagit + setOf("RX_RX0 Digital Volume", "RX_RX1 Digital Volume", "RX_RX2 Digital Volume", "RX_RX0 Mix Digital Volume",
            "RX_RX1 Mix Digital Volume", "RX_RX2 Mix Digital Volume", "EAR PA GAIN")
        val (p, said) = build(listOf(stockPaths), card = poco)
        val cc = p!!.json.getJSONObject("call_controls")
        assertEquals(6, cc.length())
        assertEquals(0, cc.getInt("RX_RX0 Digital Volume")); assertEquals(0, cc.getInt("RX_RX2 Mix Digital Volume"))
        assertTrue(said, said.endsWith(", 6 host volume controls held at 0 in a call"))
        // They go to the daemon as controls of the call, and count among the controls the profile needs on the card
        assertTrue(p.daemonSetLines().contains("SET call RX_RX0 Digital Volume=0"))
        assertTrue("RX_RX1 Digital Volume" in p.controlNames())
        val (plain, _) = build(listOf(stockPaths), card = sagit - sagit.filter { "Digital Volume" in it }.toSet())
        assertFalse(plain!!.json.has("call_controls"))
    }
}
