// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * The pause's state and every transition, free of Android (HotspotPauseTest drives it through whole calls).
 *
 * [inCall]: a call's audio went to the client over this call (dialing or answered, relayed), until no call is left;
 * a call put on hold stays a call. [held]: this call keeps the hotspot as it is, because the user switched it, it came
 * on from elsewhere, or the call's audio left Bluetooth (no pausing again within the call: no on/off flapping).
 */
class PauseCore {
    enum class Action { NONE, PAUSE, RESUME }

    var setting = false
    var paused = false
    var inCall = false; private set
    var held = false; private set

    /** Calls changed: [relayed] a call's audio goes to the client now; [live] any call is still up. */
    fun calls(relayed: Boolean, live: Boolean) {
        inCall = relayed || (inCall && live)
        if (!inCall) held = false
    }

    /** The user switched the hotspot (either way): that stands for the rest of the call, and ends a pause. */
    fun userSwitched() {
        if (inCall) held = true
        paused = false
    }

    /** The hotspot came on while paused, and not by us: whoever did it wins. True if that ended a pause. */
    fun cameOnElsewhere(): Boolean {
        if (!paused) return false
        paused = false
        held = true
        return true
    }

    /** What to do now. [onBluetooth]: the client is on Bluetooth and the call's audio isn't on the internet path. */
    fun step(offered: Boolean, onBluetooth: Boolean, hotspotUp: Boolean): Action {
        val want = setting && offered && inCall && onBluetooth && !held
        return when {
            want && !paused && hotspotUp -> { paused = true; Action.PAUSE }
            !want && paused -> {
                // The call goes on but its audio left Bluetooth (or the client did): the hotspot may carry the call now
                if (setting && offered && inCall && !onBluetooth) held = true
                paused = false
                Action.RESUME
            }
            else -> Action.NONE
        }
    }
}

/**
 * Pause the hotspot during calls (Hotspot.call_pause). On a host whose hotspot can only use 2.4 GHz, Wi-Fi and the
 * Bluetooth link that carries the call share one radio: a Galaxy J7 Prime user heard calls break up both ways with the
 * hotspot on (7 Nov 2026). Offered only there, and only with relayed call audio (Hotspot.callPauseOffered): a 5 GHz
 * hotspot keeps out of Bluetooth's way, so newer hosts never see the option.
 *
 * The hotspot goes off when a call's audio starts on Bluetooth and comes back when the last call ends. Never over the
 * internet: the hotspot may carry that call. A call whose audio moves to the internet, or whose client leaves
 * Bluetooth, gets the hotspot back at once and keeps it. Box mode and the host Wi-Fi rule see a paused hotspot as on
 * (Relay's status listener): a pause must not switch the network mode or Wi-Fi in the middle of a call. Main thread;
 * a pause survives a host restart (prefs) but not a reboot.
 */
object HotspotPause {
    private const val TAG = "SimRelayHost"
    private const val RESTORE_GRACE_MS = 8_000L
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val core = PauseCore()
    private var bleNear: () -> Boolean = { false }
    private var changed: () -> Unit = {}
    /** A pause restored from before this process: calls take a moment to be re-added (InCallService rebinding). */
    private var graceUntil = 0L
    /** The "off once unused" countdown a pause interrupted (or one asked for meanwhile): re-armed once the hotspot is up again. */
    private var unusedAfterResume = 0
    private var resumeGen = 0

    private val prefs get() = app.getSharedPreferences("hotspot_pause", Context.MODE_PRIVATE)

    /** Off right now for a call (Hotspot.paused_for_call). Any thread. */
    @Volatile var paused = false; private set

    /** Any call up, relayed or not (CallRegistry): Hotspot's "off once unused" waits for its end. Any thread. */
    @Volatile var callUp = false; private set

    /** The user's choice (Hotspot.call_pause), whatever this host offers. */
    val setting: Boolean get() = ::app.isInitialized && core.setting

    fun init(ctx: Context, bleNear: () -> Boolean, changed: () -> Unit) {
        app = ctx.applicationContext
        this.bleNear = bleNear; this.changed = changed
        val p = prefs
        core.setting = p.getBoolean("on", false)
        if (!p.getBoolean("paused", false)) return
        if (p.getInt("boot", -1) != bootCount()) {
            p.edit().putBoolean("paused", false).remove("unused_s").apply()      // a restart starts with the hotspot off anyway
            Log.i(TAG, "hotspot call pause: dropped, the phone restarted since")
            return
        }
        core.paused = true; paused = true
        unusedAfterResume = p.getInt("unused_s", 0)
        graceUntil = SystemClock.elapsedRealtime() + RESTORE_GRACE_MS
        Log.i(TAG, "hotspot call pause: still paused from before this host restart")
        main.postDelayed({ evaluate("after a host restart") }, RESTORE_GRACE_MS + 100)
    }

