// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.app.Application

/** Process start, before any screen or service: diagnostics (crash capture, queued reports) cover both. */
class HostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        Inventory.init(this)
        Onboarding.init(this)
    }
}
