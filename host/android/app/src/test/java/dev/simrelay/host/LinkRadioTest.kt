// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkRadioTest {
    @Test fun everyHostAsksFor2M() {
        // 1M was tried on MediaTek hosts (0.12.31): a Galaxy A13 really ran on it and its calls stayed choppy (10 Nov 2026)
        assertEquals(LinkRadio.MASK_2M, LinkRadio.mask)
    }

    @Test fun modeNames() {
        assertEquals("1M", LinkRadio.phyName(1)); assertEquals("2M", LinkRadio.phyName(2))
        assertEquals("coded", LinkRadio.phyName(3)); assertEquals("?", LinkRadio.phyName(0))
    }

    // `dumpsys bluetooth_manager` on Android 14 (emulator, 10 Nov 2026), the scanner list as the host cuts it out
    private val dump = """
GATT Scanner Map
  Entries: 1

  com.google.uid.shared:10127 (Registered)
  LE scans (started/stopped)                                  : 3 / 1
  Scan time in ms (active/suspend/total)                      : 165735 / 0 / 165735
  Scan time with mode in ms (Opp/LowPower/Balanced/LowLatency/AmbientDiscovery):80245 / 0 / 79474 / 6016 / 0
  Score                                                       : 25884
  Last 1 scans                                                :
    11-10 21:50:04 - 6016ms Filter 0 results (1) CB Regular Scan
      └ Scan Config: [ ScanMode=LOW_LATENCY, callbackType=ALL_MATCHES ]
      └ BluetoothLeScanFilter [ ServiceDataUuid=0000fff6-0000-1000-8000-00805f9b34fb ServiceData=[0] ServiceDataMask=[0] ]
  Ongoing scans                                               :
    11-10 21:50:10 - 79474ms Filter 0 results (1) CB Regular Scan
      └ Scan Config: [ ScanMode=BALANCED, callbackType=ALL_MATCHES ]
      └ BluetoothLeScanFilter [ ServiceDataUuid=0000fe2c-0000-1000-8000-00805f9b34fb ServiceData=[0] ServiceDataMask=[0] ]
    11-10 21:50:09 - 80245ms Opp Filter 0 results (2) CB Regular Scan
      └ Scan Config: [ ScanMode=OPPORTUNISTIC, callbackType=ALL_MATCHES ]
  Application ID                     : 2
  UUID                               : e58be9de-f25a-4f0c-a67a-67893182a051
  Connections: 0

GATT Advertiser Map
shim::legacy::stack Le scan:enabled duration_s:79.486 results:0
"""

    @Test fun scansOfTheAndroid14Dump() {
        assertEquals("com.google.uid.shared: 21:50:04 LOW_LATENCY 6s + now BALANCED 79s + now OPPORTUNISTIC 80s | stack scan on",
            LinkRadio.scans(dump))
    }

    @Test fun anAppTheUserInstalledIsNotNamed() {
        val d = "GATT Scanner Map\n  com.example.fitness:10301 (Registered)\n  Ongoing scans :\n" +
            "    11-10 21:50:10 - 4000ms Filter 0 results (1) CB Regular Scan\n      └ Scan Config: [ ScanMode=LOW_POWER, callbackType=ALL_MATCHES ]\n"
        assertEquals("another app: now LOW_POWER 4s", LinkRadio.scans(d))
    }

    @Test fun aScanWithoutItsModeLineStillCounts() {
        val d = "GATT Scanner Map\n  com.samsung.android.easysetup (Registered)\n  Ongoing scans :\n" +
            "    11-10 21:50:10 - 12000ms Filter 0 results (1) CB Regular Scan\n  Application ID : 2\n"
        assertEquals("com.samsung.android.easysetup: now ? 12s", LinkRadio.scans(d))
    }

    @Test fun nobodyScanning() {
        assertEquals("no scans | stack scan off", LinkRadio.scans("GATT Scanner Map\n  Entries: 0\n\nGATT Advertiser Map\nshim::legacy::stack Le scan:disabled\n"))
    }

    @Test fun aDumpWithoutTheListSaysNothing() {
        assertEquals("", LinkRadio.scans(""))
        assertEquals("", LinkRadio.scans("timeout"))
    }
}