    /** CallRegistry, at every call change. */
    fun calls(relayed: Boolean, live: Boolean) = onMain {
        callUp = live
        val was = core.inCall
        core.calls(relayed, live)
        if (core.inCall != was) Log.i(TAG, "hotspot call pause: ${if (core.inCall) "call audio started" else "calls over"}")
        evaluate(if (core.inCall) "call" else "call ended")
    }

    /** The client's link changed: Bluetooth up or gone, the call's audio onto or off the internet path. */
    fun transportChanged(why: String) = onMain { evaluate(why) }

    /** The user switched the hotspot (client app, widget, adb): main thread, before Hotspot.set. */
    fun userSwitched(on: Boolean) {
        if (!::app.isInitialized) return
        val wasPaused = core.paused
        core.userSwitched()
        resumeGen++; unusedAfterResume = 0
        if (wasPaused) {
            setPaused(false)
            Log.i(TAG, "hotspot call pause: you turned the hotspot ${if (on) "on" else "off"}, it stays so for this call")
            changed()
        }
    }

    /** The hotspot's own on/off (tethering events), main thread. */
    fun hotspotChanged(on: Boolean) {
        if (!::app.isInitialized || !on) return
        if (core.cameOnElsewhere()) {
            setPaused(false); unusedAfterResume = 0
            Log.i(TAG, "hotspot call pause: the hotspot came on elsewhere, it stays on for this call")
            changed()
            return
        }
        val unused = unusedAfterResume
        if (unused > 0) { unusedAfterResume = 0; Hotspot.offWhenUnused(app, unused) }      // only once it's really up
    }

    /** Hotspot.offWhenUnused while paused: the countdown starts once the hotspot is back. */
    fun deferOffWhenUnused(seconds: Int): Boolean {
        if (!paused) return false
        unusedAfterResume = seconds
        prefs.edit().putInt("unused_s", seconds).apply()
        Log.i(TAG, "hotspot call pause: off once unused for $seconds s, counted from when the hotspot is back")
        return true
    }

    fun setSetting(on: Boolean) {
        core.setting = on
        prefs.edit().putBoolean("on", on).apply()
        Log.i(TAG, "hotspot call pause ${if (on) "on" else "off"}")
        evaluate("setting")
        changed()
    }

    /** The host is being given back: no resume after this. */
    fun forget() {
        if (!::app.isInitialized) return
        core.paused = false; resumeGen++; unusedAfterResume = 0
        setPaused(false)
    }

    private fun evaluate(why: String) {
        if (!::app.isInitialized) return
        val offered = Hotspot.callPauseOffered(app)
        val onBluetooth = bleNear() && !RemoteCall.active
        if (core.paused && !core.inCall && SystemClock.elapsedRealtime() < graceUntil) return    // the restored call isn't back yet
        when (core.step(offered, onBluetooth, Hotspot.upOrStarting(app))) {
            PauseCore.Action.PAUSE -> pause(why)
            PauseCore.Action.RESUME -> resume(why, onBluetooth)
            PauseCore.Action.NONE -> {}
        }
    }

    private fun pause(why: String) {
        unusedAfterResume = Hotspot.offWhenUnusedSeconds()
        setPaused(true)
        resumeGen++
        Log.i(TAG, "hotspot paused for the call ($why): on this host its Wi-Fi shares the radio with the call's Bluetooth audio")
        Hotspot.set(app, false) { ok, detail ->
            if (ok || !Hotspot.isOn(app) || !paused) return@set
            Log.w(TAG, "hotspot call pause: the hotspot didn't stop ($detail), left on")
            core.cameOnElsewhere(); setPaused(false); changed()
        }
        changed()
    }

    private fun resume(why: String, onBluetooth: Boolean) {
        setPaused(false)
        DataUsage.continueSession()
        Log.i(TAG, "hotspot back on after the call pause ($why${if (core.held && !onBluetooth) ": the call left Bluetooth" else ""})")
        start(++resumeGen, 1)
        changed()
    }

    /** Start it again; a start that fails is tried twice more, unless anything changed meanwhile. */
    private fun start(gen: Int, attempt: Int) {
        Hotspot.set(app, true) { ok, detail ->
            if (ok || gen != resumeGen || paused) return@set
            if (attempt >= 3) {
                Log.w(TAG, "hotspot call pause: the hotspot didn't come back ($detail)")
                Diag.event("hotspot_resume_failed", org.json.JSONObject().put("what", detail.take(60)), withLog = false)
                return@set
            }
            Log.w(TAG, "hotspot call pause: start failed ($detail), trying again")
            main.postDelayed({ if (gen == resumeGen && !paused && !Hotspot.isOn(app)) start(gen, attempt + 1) }, 5_000L * attempt)
        }
    }

    private fun setPaused(v: Boolean) {
        paused = v
        val e = prefs.edit().putBoolean("paused", v)
        if (v) e.putInt("boot", bootCount()).putInt("unused_s", unusedAfterResume) else e.remove("unused_s")
        e.apply()
    }

    private fun bootCount(): Int =
        runCatching { android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    private fun onMain(f: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) f() else main.post(f) }
}
