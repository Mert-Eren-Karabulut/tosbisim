// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject

/**
 * Switches "enhanced" control channels off in a Bluetooth stack that breaks on them: Qualcomm's own stack on Android 12
 * and older (Xiaomi's MIUI 14 on Android 12, among others).
 *
 * What happens there: a phone on Android 13 or newer pairs, reads that the host supports enhanced channels, and opens
 * them at once. Qualcomm's stack then marks the link as using them, but only sets up its bookkeeping for the plain
 * control channel when the phone has first announced the feature in writing, which Android never does before opening
 * them. From that moment it finds no bookkeeping for the plain channel, takes that for "a request is still being
 * answered", and throws away every request the phone sends on it ("Server Command Queue is not empty. Discard this
 * cmd."), silently, for the life of the link. The phone's next request waits 30 seconds for its answer and the phone
 * ends the link; TosbiSim's Hello never gets its turn. A Poco X3 NFC host and a Galaxy S25 Ultra did this through two
 * days and every reinstall (27-28 Nov 2026); the host's own record of the chip's traffic and the stack's log showed
 * it, and the stack's published source (gatt_sr.cc, gatt_main.cc) says why. iPhones don't open the channels, which is
 * why the same hosts pair with them.
 *
 * That stack reads one switch at its start, the system property [PROP] (on unless it says "false"). Off, the host no
 * longer says it supports the channels and no phone opens them; TosbiSim never used them. So: on a phone whose
 * Bluetooth library names that property (nothing else reads it), on Android 12 or older (a Xiaomi host on Android 13
 * pairs with the same kind of phone as it is), the host sets it to false once and, when no phone is paired, restarts
 * Bluetooth so it holds at once; a paired host gets it at its next restart, its working link is left alone.
 * [MARK] remembers that it was us, so giving the phone back ([Retire]) puts the switch back.
 */
object BtStackFix {
    private const val TAG = "SimRelayHost"
    const val PROP = "persist.vendor.btstack.enable.eatt"
    const val MARK = "/data/adb/tosbisim/bt-enhanced-off"
    private const val LIBS = "/system/lib64/libbluetooth*.so /system_ext/lib64/libbluetooth*.so /system/lib/libbluetooth*.so /system_ext/lib/libbluetooth*.so"

    /** Whether to switch it off: the stack has the switch ([lib] names the library that reads it), it isn't off, and the Android is one that breaks. Pure. */
    fun needed(lib: String, value: String, sdk: Int): Boolean = lib.isNotEmpty() && value != "false" && sdk <= 32

    /**
     * Host start, background thread. [unpaired]: no phone is paired; [busy]: a Bluetooth link is up or a call is on
     * (then Bluetooth is not restarted now). Never throws.
     */
    fun apply(ctx: Context, unpaired: () -> Boolean, busy: () -> Boolean) = runCatching {
        val r = Root.sh("l=\$(grep -l $PROP $LIBS 2>/dev/null | head -1); echo \"lib=\$l\"; echo \"val=\$(getprop $PROP)\"", 10_000)
        if (!r.ok && "lib=" !in r.out) return@runCatching
        val lib = r.out.lineSequence().firstOrNull { it.startsWith("lib=") }?.removePrefix("lib=")?.trim().orEmpty()
        val value = r.out.lineSequence().firstOrNull { it.startsWith("val=") }?.removePrefix("val=")?.trim().orEmpty()
        if (!needed(lib, value, Build.VERSION.SDK_INT)) {
            if (lib.isNotEmpty()) Log.i(TAG, "Bluetooth stack has the enhanced-channels switch (${lib.substringAfterLast('/')}): ${if (value == "false") "off already" else "left on (Android ${Build.VERSION.RELEASE})"}")
            return@runCatching
        }
        // resetprop writes a persistent property whatever the property's own rules say; plain setprop where there is none
        val set = Root.sh("mkdir -p /data/adb/tosbisim; echo ${Root.q(value.ifEmpty { "unset" })} > $MARK; " +
            "(resetprop -p $PROP false 2>/dev/null || setprop $PROP false); echo \"now=\$(getprop $PROP)\"", 10_000)
        val ok = "now=false" in set.out
        val restart = ok && unpaired() && !busy()
        Log.w(TAG, "Bluetooth stack breaks on enhanced control channels (${lib.substringAfterLast('/')}): switched them off -> ${if (ok) "ok" else "REFUSED"}" +
            if (!ok) "" else if (restart) "; restarting Bluetooth so it holds now" else "; it holds from Bluetooth's next start")
        if (!ok) Root.sh("rm -f $MARK", 5_000)
        // Detached, as the silent-link reset does it: if this app dies in those seconds Bluetooth must still come back on
        if (restart) Root.background("setsid sh -c 'svc bluetooth disable; sleep 2; svc bluetooth enable' </dev/null >/dev/null 2>&1 &")
        Diag.event("bt_stack_fix", JSONObject().put("what", if (ok) "enhanced channels off" else "refused").put("restarted", restart)
            .put("lib", lib.substringAfterLast('/')).put("was", value.ifEmpty { "unset" }), withLog = false)
    }.onFailure { Log.w(TAG, "Bluetooth stack check failed: $it") }
}
