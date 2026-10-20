// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The setup journey's state, owned by the process (not the screen): a rotation, a theme change or Magisk's own screen
 * in between must not lose "root works" or "installed, restart pending", and an action must not run twice. The
 * screen ([SetupActivity]) only renders [facts] and calls the actions here.
 */
object Onboarding {
    private const val TAG = "SimRelayHost"
    private val worker = Bg.single("onboarding")

    /** What the root check found. */
    enum class Root { UNKNOWN, CHECKING, OK, NO_SU, TIMEOUT, DENIED }

    /** Installing the Magisk module. */
    enum class Install { IDLE, BUSY, FAILED, RESTART_PENDING }

    /** The module as Magisk sees it (needs root). ENABLED_NEEDS_RESTART: on in Magisk but not mounted this boot
     *  (turned back on, or Magisk skipped it): a restart, not a reinstall. */
    enum class Module { UNKNOWN, NONE, PENDING_RESTART, ENABLED_NEEDS_RESTART, ACTIVE, DISABLED, REMOVING }

    @Volatile var root = Root.UNKNOWN; private set
    @Volatile var install = Install.IDLE; private set
    @Volatile var installError = ""; private set
    @Volatile var fixing = false; private set

    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        // "Installed, restart pending" survives the process (the user may leave and come back before restarting).
        if (prefs(ctx).getBoolean("restart_pending", false)) install = Install.RESTART_PENDING
        // Root seen before: assumed until a root call fails (rootLost), so the screen doesn't prompt again each start.
        if (prefs(ctx).getBoolean("root_ok", false)) root = Root.OK
    }

    /** A root call failed although root was OK (Magisk removed, the app's grant revoked): back to the root step. */
    private fun rootLost(why: String) {
        if (root != Root.OK) return
        root = Root.UNKNOWN
        prefs(app).edit().remove("root_ok").apply()
        Log.w(TAG, "root lost: $why")
        Diag.event("root_lost", JSONObject().put("what", why.take(60)), withLog = false)
    }

    private fun suFailed(r: dev.simrelay.host.Root.Result) =
        !r.ok && (r.out.contains("timeout") || r.out.contains("IOException") || r.out.contains("Permission denied", true) ||
            r.out.contains("not found", true) || r.out.contains("su:"))

    /** Ask for root (Magisk prompts once). Classified, so the screen can say what to do: no su / no answer / refused. */
    fun checkRoot(ctx: Context, done: () -> Unit) {
        if (root == Root.CHECKING) return
        root = Root.CHECKING
        worker.execute {
            try {
                val r = dev.simrelay.host.Root.sh("id -u", 25_000)
                root = when {
                    r.last == "0" -> Root.OK
                    r.out.contains("timeout") -> Root.TIMEOUT
                    r.out.contains("IOException") || r.out.contains("not found", ignoreCase = true) -> Root.NO_SU
                    else -> Root.DENIED
                }
                if (root == Root.OK) {
                    prefs(ctx).edit().putBoolean("root_ok", true).apply()
                    Diag.milestone("root_ok", JSONObject().put("magisk", Compat.magiskInstalled(ctx)))
                } else Diag.event("root_check", JSONObject().put("what", root.name.lowercase()).put("magisk", Compat.magiskInstalled(ctx)), withLog = false)
                Log.i(TAG, "root check: $root")
            } finally {
                if (root == Root.CHECKING) root = Root.UNKNOWN
                done()
            }
        }
    }

    /** Install the embedded Magisk module; on success a restart is pending (remembered across the process). */
    fun installModule(ctx: Context, done: () -> Unit) {
        if (install == Install.BUSY) return
        install = Install.BUSY
        val started = Installer.install(ctx) { ok, log ->
            installError = if (ok) "" else plainInstallError(log)
            if (!ok && (log.contains("su:") || log.contains("timeout") || log.contains("Permission denied", true))) rootLost("install: ${installError}")
            install = if (ok) Install.RESTART_PENDING else Install.FAILED
            prefs(ctx).edit().putBoolean("restart_pending", ok).putInt("installed_boot", bootCount(ctx))
                .putLong("installed_at", System.currentTimeMillis()).apply()
            done()
        }
        if (!started) install = Install.IDLE.also { done() }    // another install still running (it reports itself)
    }

    /** Boots since the factory reset (Settings.Global.BOOT_COUNT): unlike wall-clock time, a clock change can't move it. */
    private fun bootCount(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /**
     * Installed, and the phone has restarted since, yet we're not the host: the module didn't come up (disabled in
     * Magisk, Magisk safe mode, a privapp mismatch...). Telling this apart from "restart pending" ends the old loop
     * of "Install" -> "Restart" -> "Install".
     */
    fun restartedSinceInstall(ctx: Context): Boolean {
        val at = prefs(ctx).getInt("installed_boot", -1)
        val now = bootCount(ctx)
        if (at >= 0 && now >= 0) return now > at
        // Installed by 0.11 (wall-clock time saved) or no boot counter on this ROM
        val installedAt = prefs(ctx).getLong("installed_at", 0)
        return installedAt > 0 && System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime() > installedAt
    }

    /** The host runs after a restart: a pending restart is done. An install started from this screen (a reinstall in
     *  host mode) is left alone: it reports its own result. */
    fun hostRunning(ctx: Context) {
        val legacy = !prefs(ctx).contains("installed_boot")         // pending from 0.11: we run as the host, so it's done
        if (install == Install.RESTART_PENDING && (legacy || restartedSinceInstall(ctx)) ||
            install == Install.IDLE && prefs(ctx).getBoolean("restart_pending", false)) {
            install = Install.IDLE
            prefs(ctx).edit().putBoolean("restart_pending", false).apply()
        }
    }

    private fun plainInstallError(log: String): String {
        val last = log.lines().map { it.trim().trimStart('!', '-', ' ') }.lastOrNull { it.isNotBlank() }.orEmpty()
        return when {
            log.contains("arm64 only") -> "this phone runs 32-bit Android"
            log.contains("Incomplete Magisk install", true) -> "Magisk's setup isn't complete: open the Magisk app once and let it finish"
            log.contains("su:") || log.contains("timeout") -> "root was lost during the install"
            else -> last.take(160)
        }
    }

    /** The module as Magisk sees it, from its folders (root, quick). */
    fun module(): Module {
        if (root != Root.OK) return Module.UNKNOWN
        val r = dev.simrelay.host.Root.sh(
            "d=/data/adb/modules/simrelay-host; u=/data/adb/modules_update/simrelay-host; " +
                "if [ -d \$u ]; then echo pending; elif [ ! -d \$d ]; then echo none; elif [ -f \$d/remove ]; then echo removing; " +
                "elif [ -f \$d/disable ]; then echo disabled; elif [ -f /system/priv-app/SimRelayHost/SimRelayHost.apk ]; then echo active; " +
                "else echo enabled; fi", 5_000)
        if (suFailed(r)) rootLost("module check: ${r.out.takeLast(60)}")
        return when (r.last) {
            "pending" -> Module.PENDING_RESTART; "none" -> Module.NONE; "removing" -> Module.REMOVING
            "disabled" -> Module.DISABLED; "active" -> Module.ACTIVE; "enabled" -> Module.ENABLED_NEEDS_RESTART
            else -> Module.UNKNOWN
        }
    }

    // ---------------------------------------------------------------- host-mode health, and fixing it as root

    /** Runtime permissions the host needs (the module grants them; Fix grants them again). Only those this Android
     *  has: Bluetooth advertise/connect are Android 12+, notifications 13+ (an Android 11 host always reported them
     *  missing, and Fix could never clear the card: Redmi Note 8 Pro, 19 Oct 2026). */
    val RUNTIME = listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE, Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS) +
        (if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT) else emptyList()) +
        (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
    /** Privileged permissions only the module's privapp whitelist gives (missing = module not active as it should). */
    val PRIVILEGED = listOf("android.permission.MODIFY_PHONE_STATE", "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.CONTROL_INCALL_EXPERIENCE", "android.permission.TETHER_PRIVILEGED")

    fun missing(ctx: Context, perms: List<String>) = perms.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    /** The SMS role (RoleManager: Telephony's getDefaultSmsPackage lags behind it on Android 16, seen 19 Oct). */
    fun smsRole(ctx: Context) = ctx.getSystemService(android.app.role.RoleManager::class.java)
        ?.isRoleHeld(android.app.role.RoleManager.ROLE_SMS) ?: (Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName)
    fun batteryExempt(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    fun silent(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, "zen_mode", 0) == 2

    /**
     * Put back what the module sets at boot, now, as root (instead of "restart the phone"): permissions, battery
     * exemption, SMS role, total silence, Bluetooth on.
     */
    @Volatile private var lastHeal = 0L

    /**
     * A host repairs itself instead of waiting for someone to tap Fix now on a screen nobody looks at: an app update
     * (pm install, as the self-updater does) drops the battery exemption on MIUI until the next restart (Redmi, 20 Oct
     * 2026), and Android can take a permission or the SMS role away. At most every 10 minutes, only when something is
     * missing, never while the phone is being given back.
     */
    fun selfHeal(ctx: Context) {
        if (Retire.running || !Compat.isHostInstalled(ctx)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastHeal != 0L && now - lastHeal < 10 * 60_000L) return
        val gaps = listOfNotNull(
            "permissions".takeIf { missing(ctx, RUNTIME).isNotEmpty() }, "battery".takeIf { !batteryExempt(ctx) },
            "silence".takeIf { !silent(ctx) }, "sms role".takeIf { !smsRole(ctx) })
        if (gaps.isEmpty()) return
        lastHeal = now
        Log.w(TAG, "self-repair: ${gaps.joinToString()}")
        fixAll(ctx) {}
    }

    fun fixAll(ctx: Context, done: () -> Unit) {
        if (fixing) return
        fixing = true
        worker.execute {
            var waitRole = false
            try {
                val pkg = ctx.packageName
                val cmds = missing(ctx, RUNTIME).map { "pm grant $pkg $it" } +
                    listOf("dumpsys deviceidle whitelist +$pkg", "cmd notification allow_dnd $pkg", "cmd notification set_dnd none") +
                    if (ctx.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter?.isEnabled != true) listOf("svc bluetooth enable") else emptyList()
                val r = dev.simrelay.host.Root.sh(cmds.joinToString("; "), 30_000)
                Log.i(TAG, "setup fix applied (${cmds.size} commands, exit ${r.code})")
                Diag.event("setup_fix", JSONObject().put("what", "fix_all").put("commands", cmds.size).put("exit", r.code), withLog = false)
                if (!smsRole(ctx)) {
                    // The role goes through the daemon: done when it answers, so the card doesn't come back meanwhile.
                    waitRole = true
                    Daemon.query("ROLE ${android.app.role.RoleManager.ROLE_SMS} $pkg") { fixing = false; done() }
                }
            } finally {
                if (!waitRole) { fixing = false; done() }
            }
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    /** 64-bit Android (the daemon and module are arm64 only). */
    val is64Bit: Boolean get() = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
}
