// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

/**
 * Non-UI InCallService (bound by Telecom because we hold CONTROL_INCALL_EXPERIENCE as a privileged
 * app; the stock dialer stays the UI). Forwards every call and state change to CallRegistry.
 */
class CallMonitor : InCallService() {
    companion object { private const val TAG = "SimRelayHost" }

    private val callbacks = HashMap<Call, Call.Callback>()

    override fun onCreate() {
        super.onCreate()
        CallRegistry.init(this)
    }

    override fun onCallAdded(call: Call) {
        val cb = object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) = CallRegistry.changed(c)
        }
        call.registerCallback(cb)
        callbacks[call] = cb
        Log.i(TAG, "call added: state=${call.stateCompat} dir=${call.details.callDirection} slot=${CallRegistry.slotOf(call)}")
        CallRegistry.added(call)
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        Log.i(TAG, "call removed: state=${call.stateCompat}")
        CallRegistry.removed(call)
    }
}
