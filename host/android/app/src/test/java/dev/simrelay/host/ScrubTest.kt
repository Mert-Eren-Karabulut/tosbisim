// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** What leaves the phone in a diagnostics report: nothing personal, the technical content intact. */
class ScrubTest {
    private fun clean(s: String) = Scrub.text(s)

    @Test fun phoneNumbersGo() {
        for (n in listOf("+905551234567", "05551234567", "+44 7700 900123", "0544 958 9260")) {
            assertFalse(n, clean("dial $n now").contains(n.filter { it.isDigit() }.takeLast(7)))
        }
    }

    @Test fun keyedFieldsGo() {
        assertEquals("call RINGING number=<…> slot=0", clean("call RINGING number=+905551234567 slot=0"))
        assertEquals("ssid=<…>", clean("ssid=HomeNet"))
    }

    @Test fun addressesAndIdsGo() {
        assertEquals("BLE connected <mac> bond=12", clean("BLE connected 02:1A:2B:3C:4D:5E bond=12"))
        assertEquals("gateway <ip>", clean("gateway 192.168.43.1"))
        assertEquals("queued <id>", clean("queued e844e74c-06b4-4643-8caa-e423b6e4eda7"))
        assertEquals("joined \"<…>\"", clean("joined \"Home Net 5G\""))
        assertEquals("mail <email>", clean("mail someone@example.com"))
    }

    @Test fun technicalContentStays() {
        val lines = listOf(
            "10-19 18:12:22.623  2690  2690 I SimRelayHost: audio 5s: sent=500 recv=499 peak=26168 played=500 (99.9 fps)",
            "managed 'PCM_2_PB_CH1 DL2_CH1' numid=4079 -> 0 (orig 0)",
            "daemon <- IFSTATS wlan0 -> OK rx=526171 tx=362431",
            "Galaxy SM-G780G G780GXXSHEYJ1 Android 13 card=4c806f57225b1e78",
        )
        for (l in lines) assertEquals(l, clean(l))
    }

    @Test fun partsFitTheFrameInBytes() {
        val log = "ok … ç ş 🙂 ".repeat(800)
        val parts = utf8Chunks(log, 2_800)
        assertEquals(log, parts.joinToString(""))
        for (p in parts) assertFalse(p.toByteArray().size > 2_800)
    }
}
