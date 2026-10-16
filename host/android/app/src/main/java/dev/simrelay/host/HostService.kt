// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import android.util.Log

/** Long-lived foreground service: owns the daemon connection and the BLE relay. */
class HostService : Service() {
    companion object { private const val TAG = "SimRelayHost" }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notification())
        if (!Compat.isHostInstalled(this)) {      // plain app install (before root): compatibility check only
            Log.i(TAG, "not installed as the host yet; service stays off")
            stopSelf(); return
        }
        Log.i(TAG, "host service up; silence: zen_mode=${Settings.Global.getInt(contentResolver, "zen_mode", -1)} " +
                "(2 = total silence)")
        Daemon.start(this)
        Relay.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STATUS" -> {
                Daemon.status()
                val h = Hotspot.info(this)
                Log.i(TAG, "hotspot: supported=${h.supported} on=${h.on} band=${h.band} sec=${h.security} hidden=${h.hidden} " +
                        "max=${h.maxClients} autoOff=${h.autoOff}/${h.autoOffMinutes}min clients=${h.clientsCount} " +
                        "caps bands=${h.capabilities.bandsList} sec=${h.capabilities.securitiesList}")
            }
            "PAIR" -> Relay.openPairing()          // adb: re-pair a (new) client within 5 min
            "BOX" -> Box.set(intent.getBooleanExtra("on", false))   // adb debug: --ez on true|false
            // adb debug: build the automatic profile even if a shipped one exists, and log it
            "AUTOPROFILE" -> Daemon.tryAutoProfile { p -> Log.i(TAG, "AUTOPROFILE -> ${p?.json?.toString() ?: "none"}") }
            // adb debug (root only): turn a SIM's PIN lock on/off. --ei slot N --es pin PIN --ez on true|false
            "SIM_LOCK" -> Sims.setLockEnabled(this, intent.getIntExtra("slot", -1), intent.getStringExtra("pin").orEmpty(),
                                              intent.getBooleanExtra("on", false))
            // Debug (root only: this service is not exported): relay a synthetic SMS through the normal path.
            "INJECT_SMS" -> injectSms(intent.getStringExtra("from") ?: "+905550000000",
                                      intent.getStringExtra("body") ?: "Test")
        }
        return START_STICKY
    }

    private fun injectSms(from: String, body: String) {
        val msg = dev.simrelay.protocol.v1.SmsReceived.newBuilder()
            .setId(java.util.UUID.randomUUID().toString()).setFromRaw(from).setFromE164(from)
            .setBody(body).setReceivedMs(System.currentTimeMillis()).setSlot(1).build()
        SmsStore.add(this, msg)
        Log.i(TAG, "INJECTED test SMS ${msg.id.take(8)} (${body.length} chars)")
        Relay.smsReceived(msg)
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("host", getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, "host")
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .build()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED)
            context.startForegroundService(Intent(context, HostService::class.java))
    }
}
