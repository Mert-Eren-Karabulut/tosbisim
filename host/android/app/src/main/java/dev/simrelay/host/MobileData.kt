// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * The host's mobile data switch (default-data SIM). The hotspot turns it on; only the user turns it off (a LAN-only
 * hotspot, or abroad). Remote mode needs it. Changing it needs MODIFY_PHONE_STATE (privileged app via the module).
 */
object MobileData {
    private const val TAG = "SimRelayHost"
    private const val REASON_USER = 0   // TelephonyManager.DATA_ENABLED_REASON_USER (API 31)

    private fun tm(ctx: Context): TelephonyManager {
        val base = ctx.getSystemService(TelephonyManager::class.java)
        val sub = SubscriptionManager.getDefaultDataSubscriptionId()
        return if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(sub) else base
    }

    fun isOn(ctx: Context): Boolean = runCatching { tm(ctx).isDataEnabled }.getOrDefault(false)

    fun set(ctx: Context, on: Boolean): Boolean = runCatching {
        val t = tm(ctx)
        if (Build.VERSION.SDK_INT >= 31) {
            TelephonyManager::class.java.getMethod("setDataEnabledForReason", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(t, REASON_USER, on)
        } else {
            @Suppress("DEPRECATION") t.setDataEnabled(on)
        }
        Log.i(TAG, "mobile data ${if (on) "on" else "off"}")
        true
    }.onFailure { Log.e(TAG, "mobile data ${if (on) "on" else "off"} failed: $it") }.getOrDefault(false)

    /** The hotspot needs it: turn it on if the user hasn't got it on. */
    fun ensureOn(ctx: Context) {
        if (!isOn(ctx)) { Log.i(TAG, "hotspot needs mobile data: turning it on"); set(ctx, true) }
    }

    /** Status push when the switch changes (from us, the system or a SIM change). */
    fun watch(ctx: Context, changed: () -> Unit) {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { changed() }
        }
        ctx.contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // data roaming too: Android 16 applies a change asynchronously, so the status sent right after showed the
                // old value until something else changed (seen on the Android 16 emulator, 19 Oct)
                val key = uri?.lastPathSegment.orEmpty()
                if (key.startsWith("mobile_data") || key.startsWith("data_roaming")) obs.onChange(selfChange, uri)
            }
        })
    }
}
