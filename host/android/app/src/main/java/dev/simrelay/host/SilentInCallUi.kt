// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.app.Activity
import android.os.Bundle
import android.telecom.InCallService

/**
 * The "in-call UI" while the host app holds the dialer role (box mode): it shows nothing, so the display
 * stays off for calls. Call handling stays in CallMonitor (non-UI service, bound either way).
 */
class SilentInCallUi : InCallService()

/** ACTION_DIAL (needed to qualify for the dialer role). A box has no screen to dial from. */
class DialActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); finish() }
}
