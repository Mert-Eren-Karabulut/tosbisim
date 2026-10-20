// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.simrelay.protocol.v1.Sim
import java.util.concurrent.Executors

/**
 * The host app's only screen: the setup journey, one step at a time. Two modes, one APK:
 *  - before root (installed as a normal app): can this phone be a host, unlock, root, install the module, restart;
 *  - host (installed by the module as a system app): health (fixed in place as root), pairing, then "continue on
 *    your iPhone" (call test, box mode), and done. Also shown after the box-mode escape gesture.
 * The journey's state lives in [Onboarding] (process-level); this screen gathers facts off the main thread every 2 s
 * while visible, derives one [Model] and redraws only when it changes (no flicker, TalkBack focus kept).
 */
class SetupActivity : Activity() {
    private enum class Mark(val glyph: String) { OK("✓"), WARN("!"), FAIL("✗"), WAIT("…") }
    private data class Check(val title: String, val mark: Mark, val detail: String)
    private enum class Act { CHECK_ROOT, INSTALL, RESTART, REINSTALL, DEV_OPTIONS, MAGISK_GUIDE, FIX, OPEN_PAIRING, PAIR_OTHER, LOCK_SETTINGS }
    private data class Btn(val label: String, val act: Act?, val enabled: Boolean = true)
    private data class Card(val title: String, val body: String, val mark: Mark, val primary: Btn? = null, val secondary: Btn? = null,
                            val countdown: Boolean = false)
    private data class Model(val subtitle: String, val step: Int, val steps: Int, val card: Card, val checks: List<Check>)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "setup-facts").apply { isDaemon = true } }
    @Volatile private var resumed = false
    @Volatile private var daemon: String? = null
    @Volatile private var daemonProbed = false
    @Volatile private var daemonFails = 0
    @Volatile private var module = Onboarding.Module.UNKNOWN
    @Volatile private var moduleAt = 0L                      // module() is a root call: at most every MODULE_MS
    private val building = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var buildFails = 0
    private val createdAt = android.os.SystemClock.elapsedRealtime()
    @Volatile private var reportSent = false
    private var host = false
    private var shown: Model? = null
    private var countdown: TextView? = null
    private lateinit var root: LinearLayout
    private lateinit var c: Palette

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = Compat.isHostInstalled(this)
        Diag.milestone("setup_opened", org.json.JSONObject().put("host_installed", host))
        if (host) startForegroundService(Intent(this, HostService::class.java))   // normally started by the module
        Api.refreshSupportTable(this)
        // Shown straight after the escape gesture: over the lock screen, which is dismissed if it has no PIN.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
        c = Palette.of(resources.configuration)
        window.statusBarColor = c.bg
        window.navigationBarColor = c.bg
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(28)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(c.bg); addView(root) })
        // after setContentView: the window has no decor (and no insets controller) before it
        val light = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
        window.insetsController?.setSystemBarsAppearance(
            if (light) android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        root.addView(text(getString(R.string.checking), 16f, c.secondary))
    }

    override fun onResume() { super.onResume(); resumed = true; tick.run() }
    override fun onPause() { super.onPause(); resumed = false; main.removeCallbacks(tick) }
    override fun onDestroy() { super.onDestroy(); worker.shutdown() }

    private val tick = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            if (!resumed) return
            // The daemon is "down" only after two failed answers, once the service has had time to start it (a cold
            // start right after boot or a crash otherwise shows Restart / Reinstall for a moment).
            if (host && Daemon.isStarted()) Daemon.probe { r ->
                daemon = r
                daemonFails = if (r?.startsWith("OK") == true) 0 else daemonFails + 1
                daemonProbed = daemonFails >= 2 || r?.startsWith("OK") == true ||
                    android.os.SystemClock.elapsedRealtime() - createdAt > 15_000 && daemonFails >= 1
            }
            // One build at a time: a slow root call must not queue builds up behind it.
            if (building.compareAndSet(false, true)) worker.execute {
                val m = try { model().also { buildFails = 0 } } catch (e: Exception) {
                    Log.w("SimRelayHost", "setup screen: ${Log.getStackTraceString(e).take(600)}")
                    if (++buildFails == 3) Diag.event("setup_screen_error", org.json.JSONObject().put("what", e.javaClass.simpleName))
                    if (buildFails >= 3) errorModel() else null
                } finally { building.set(false) }
                main.post { if (resumed && m != null) show(m) }
            }
            main.postDelayed(this, 2000)
        }
    }

    private fun errorModel() = Model(getString(R.string.subtitle_setup), 0, 0,
        Card(getString(R.string.error_title), getString(R.string.error_body), Mark.FAIL), emptyList())

    /** The module's state, asked as root at most every 10 s (and at once after an action resets [moduleAt]). */
    private fun moduleNow(): Onboarding.Module {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - moduleAt > 10_000) { module = Onboarding.module(); moduleAt = now }
        return module
    }

    // ------------------------------------------------------------------ the journey

    private fun model(): Model = if (host) hostModel() else setupModel()

    /** Before root: 1 unlock, 2 root, 3 install, 4 restart; then the host steps. */
    private fun setupModel(): Model {
        val module = if (Onboarding.root == Onboarding.Root.OK) moduleNow() else Onboarding.Module.UNKNOWN
        val v = Compat.verdict(this)
        val boot = Compat.bootloader()
        val magisk = Compat.magiskInstalled(this)
        val ble = Compat.bleAdvertising(this)
        val rootState = Onboarding.root
        val rooted = rootState == Onboarding.Root.OK
        val checks = listOf(
            Check(getString(R.string.c_phone), Mark.OK, getString(R.string.c_phone_detail, v.name, Build.VERSION.RELEASE, Compat.soc())),
            Check(getString(R.string.c_support), tierMark(v.tier), tierText(v)),
            Check(getString(R.string.c_boot), if (rooted || boot.unlocked) Mark.OK else Mark.WARN,
                getString(if (rooted) R.string.boot_rooted else if (boot.unlocked) R.string.boot_unlocked else R.string.boot_locked)),
            Check(getString(R.string.c_root), when (rootState) {
                Onboarding.Root.OK -> Mark.OK; Onboarding.Root.CHECKING -> Mark.WAIT
                Onboarding.Root.UNKNOWN -> Mark.WARN; else -> Mark.FAIL }, rootText(rootState)),
            Check(getString(R.string.c_bluetooth), when (ble) { true -> Mark.OK; false -> Mark.FAIL; null -> Mark.WARN },
                getString(when (ble) { true -> R.string.ble_ok; false -> R.string.ble_no; null -> R.string.ble_off })),
        )
        val sub = getString(R.string.subtitle_setup)
        fun at(step: Int, card: Card) = Model(sub, step, 6, card, checks)
        val install = Onboarding.install
        return when {
            !Onboarding.is64Bit -> at(0, cant(getString(R.string.cant_32bit)))
            v.tier == Compat.Tier.UNSUPPORTED && !rooted -> at(0, cant(v.note.ifEmpty { getString(R.string.cant_unsupported_generic) }))
            ble == false -> at(0, cant(getString(R.string.ble_no)))
            install == Onboarding.Install.RESTART_PENDING && Onboarding.restartedSinceInstall(this) -> at(4, inactive())
            install == Onboarding.Install.RESTART_PENDING ->
                at(4, Card(getString(R.string.restart_title), getString(R.string.restart_body), Mark.OK, Btn(getString(R.string.btn_restart), Act.RESTART)))
            install == Onboarding.Install.BUSY ->
                at(3, Card(getString(R.string.install_title), getString(R.string.install_body), Mark.WAIT, Btn(getString(R.string.btn_installing), null, false)))
            install == Onboarding.Install.FAILED ->
                at(3, Card(getString(R.string.install_failed_title), getString(R.string.install_failed_body, Onboarding.installError),
                    Mark.FAIL, if (rooted) Btn(getString(R.string.btn_retry), Act.INSTALL) else Btn(getString(R.string.btn_check_root), Act.CHECK_ROOT),
                    if (rooted) Btn(getString(R.string.btn_check_root), Act.CHECK_ROOT) else null))
            // On in Magisk but not mounted this boot (turned back on, or installed from the Magisk app): restart.
            rooted && module in listOf(Onboarding.Module.PENDING_RESTART, Onboarding.Module.ENABLED_NEEDS_RESTART) ->
                at(4, Card(getString(R.string.restart_title), getString(R.string.restart_body), Mark.OK,
                    Btn(getString(R.string.btn_restart), Act.RESTART), Btn(getString(R.string.btn_reinstall), Act.REINSTALL)))
            rooted && module in listOf(Onboarding.Module.DISABLED, Onboarding.Module.REMOVING, Onboarding.Module.ACTIVE) -> at(4, inactive())
            rooted -> at(3, Card(getString(R.string.install_title), getString(R.string.install_body), Mark.OK, Btn(getString(R.string.btn_install), Act.INSTALL)))
            // Root checks first: a running check, or su that answered "no" / not at all, proves more than the bootloader
            // flag (which some phones spoof).
            rootState == Onboarding.Root.CHECKING -> at(2, Card(getString(R.string.root_title), getString(R.string.root_checking), Mark.WAIT,
                Btn(getString(R.string.btn_check_root), null, false)))
            rootState == Onboarding.Root.TIMEOUT || rootState == Onboarding.Root.DENIED -> at(2, Card(getString(R.string.root_title),
                listOf(getString(R.string.root_body), rootText(rootState)).joinToString("\n\n"), Mark.FAIL,
                Btn(getString(R.string.btn_check_root), Act.CHECK_ROOT), Btn(getString(R.string.btn_magisk_guide), Act.MAGISK_GUIDE)))
            !boot.unlocked && !magisk && boot.unlockSupported == false -> at(1, cant(getString(R.string.unlock_cant))
                .copy(secondary = Btn(getString(R.string.btn_check_root), Act.CHECK_ROOT)))
            !boot.unlocked && !magisk -> at(1, Card(getString(R.string.unlock_title),
                listOf(getString(R.string.unlock_body), getString(unlockHint()), getString(R.string.unlock_erase)).joinToString("\n\n"),
                Mark.WARN, Btn(getString(R.string.btn_dev_options), Act.DEV_OPTIONS), Btn(getString(R.string.btn_check_root), Act.CHECK_ROOT)))
            else -> at(2, Card(getString(R.string.root_title),
                listOf(getString(R.string.root_body), if (rootState == Onboarding.Root.UNKNOWN) "" else rootText(rootState))
                    .filter { it.isNotEmpty() }.joinToString("\n\n"),
                if (rootState == Onboarding.Root.UNKNOWN) Mark.WARN else Mark.FAIL,
                Btn(getString(R.string.btn_check_root), Act.CHECK_ROOT), Btn(getString(R.string.btn_magisk_guide), Act.MAGISK_GUIDE)))
        }
    }

    /** Running as the host: 5 health and pairing, 6 continue on the iPhone, then done. */
    private fun hostModel(): Model {
        Onboarding.hostRunning(this)
        val d = daemon
        val privMissing = Onboarding.missing(this, Onboarding.PRIVILEGED)
        val runtimeMissing = Onboarding.missing(this, Onboarding.RUNTIME)
        val sms = Onboarding.smsRole(this)
        val battery = Onboarding.batteryExempt(this)
        val silent = Onboarding.silent(this)
        val bt = getSystemService(BluetoothManager::class.java).adapter
        val btOn = bt?.isEnabled == true
        val paired = Relay.pairedClient()
        val sims = runCatching { Sims.status(this) }.getOrDefault(emptyList())
        val v = Compat.verdict(this)
        val permsOk = runtimeMissing.isEmpty() && sms && battery && silent
        // A PIN/pattern/password: after a restart or power cut TosbiSim can't start until someone unlocks the phone
        // (credential-encrypted storage), which nobody can on a phone without a screen (audit, 19 Oct).
        val locked = getSystemService(KeyguardManager::class.java).isDeviceSecure
        val daemonOk = d?.startsWith("OK") == true
        val checks = listOf(
            Check(getString(R.string.c_phone), Mark.OK, getString(R.string.c_phone_detail, v.name, Build.VERSION.RELEASE, Compat.soc())),
            Check(getString(R.string.c_service), if (daemonOk) Mark.OK else if (daemonProbed) Mark.FAIL else Mark.WAIT,
                if (daemonOk) getString(R.string.service_ok, d!!.removePrefix("OK ").substringBefore(" card")) else getString(R.string.service_down)),
            Check(getString(R.string.c_module), if (privMissing.isEmpty()) Mark.OK else Mark.FAIL,
                if (privMissing.isEmpty()) getString(R.string.module_active) else getString(R.string.fix_privileged, names(privMissing))),
            Check(getString(R.string.c_permissions), if (permsOk) Mark.OK else Mark.WARN,
                if (permsOk) getString(R.string.perm_ok) else problems(runtimeMissing, sms, battery, silent, true).joinToString(" ")),
            Check(getString(R.string.c_bluetooth), if (btOn) Mark.OK else Mark.FAIL, getString(if (btOn) R.string.ble_ok else R.string.ble_off)),
            Check(getString(R.string.c_lock), if (locked) Mark.WARN else Mark.OK, getString(if (locked) R.string.lock_on else R.string.lock_off)),
            Check(getString(R.string.c_iphone), if (paired != null && Relay.clientConnected()) Mark.OK else Mark.WARN,
                when { paired == null -> getString(R.string.iphone_none); Relay.clientConnected() -> getString(R.string.iphone_connected, paired)
                    else -> getString(R.string.iphone_away, paired) }),
            Check(getString(R.string.c_sim), when { sims.isEmpty() -> Mark.FAIL; sims.any { it.lock != Sim.Lock.UNLOCKED } -> Mark.WARN; else -> Mark.OK },
                if (sims.isEmpty()) getString(R.string.sim_none) else sims.joinToString("\n") { simLine(it) }),
            Check(getString(R.string.c_audio), if (VoiceGate.fullVoice) Mark.OK else Mark.WARN,
                getString(when { VoiceGate.fullVoice -> R.string.audio_full; Daemon.hasProfile() -> R.string.audio_untested; else -> R.string.audio_basic })),
            Check(getString(R.string.c_box), if (Box.on) Mark.OK else Mark.WARN,
                if (Box.on) getString(R.string.box_on, Box.chargeLimit) else getString(R.string.box_off)),
        )
        val sub = getString(R.string.subtitle_host)
        fun at(step: Int, card: Card) = Model(sub, step, 6, card, checks)
        val fixing = Onboarding.fixing
        val pairing = Relay.pairingOpen()
        // Paired but not finished: another iPhone can still take over (a phone passed on, a new iPhone).
        val other = if (pairing) null else Btn(getString(R.string.btn_pair_other), Act.PAIR_OTHER)
        return when {
            // A reinstall from this screen: its progress and result before anything else (a restart finishes it).
            Onboarding.install == Onboarding.Install.BUSY ->
                at(5, Card(getString(R.string.install_title), getString(R.string.install_body), Mark.WAIT, Btn(getString(R.string.btn_installing), null, false)))
            Onboarding.install == Onboarding.Install.FAILED ->
                at(5, Card(getString(R.string.install_failed_title), getString(R.string.install_failed_body, Onboarding.installError),
                    Mark.FAIL, Btn(getString(R.string.btn_retry), Act.REINSTALL), Btn(getString(R.string.btn_restart), Act.RESTART)))
            Onboarding.install == Onboarding.Install.RESTART_PENDING ->
                at(5, Card(getString(R.string.restart_title), getString(R.string.restart_body), Mark.OK, Btn(getString(R.string.btn_restart), Act.RESTART)))
            daemonProbed && !daemonOk -> at(5, Card(getString(R.string.fix_title), getString(R.string.fix_daemon), Mark.FAIL,
                Btn(getString(R.string.btn_restart), Act.RESTART), Btn(getString(R.string.btn_reinstall), Act.REINSTALL)))
            privMissing.isNotEmpty() -> at(5, Card(getString(R.string.fix_title), getString(R.string.fix_privileged, names(privMissing)), Mark.FAIL,
                Btn(getString(R.string.btn_reinstall), Act.REINSTALL), Btn(getString(R.string.btn_restart), Act.RESTART)))
            !permsOk || !btOn -> at(5, Card(getString(R.string.fix_title),
                (problems(runtimeMissing, sms, battery, silent, btOn) + getString(R.string.fix_hint)).joinToString("\n"), Mark.WARN,
                Btn(getString(if (fixing) R.string.btn_fixing else R.string.btn_fix), if (fixing) null else Act.FIX, !fixing)))
            // Unpaired: ready for the first iPhone, nothing to open.
            locked -> at(5, Card(getString(R.string.lock_title), getString(R.string.lock_body), Mark.WARN,
                Btn(getString(R.string.btn_lock_settings), Act.LOCK_SETTINGS)))
            paired == null -> at(5, Card(getString(R.string.pair_title), getString(R.string.pair_body, btName(bt)), Mark.WARN, countdown = true))
            sims.isEmpty() -> at(6, Card(getString(R.string.sim_title), getString(R.string.sim_body), Mark.FAIL))
            sims.any { it.lock != Sim.Lock.UNLOCKED } ->
                at(6, Card(getString(R.string.sim_title), getString(R.string.sim_locked, sims.first { it.lock != Sim.Lock.UNLOCKED }.slot + 1), Mark.WARN))
            Daemon.hasProfile() && !VoiceGate.fullVoice -> at(6, Card(getString(R.string.next_title),
                getString(if (VoiceGate.passedBeforeUpdate) R.string.next_retest else R.string.next_test, paired), Mark.WARN,
                secondary = other, countdown = pairing))
            !Daemon.hasProfile() && !Box.on -> at(6, Card(getString(R.string.next_title), getString(R.string.next_basic, paired), Mark.WARN,
                secondary = other, countdown = pairing))
            !Box.on -> at(6, Card(getString(R.string.next_title), getString(R.string.next_box, paired), Mark.WARN,
                secondary = other, countdown = pairing))
            // A different iPhone may pair while the window is open (it then replaces this one): show the countdown.
            else -> Model(sub, 0, 0, Card(getString(R.string.done_title), getString(R.string.done_body), Mark.OK,
                secondary = other, countdown = pairing), checks)
        }
    }

    private fun cant(why: String) = Card(getString(R.string.cant_title), "$why\n\n${getString(R.string.cant_alt)}", Mark.FAIL)

    private fun inactive(): Card {
        val m = moduleNow()
        val why = getString(when (m) {
            Onboarding.Module.DISABLED -> R.string.inactive_disabled
            Onboarding.Module.REMOVING -> R.string.inactive_removing
            Onboarding.Module.NONE -> R.string.inactive_none
            Onboarding.Module.PENDING_RESTART, Onboarding.Module.ENABLED_NEEDS_RESTART -> R.string.inactive_pending
            else -> R.string.inactive_other
        })
        Diag.event("module_inactive", org.json.JSONObject().put("what", m.name.lowercase()))
        val restart = m == Onboarding.Module.PENDING_RESTART || m == Onboarding.Module.ENABLED_NEEDS_RESTART
        return Card(getString(R.string.inactive_title), why, Mark.FAIL,
            if (restart) Btn(getString(R.string.btn_restart), Act.RESTART) else Btn(getString(R.string.btn_reinstall), Act.REINSTALL),
            if (restart) null else Btn(getString(R.string.btn_restart), Act.RESTART))
    }

    private fun problems(runtime: List<String>, sms: Boolean, battery: Boolean, silent: Boolean, bt: Boolean) = listOfNotNull(
        if (!bt) getString(R.string.fix_bluetooth) else null,
        if (runtime.isNotEmpty()) getString(R.string.fix_permissions, names(runtime)) else null,
        if (!sms) getString(R.string.fix_sms) else null,
        if (!battery) getString(R.string.fix_battery) else null,
        if (!silent) getString(R.string.fix_silence) else null,
    )

    private fun names(perms: List<String>) = perms.joinToString { it.substringAfterLast('.') }

    private fun unlockHint() = when (Build.MANUFACTURER.lowercase()) {
        "samsung" -> R.string.unlock_samsung
        "xiaomi", "redmi", "poco" -> R.string.unlock_xiaomi
        "google" -> R.string.unlock_pixel
        "motorola", "sony" -> R.string.unlock_code
        else -> R.string.unlock_other
    }

    @Suppress("MissingPermission")
    private fun btName(bt: android.bluetooth.BluetoothAdapter?) = runCatching { bt?.name }.getOrNull() ?: Build.MODEL

    private fun tierMark(t: Compat.Tier) = when (t) {
        Compat.Tier.VERIFIED, Compat.Tier.EXPECTED -> Mark.OK
        Compat.Tier.UNSUPPORTED -> Mark.FAIL
        else -> Mark.WARN
    }

    private fun tierText(v: Compat.Verdict): String {
        val t = getString(when (v.tier) {
            Compat.Tier.VERIFIED -> R.string.tier_verified; Compat.Tier.EXPECTED -> R.string.tier_expected
            Compat.Tier.EXPERIMENTAL -> R.string.tier_experimental; Compat.Tier.NOT_YET -> R.string.tier_not_yet
            Compat.Tier.UNKNOWN -> R.string.tier_unknown; Compat.Tier.UNSUPPORTED -> R.string.tier_unsupported
        })
        return listOf(t, v.note).filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun rootText(r: Onboarding.Root) = getString(when (r) {
        Onboarding.Root.OK -> R.string.root_ok; Onboarding.Root.CHECKING -> R.string.root_checking
        Onboarding.Root.NO_SU -> R.string.root_no_su; Onboarding.Root.TIMEOUT -> R.string.root_timeout
        Onboarding.Root.DENIED -> R.string.root_denied; Onboarding.Root.UNKNOWN -> R.string.root_unknown
    })

    private fun simLine(s: Sim) = getString(R.string.sim_line, s.slot + 1, when {
        s.lock == Sim.Lock.PIN_REQUIRED -> getString(R.string.sim_pin)
        s.lock == Sim.Lock.PUK_REQUIRED -> getString(R.string.sim_puk)
        s.lock != Sim.Lock.UNLOCKED -> getString(R.string.sim_network_lock)
        !s.enabled -> getString(R.string.sim_off)
        else -> getString(R.string.sim_ok, s.operatorName, s.signalLevel, getString(if (s.volte) R.string.yes else R.string.no))
    })

    // ------------------------------------------------------------------ actions

    private fun act(a: Act) {
        val again = { main.post { shown = null; tick.run() }; Unit }
        when (a) {
            Act.CHECK_ROOT -> { Onboarding.checkRoot(this) { moduleAt = 0; again() }; again() }
            Act.INSTALL, Act.REINSTALL -> { Onboarding.installModule(this) { moduleAt = 0; again() }; again() }
            Act.RESTART -> Installer.reboot()
            Act.FIX -> { Onboarding.fixAll(this) { again() }; again() }
            Act.OPEN_PAIRING -> { Relay.openPairing(); again() }
            Act.PAIR_OTHER -> AlertDialog.Builder(this)
                .setMessage(getString(R.string.pair_other_confirm, Relay.pairedClient() ?: ""))
                .setPositiveButton(R.string.btn_continue) { _, _ -> Relay.openPairing(); again() }
                .setNegativeButton(R.string.btn_cancel, null).show()
            Act.DEV_OPTIONS -> runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                .onFailure { runCatching { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) } }
            Act.LOCK_SETTINGS -> runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
            Act.MAGISK_GUIDE -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://topjohnwu.github.io/Magisk/install.html"))) }
        }
    }

    // ------------------------------------------------------------------ views

    private fun show(m: Model) {
        if (Retire.running) return                     // the removal's own progress screen stays
        if (m != shown) {
            shown = m
            root.removeAllViews()
            root.addView(text(getString(R.string.app_name), 28f, c.text, bold = true))
            root.addView(text(m.subtitle, 15f, c.secondary).apply { setPadding(0, dp(2), 0, dp(14)) })
            if (m.steps > 0) root.addView(progress(m.step, m.steps))
            root.addView(card(m.card))
            root.addView(text(getString(R.string.checks_title), 13f, c.secondary, bold = true).apply { setPadding(dp(4), dp(22), 0, dp(6)) })
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(c.card, 16)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                m.checks.forEach { addView(row(it)) }
            })
            root.addView(diagnostics(), lp(top = 14))
            if (host) root.addView(retireCard(), lp(top = 14))
            root.addView(text(getString(R.string.host_version, BuildConfigInfo.version(this)), 12f, c.secondary).apply { setPadding(dp(4), dp(16), 0, 0) })
        }
        updateCountdown()
    }

    private fun progress(step: Int, steps: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), 0, 0, dp(10))
        if (step > 0) addView(text(getString(R.string.progress, step, steps), 13f, c.secondary))
        addView(LinearLayout(this@SetupActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            for (i in 1..steps) addView(View(this@SetupActivity).apply { background = rounded(if (i <= step) c.accent else c.track, 3) },
                LinearLayout.LayoutParams(0, dp(5), 1f).apply { if (i > 1) marginStart = dp(4) })
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }

    private fun card(k: Card) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c.card, 18)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        addView(LinearLayout(this@SetupActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(badge(k.mark, 30))
            addView(text(k.title, 20f, c.text, bold = true).apply { setPadding(dp(12), 0, 0, 0); isFocusable = true })
        })
        addView(text(k.body, 15.5f, c.text).apply { setPadding(0, dp(12), 0, 0); setLineSpacing(0f, 1.15f) })
        countdown = if (k.countdown) text("", 14f, c.accent, bold = true).apply { setPadding(0, dp(10), 0, 0) }.also { addView(it) } else null
        k.primary?.let { addView(button(it, primary = true), lp(top = 16)) }
        k.secondary?.let { addView(button(it, primary = false), lp(top = 8)) }
    }

    private fun updateCountdown() {
        val t = countdown ?: return
        val left = Relay.pairingSecondsLeft()
        t.text = when {
            Relay.pairedClient() == null -> getString(R.string.pair_ready)
            left > 0 -> getString(R.string.pair_open_left, "%d:%02d".format(left / 60, left % 60))
            else -> getString(R.string.pair_closed)
        }
    }

    private fun row(k: Check) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(10), 0, dp(10))
        addView(badge(k.mark, 22))
        addView(LinearLayout(this@SetupActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(text(k.title, 15f, c.text, bold = true))
            addView(text(k.detail, 13.5f, c.secondary))
        })
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        contentDescription = "${k.title}: ${markName(k.mark)}. ${k.detail}"
    }

    private fun diagnostics() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c.card, 16)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        addView(text(getString(R.string.diag_title), 15f, c.text, bold = true))
        addView(Switch(this@SetupActivity).apply {
            text = getString(R.string.diag_switch); setTextColor(c.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            isChecked = Diag.enabled
            setOnCheckedChangeListener { _, on -> Diag.enabled = on }
        }, lp(top = 6))
        addView(text(getString(R.string.diag_note), 12.5f, c.secondary).apply { setPadding(0, dp(4), 0, 0) })
        addView(button(Btn(getString(if (reportSent) R.string.report_sent else R.string.btn_send_report), null, !reportSent), primary = false).apply {
            if (!reportSent) setOnClickListener {
                Diag.event("user_report", org.json.JSONObject().put("what", if (host) "host" else "setup")
                    .put("screen", shown?.card?.title ?: ""), force = true)
                reportSent = true; shown = null; tick.run()
            }
        }, lp(top = 10))
    }

    /** "Stop using this phone as a host": the phone back as an ordinary phone, TosbiSim removed (Retire). */
    private fun retireCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c.card, 16)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        addView(text(getString(R.string.retire_title), 15f, c.text, bold = true))
        addView(text(getString(R.string.retire_note), 12.5f, c.secondary).apply { setPadding(0, dp(4), 0, 0) })
        addView(button(Btn(getString(R.string.retire_btn), null), primary = false).apply {
            setTextColor(c.fail)
            setOnClickListener {
                AlertDialog.Builder(this@SetupActivity).setTitle(R.string.retire_confirm_title)
                    .setMessage(R.string.retire_confirm_body)
                    .setPositiveButton(R.string.retire_confirm_yes) { _, _ -> retire() }
                    .setNegativeButton(R.string.btn_cancel, null).show()
            }
        }, lp(top = 10))
    }

    private fun retire() {
        root.removeAllViews()
        root.addView(text(getString(R.string.app_name), 28f, c.text, bold = true))
        val status = text("", 16f, c.text).apply { setPadding(0, dp(14), 0, 0); setLineSpacing(0f, 1.15f) }
        root.addView(status)
        Retire.start(this) { s -> status.text = s }
    }

    private fun badge(m: Mark, size: Int) = TextView(this).apply {
        text = m.glyph; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size * 0.5f)
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(when (m) { Mark.OK -> c.accent; Mark.WARN -> c.warn; Mark.FAIL -> c.fail; Mark.WAIT -> c.secondary })
        }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun button(b: Btn, primary: Boolean) = Button(this).apply {
        text = b.label; isAllCaps = false; isEnabled = b.enabled
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(if (primary) Color.WHITE else if (b.enabled) c.accent else c.secondary)
        minHeight = dp(52); stateListAnimator = null
        val shape = if (primary) rounded(if (b.enabled) c.accent else c.track, 14)
        else GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setStroke(dp(1), c.track); setColor(Color.TRANSPARENT) }
        background = RippleDrawable(ColorStateList.valueOf(c.ripple), shape, null)
        b.act?.let { a -> setOnClickListener { act(a) } }
    }

    private fun markName(m: Mark) = when (m) { Mark.OK -> "done"; Mark.WARN -> "to do"; Mark.FAIL -> "problem"; Mark.WAIT -> "in progress" }

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { cornerRadius = dp(r).toFloat(); setColor(color) }

    private fun lp(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Light and dark, following the system. */
    private data class Palette(val bg: Int, val card: Int, val text: Int, val secondary: Int, val accent: Int, val warn: Int,
                               val fail: Int, val track: Int, val ripple: Int) {
        companion object {
            fun of(cfg: Configuration) = if ((cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
                Palette(Color.rgb(12, 12, 14), Color.rgb(28, 28, 32), Color.rgb(242, 242, 247), Color.rgb(160, 160, 168),
                    Color.rgb(48, 209, 88), Color.rgb(255, 159, 10), Color.rgb(255, 69, 58), Color.rgb(58, 58, 64), Color.argb(60, 255, 255, 255))
            else
                Palette(Color.rgb(242, 242, 247), Color.WHITE, Color.rgb(20, 20, 24), Color.rgb(98, 98, 108),
                    Color.rgb(36, 138, 61), Color.rgb(199, 110, 0), Color.rgb(215, 0, 21), Color.rgb(222, 222, 228), Color.argb(40, 0, 0, 0))
        }
    }
}
