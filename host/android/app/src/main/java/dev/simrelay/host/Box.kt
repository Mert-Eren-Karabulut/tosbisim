// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject

/**
 * Box mode: the host as a silent, screen-off box that takes no user input (16 Oct 2026).
 * Layers, most portable first; every change records the original value and box-off restores it:
 *  1. Screen guard: whenever the display turns on (power key, charger, a notification), simrelayd puts it
 *     back to sleep. Touch needs no switch of its own: Android stops the touch panel while the display is off.
 *  2. No OEM call screen: the host app takes the dialer role (SilentInCallUi has no UI), so the stock
 *     in-call activity never starts and never wakes the display.
 *  3. Settings: AOSP keys (ambient display, wake gestures, background Wi-Fi/BLE scans, location) plus
 *     per-manufacturer keys from profiles/oem/<maker>.json (Samsung: AOD, double tap to wake, motions).
 *     A key this phone doesn't have is left alone.
 *  4. Radio: LTE only while idle (allowed-network reason POWER, so the user's own choice is untouched);
 *     5G comes back while the hotspot is on. Calls are VoLTE either way.
 *  6. Network blocked for Google Play services + Play Store (no background data, no app updates on the box):
 *     simrelayd NETBLOCK, an iptables chain on their uids; re-applied at boot, removed on restore.
 *  5. Wi-Fi client off (16 Oct 2026): no association wake-ups or scans, no Wi-Fi Calling (only VoLTE audio is
 *     measured), and a rooted phone isn't on the home LAN. The hotspot works with the client off; after the
 *     hotspot stops, Android may bring the client back, so we switch it off again.
 * Escape: Volume Up x3 then Volume Down x3 (simrelayd reads the keys) suspends box mode for 10 min; it
 * comes back at the first screen-off after that. The charge limit is separate and always applies.
 */
object Box {
    private const val TAG = "SimRelayHost"
    private const val ESCAPE_MS = 10 * 60_000L
    private const val DIALER_ROLE = "android.app.role.DIALER"
    private const val REASON_POWER = 1                  // TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_POWER
    private const val BITMASK_NR = 1L shl 19            // TelephonyManager.NETWORK_TYPE_BITMASK_NR
    private const val BITMASK_LTE = (1L shl 12) or (1L shl 18)   // NETWORK_TYPE_BITMASK_LTE | _LTE_CA

    /** The mask without 5G, or [mask] itself when that would leave no LTE (a "5G only" choice would become "no
     *  radio": no calls, no texts; audit, 19 Oct). */
    private fun withoutNr(mask: Long): Long = (mask and BITMASK_NR.inv()).let { if (it and BITMASK_LTE == 0L) mask else it }
    const val DEFAULT_CHARGE_LIMIT = 80
    private val NET_BLOCK = listOf("com.google.android.gms", "com.android.vending")

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var onChange: () -> Unit = {}
    @Volatile var chargeSupported = false; private set
    @Volatile private var hotspotOn = false

    private val prefs get() = app.getSharedPreferences("box", Context.MODE_PRIVATE)
    val on get() = prefs.getBoolean("on", false)
    val suspendedUntil get() = prefs.getLong("suspended_until", 0)
    val suspended get() = System.currentTimeMillis() < suspendedUntil
    val chargeLimit get() = prefs.getInt("charge_limit", DEFAULT_CHARGE_LIMIT)
    /** Box settings are currently applied (on and not suspended by the escape gesture). */
    private val applied get() = prefs.getBoolean("applied", false)
    /** The power button is blocked while box settings are applied (the daemon re-grabs it after a restart). */
    val blocksPowerKey get() = ::app.isInitialized && applied

