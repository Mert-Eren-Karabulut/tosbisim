// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.IBinder
import android.provider.Settings
import android.util.Log

/** Long-lived foreground service: owns the daemon connection and the BLE relay. */
class HostService : Service() {
    companion object {
        private const val TAG = "SimRelayHost"
        @Volatile var running = false; private set

        /**
         * Start the host if it isn't running: from every way the system can wake this app (a call, an SMS, the
         * daemon's start-up broadcast). Once Android stops restarting a crashed service, nothing else brought it
         * back (audit, 19 Oct). Never throws.
         */
        fun ensure(ctx: Context) {
            if (running || !Compat.isHostInstalled(ctx)) return
            runCatching { ctx.startForegroundService(Intent(ctx, HostService::class.java)) }
                .onFailure { Log.w(TAG, "host start: $it") }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { running = false; super.onDestroy() }

    override fun onCreate() {
        super.onCreate()
        // Android 12+ refuses a background foreground-service start without an exemption (an OEM battery manager
        // that dropped ours): stop cleanly and let the module's watchdog start us as root, rather than crash-loop.
        try { startForeground(1, notification()) } catch (e: Exception) {
            Log.e(TAG, "startForeground refused: $e")
            Diag.event("host_start_refused", org.json.JSONObject().put("what", e.javaClass.simpleName), withLog = false)
            stopSelf(); return
        }
        if (!Compat.isHostInstalled(this)) {      // plain app install (before root): compatibility check only
            Log.i(TAG, "not installed as the host yet; service stays off")
            stopSelf(); return
        }
        Log.i(TAG, "host service up; silence: zen_mode=${Settings.Global.getInt(contentResolver, "zen_mode", -1)} " +
                "(2 = total silence)")
        silenceStreams()
        running = true
        Diag.milestone("host_running", org.json.JSONObject().apply { Compat.bleCaps(this@HostService)?.let { put("ble", it) } })
        Onboarding.hostRunning(this)
        Daemon.start(this)
        Relay.start(this)
        android.os.Handler(mainLooper).postDelayed({ Onboarding.selfHeal(this) }, 30_000)   // after service.sh's own pass
        welcomeOnce()
    }

    /**
     * The first start after the Magisk install (a restart): the person who just installed it sees the home screen and
     * no sign of TosbiSim. Open the host's own screen once, at the "pair your iPhone" step (20 Oct 2026 rehearsal).
     */
    private fun welcomeOnce() {
        val p = getSharedPreferences("onboarding", MODE_PRIVATE)
        if (p.getBoolean("welcomed", false)) return
        p.edit().putBoolean("welcomed", true).apply()
        if (Relay.pairedClient() != null) return
        android.os.Handler(mainLooper).postDelayed({
            runCatching { startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.w(TAG, "welcome screen: $it") }
        }, 8_000)
    }

    /** Silence policy: system, ring and notification volumes at minimum. Here, not in service.sh: on Android 11
     *  `cmd media_session volume` calls ActivityThread.systemMain() inside system_server, which then crashes on its
     *  next service bind (soft reboot ~25 s after every boot; Redmi Note 8 Pro, 18 Oct).
     *  Ring volume 0 moves the ringer from silent to vibrate, which turns Do Not Disturb off: total silence is put
     *  back afterwards (service.sh gives us DND access). */
    private fun silenceStreams() {
        if (Retire.running) return
        val am = getSystemService(AudioManager::class.java) ?: return
        Retire.snapshotVolumes(this, am)                    // once: what "Stop using as host" gives back
        for (s in intArrayOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION)) {
            runCatching { am.setStreamVolume(s, am.getStreamMinVolume(s), 0) }.onFailure { Log.w(TAG, "volume $s: $it") }
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        Log.i(TAG, "streams silenced; zen_mode=${Settings.Global.getInt(contentResolver, "zen_mode", -1)}")
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
            // adb debug: --ez on true|false, exactly what a SetHotspot from the iPhone does
            "REPORT" -> Api.sendCompatReport(this) { ok, d -> Log.i(TAG, "REPORT -> ok=$ok $d") }   // adb debug: same as the button
            // adb debug, the host's own Wi-Fi: -a WIFI --es op scan|add|forget|away [--es ssid S --es pass P | --ei id N | --ez on B]
            "WIFI" -> when (intent.getStringExtra("op")) {
                "scan" -> HostWifi.scan { r, why -> Log.i(TAG, "WIFI scan -> ${r?.networksList?.map { "${it.level}${if (it.secure) "*" else ""}${if (it.saved) "S" else ""}" } ?: why}") }
                "add" -> HostWifi.add(intent.getStringExtra("ssid").orEmpty(), intent.getStringExtra("pass").orEmpty()) { r -> Log.i(TAG, "WIFI add -> ok=${r.ok} ${r.problem}") }
                "forget" -> HostWifi.forget(intent.getIntExtra("id", -1)) { Log.i(TAG, "WIFI forget done") }
                "away" -> HostWifi.setUseWhenAway(intent.getBooleanExtra("on", false), remote = false)
                // test rig: --es state away|near|real
                "client" -> { HostWifi.clientOverride = when (intent.getStringExtra("state")) { "away" -> false; "near" -> true; else -> null }
                              HostWifi.evaluate("rig: client ${intent.getStringExtra("state")}") }
                else -> Log.i(TAG, "WIFI status: ${HostWifi.status().let { "on=${it.on} reason=${it.reason} away=${it.useWhenAway} saved=${it.savedCount} connected=${it.connectedSsid.isNotEmpty()}" }} " +
                    "hotspot=${Hotspot.isOn(this)} uplink=${HostWifi.uplinkKind()} override=${HostWifi.clientOverride}")
            }
            "HOTSPOT" -> Hotspot.set(this, intent.getBooleanExtra("on", false)) { ok, d -> Log.i(TAG, "HOTSPOT -> ok=$ok $d") }
            // adb debug: build the automatic profile even if a shipped one exists, and log it
            // adb debug: one raw daemon command, e.g. -a DAEMON --es cmd "TUNE 2 1 240"; the answer goes to the log
            "DAEMON" -> intent.getStringExtra("cmd")?.let { c -> Daemon.query(c) { r -> Log.i(TAG, "DAEMON $c -> $r") } }
            // adb debug: set the remote-mode key (hex) without the iPhone, or turn remote mode off (no extra)
            "REMOTE" -> RemoteLink.configure(intent.hasExtra("key"),
                intent.getStringExtra("key")?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray())
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
            // Tapping it opens setup and status (after the module's restart this is how people find the next step)
            .setContentIntent(android.app.PendingIntent.getActivity(this, 0, Intent(this, SetupActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED)
            context.startForegroundService(Intent(context, HostService::class.java))
    }
}
