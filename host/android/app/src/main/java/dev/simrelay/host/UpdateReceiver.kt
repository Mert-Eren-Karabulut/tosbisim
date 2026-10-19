// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The app was just updated (by Updater or by hand): start the service again, the install killed it. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == Intent.ACTION_MY_PACKAGE_REPLACED) ctx.startForegroundService(Intent(ctx, HostService::class.java))
    }
}