    fun start(ctx: Context, changed: () -> Unit) {
        app = ctx.applicationContext
        onChange = changed
        app.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        })
        setChargeLimit(chargeLimit)
        if (on && !suspended) apply() else if (on) main.postDelayed(resume, suspendedUntil - System.currentTimeMillis())
        Log.i(TAG, "box mode ${if (on) "on" else "off"}${if (suspended) " (suspended)" else ""}, charge limit $chargeLimit%")
    }

    fun set(enable: Boolean) {
        prefs.edit().putBoolean("on", enable).putLong("suspended_until", 0).apply()
        main.removeCallbacks(resume)
        if (enable) apply() else restore()
        onChange()
    }

    fun setChargeLimit(percent: Int) {
        val p = percent.coerceIn(50, 100)
        prefs.edit().putInt("charge_limit", p).apply()
        Daemon.charge(p) { ok -> chargeSupported = ok; main.post(onChange) }
    }

    /** Escape gesture (from simrelayd): screen usable for 10 minutes. */
    fun escape() {
        if (!on) return
        Log.i(TAG, "box mode suspended for ${ESCAPE_MS / 60000} min (escape gesture)")
        prefs.edit().putLong("suspended_until", System.currentTimeMillis() + ESCAPE_MS).apply()
        restore()
        main.removeCallbacks(resume); main.postDelayed(resume, ESCAPE_MS)
        onChange()
        // Whoever used the gesture wants to look at the host: show the setup checklist.
        runCatching {
            app.startActivity(Intent(app, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "box: could not open the setup screen: $it") }
    }

    /** Hotspot on: allow 5G for its data; off: LTE only while idle. */
    fun hotspotChanged(isOn: Boolean) {
        if (hotspotOn == isOn) return
        hotspotOn = isOn
        if (applied) {
            setLteOnly(!isOn)
        }
    }

    fun callScreenHidden(): Boolean =
        app.getSystemService(TelecomManager::class.java).defaultDialerPackage == app.packageName

    // Back on at the first screen-off after the escape window (or at once if the screen is already off).
    private val resume = Runnable {
        if (!on || suspended || Retire.running) return@Runnable
        if (app.getSystemService(PowerManager::class.java).isInteractive) return@Runnable  // wait for SCREEN_OFF
        Log.i(TAG, "box mode back on after escape")
        apply(); onChange()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> if (on && !suspended && !Retire.running) {
                    Log.i(TAG, "box mode: display turned on, putting it back to sleep")
                    Daemon.screenOff()
                }
                Intent.ACTION_SCREEN_OFF -> if (on && !suspended && !applied) resume.run()
            }
        }
    }

    // ------------------------------------------------------------------ apply / restore

    private fun apply() {
        val orig = JSONObject(prefs.getString("orig", "{}")!!)
        for ((ns, key, value) in plan()) {
            val id = "$ns/$key"
            val cur = read(ns, key) ?: continue                 // not on this phone: leave it alone
            if (!orig.has(id)) orig.put(id, cur)
            if (cur != value) write(ns, key, value)
        }
        if (!orig.has("dialer")) orig.put("dialer", app.getSystemService(TelecomManager::class.java).defaultDialerPackage ?: "")
        // uids whose network box mode blocks (recorded so restore unblocks exactly these)
        val uids = NET_BLOCK.mapNotNull { runCatching { app.packageManager.getPackageUid(it, 0) }.getOrNull() }.distinct()
        orig.put("netblock", org.json.JSONArray(uids))
        uids.forEach { Daemon.netblock(it, true) }
        prefs.edit().putString("orig", orig.toString()).putBoolean("applied", true).apply()
        Daemon.role(DIALER_ROLE, app.packageName)
        // The host's Wi-Fi follows HostWifi's rule (box mode or not), not box mode.
        setLocation(false)
        setLteOnly(!hotspotOn)
        Daemon.powerKey(true)                               // no wake-and-flash on a power press (19 Oct)
        if (app.getSystemService(PowerManager::class.java).isInteractive) Daemon.screenOff()
        Log.i(TAG, "box mode applied (${orig.length()} originals saved)")
    }

    private fun restore() {
        val orig = JSONObject(prefs.getString("orig", "{}")!!)
        for (id in orig.keys()) {
            if (id == "dialer" || id == "location" || id == "wifi" || id == "netblock") continue
            val (ns, key) = id.split("/", limit = 2)
            write(ns, key, orig.getString(id))
        }
        // The phone app role stays: a host is the phone app whether or not box mode is on (Relay.guardDialerRole)
        if (orig.optBoolean("location", false)) setLocation(true)
        orig.optJSONArray("netblock")?.let { a -> for (i in 0 until a.length()) Daemon.netblock(a.getInt(i), false) }
        setLteOnly(false)
        Daemon.powerKey(false)
        prefs.edit().remove("orig").putBoolean("applied", false).apply()
        Log.i(TAG, "box mode restored ${orig.length()} settings")
    }

    /** (namespace, key, box value): AOSP keys for every phone + the maker's keys from its OEM profile. */
    private fun plan(): List<Triple<String, String, String>> {
        val l = mutableListOf(
            Triple("secure", "doze_always_on", "0"),             // always-on / ambient display
            Triple("secure", "doze_enabled", "0"),               // ambient display for notifications
            Triple("secure", "doze_pulse_on_pick_up", "0"),
            Triple("secure", "doze_pulse_on_double_tap", "0"),
            Triple("secure", "doze_tap_gesture", "0"),
            Triple("secure", "doze_wake_screen_gesture", "0"),
            Triple("secure", "wake_gesture_enabled", "0"),
            Triple("secure", "camera_double_tap_power_gesture_disabled", "1"),
            Triple("global", "wifi_scan_always_enabled", "0"),   // background Wi-Fi scans for location
            Triple("global", "ble_scan_always_enabled", "0"),    // background BLE scans (our BLE link is unaffected)
            Triple("global", "stay_on_while_plugged_in", "0"),
        )
        Profiles.forManufacturer(app)?.optJSONObject("box")?.let { box ->
            for (ns in listOf("system", "secure", "global")) {
                val o = box.optJSONObject(ns) ?: continue
                for (k in o.keys()) l += Triple(ns, k, o.get(k).toString())
            }
        }
        return l
    }

    private fun read(ns: String, key: String): String? = runCatching {
        val r = app.contentResolver
        when (ns) {
            "system" -> Settings.System.getString(r, key)
            "secure" -> Settings.Secure.getString(r, key)
            else -> Settings.Global.getString(r, key)
        }
    }.getOrNull()

    private fun write(ns: String, key: String, value: String) {
        val r = app.contentResolver
        runCatching {
            when (ns) {
                "system" -> Settings.System.putString(r, key, value)
                "secure" -> Settings.Secure.putString(r, key, value)
                else -> Settings.Global.putString(r, key, value)
            }
        }.onFailure { Log.w(TAG, "box: $ns/$key=$value failed: $it") }
    }

    /** Location off in box mode (@SystemApi setLocationEnabledForUser, WRITE_SECURE_SETTINGS). */
    @SuppressLint("MissingPermission")
    private fun setLocation(enabled: Boolean) = runCatching {
        val lm = app.getSystemService(LocationManager::class.java)
        if (!enabled && lm.isLocationEnabled) {
            val orig = JSONObject(prefs.getString("orig", "{}")!!).put("location", true)
            prefs.edit().putString("orig", orig.toString()).apply()
        }
        LocationManager::class.java.getMethod("setLocationEnabledForUser", Boolean::class.java, UserHandle::class.java)
            .invoke(lm, enabled, Process.myUserHandle())
    }.onFailure { Log.w(TAG, "box: location ${if (enabled) "on" else "off"} failed: ${it.cause ?: it}") }

    /** LTE-only (no NR) through the POWER reason on every active SIM; false lifts the restriction. */
    private fun setLteOnly(lteOnly: Boolean) {
        val base = app.getSystemService(TelephonyManager::class.java)
        for (info in Sims.active(app)) runCatching {
            val tm = base.createForSubscriptionId(info.subscriptionId)
            if (android.os.Build.VERSION.SDK_INT < 31) return@runCatching setLteOnlyR(tm, info.subscriptionId, info.simSlotIndex, lteOnly)
            val set = TelephonyManager::class.java.getMethod("setAllowedNetworkTypesForReason", Int::class.java, Long::class.java)
            val get = TelephonyManager::class.java.getMethod("getAllowedNetworkTypesForReason", Int::class.java)
            val user = get.invoke(tm, 0) as Long                 // REASON_USER: what the user allows
            set.invoke(tm, REASON_POWER, if (lteOnly) withoutNr(user) else user)
            Log.i(TAG, "box: slot ${info.simSlotIndex} ${if (lteOnly) "LTE only (idle)" else "5G allowed"}")
        }.onFailure { Log.w(TAG, "box: network types failed: ${it.cause ?: it}") }
    }

    /** Android 11 has no per-reason network types (Redmi Note 8 Pro, 19 Oct): no 5G radio -> nothing to do; else
     *  setAllowedNetworkTypes (@SystemApi, R) with the original remembered per SIM and put back after. */
    private fun setLteOnlyR(tm: TelephonyManager, sub: Int, slot: Int, lteOnly: Boolean) {
        val c = TelephonyManager::class.java
        val supported = c.getMethod("getSupportedRadioAccessFamily").invoke(tm) as Long
        if (supported and BITMASK_NR == 0L) { if (lteOnly) Log.i(TAG, "box: slot $slot has no 5G radio: LTE only not needed"); return }
        val key = "allowed_$sub"
        val cur = c.getMethod("getAllowedNetworkTypes").invoke(tm) as Long
        val target = if (lteOnly) {
            if (!prefs.contains(key)) prefs.edit().putLong(key, cur).apply()
            withoutNr(cur)
        } else prefs.getLong(key, cur or BITMASK_NR).also { prefs.edit().remove(key).apply() }
        c.getMethod("setAllowedNetworkTypes", Long::class.java).invoke(tm, target)
        Log.i(TAG, "box: slot $slot ${if (lteOnly) "LTE only (idle)" else "5G allowed"} (Android 11)")
    }
}

/** simrelayd events: the box-mode escape gesture, and "I (re)started" so the app reconnects at once. */
class DaemonEventReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            "dev.simrelay.host.BOX_ESCAPE" -> runCatching { Box.escape() }
            "dev.simrelay.host.DAEMON_UP" -> { HostService.ensure(ctx); Daemon.reconnect() }
        }
    }
}
