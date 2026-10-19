// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Telephony
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.simrelay.protocol.v1.Sim

/**
 * The host app's only screen. Two modes, one APK:
 *  - compatibility (installed as a normal app, before root): support tier for this phone, bootloader, root,
 *    Bluetooth, and "Install TosbiSim Host", which installs the embedded Magisk module and restarts;
 *  - host (installed by the module as a system app): the setup checklist, also shown after the box-mode
 *    escape gesture.
 * Each step shows pass / warning / fail with what to do; it refreshes every 2 s while visible.
 */
class SetupActivity : Activity() {
    private enum class State(val mark: String, val color: Int) {
        OK("✓", Color.rgb(52, 199, 89)), WARN("!", Color.rgb(255, 159, 10)), FAIL("✗", Color.rgb(255, 69, 58))
    }
    private data class Step(val title: String, val state: State, val detail: String)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    @Volatile private var daemon: String? = null
    private var host = false
    @Volatile private var rootOk: Boolean? = null          // asked only when the user taps (Magisk prompts)
    @Volatile private var installState: String? = null     // null, "busy", "ok", or an error
    @Volatile private var reportState: String? = null      // null, "busy", "ok", or an error

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = Compat.isHostInstalled(this)
        // The service owns the relay; make sure it runs (it is normally started by the Magisk module).
        if (host) startForegroundService(Intent(this, HostService::class.java))
        Api.refreshSupportTable(this)
        // Shown straight after the escape gesture: over the lock screen, which is dismissed if it has no PIN.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(28)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(16, 16, 18)); addView(list) })
    }

    override fun onResume() { super.onResume(); refresh.run() }
    override fun onPause() { super.onPause(); main.removeCallbacks(refresh) }

    private val refresh = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)              // one chain, however often it's run by hand
            if (host) { Daemon.query("HELLO") { r -> daemon = r }; render(steps()) } else renderCompat()
            main.postDelayed(this, 2000)
        }
    }

    // ------------------------------------------------------------------ checks

    private fun steps(): List<Step> {
        val s = mutableListOf<Step>()
        val d = daemon
        s += if (d?.startsWith("OK") == true)
            Step(getString(R.string.step_root), State.OK, d.removePrefix("OK ").substringBefore(" card"))
        else Step(getString(R.string.step_root), State.FAIL, getString(R.string.root_fail))

        val priv = listOf("android.permission.MODIFY_PHONE_STATE", "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.CONTROL_INCALL_EXPERIENCE", "android.permission.TETHER_PRIVILEGED")
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        s += if (priv.isEmpty()) Step(getString(R.string.step_system), State.OK, getString(R.string.system_ok))
        else Step(getString(R.string.step_system), State.FAIL,
            getString(R.string.system_fail, priv.joinToString { it.substringAfterLast('.') }))

        val runtime = (listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE,
            Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS) +
            if (android.os.Build.VERSION.SDK_INT >= 31)
                listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT) else emptyList())
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        s += if (runtime.isEmpty()) Step(getString(R.string.step_permissions), State.OK, getString(R.string.permissions_ok))
        else Step(getString(R.string.step_permissions), State.FAIL,
            getString(R.string.permissions_fail, runtime.joinToString { it.substringAfterLast('.') }))

        s += if (Telephony.Sms.getDefaultSmsPackage(this) == packageName)
            Step(getString(R.string.step_sms), State.OK, getString(R.string.sms_ok))
        else Step(getString(R.string.step_sms), State.FAIL, getString(R.string.sms_fail))

        s += if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            Step(getString(R.string.step_battery), State.OK, getString(R.string.battery_ok))
        else Step(getString(R.string.step_battery), State.WARN, getString(R.string.battery_warn))

        val bt = getSystemService(BluetoothManager::class.java).adapter
        val paired = Relay.pairedClient()
        val phone = getString(R.string.step_phone)
        s += when {
            bt?.isEnabled != true -> Step(getString(R.string.step_bluetooth), State.FAIL, getString(R.string.bluetooth_off))
            paired == null -> Step(phone, State.WARN,
                getString(if (Relay.pairingOpen()) R.string.phone_pairing_open else R.string.phone_not_paired))
            Relay.clientConnected() -> Step(phone, State.OK, getString(R.string.phone_connected, paired))
            else -> Step(phone, State.WARN, getString(R.string.phone_away, paired))
        }

        val sims = runCatching { Sims.status(this) }.getOrDefault(emptyList())
        s += if (sims.isEmpty()) Step(getString(R.string.step_sim), State.FAIL, getString(R.string.sim_none))
        else Step(getString(R.string.step_sim), if (sims.any { it.lock != Sim.Lock.UNLOCKED }) State.WARN else State.OK,
            sims.joinToString("\n") { sim ->
                getString(R.string.sim_line, sim.slot + 1, when {
                    sim.lock == Sim.Lock.PIN_REQUIRED -> getString(R.string.sim_pin)
                    sim.lock == Sim.Lock.PUK_REQUIRED -> getString(R.string.sim_puk)
                    sim.lock != Sim.Lock.UNLOCKED -> getString(R.string.sim_network_lock)
                    !sim.enabled -> getString(R.string.sim_off)
                    else -> getString(R.string.sim_ok, sim.operatorName, sim.signalLevel,
                        getString(if (sim.volte) R.string.yes else R.string.no))
                })
            })

        s += when {
            VoiceGate.fullVoice -> Step(getString(R.string.step_audio), State.OK, getString(R.string.audio_full))
            Daemon.hasProfile() -> Step(getString(R.string.step_audio), State.WARN, getString(R.string.audio_untested))
            else -> Step(getString(R.string.step_audio), State.WARN, getString(R.string.audio_basic))
        }

        s += if (Box.on) Step(getString(R.string.step_box), State.OK,
            getString(if (Box.chargeSupported) R.string.box_charge else R.string.box_charge_unsupported,
                getString(if (Box.suspended) R.string.box_suspended else R.string.box_on), Box.chargeLimit))
        else Step(getString(R.string.step_box), State.WARN, getString(R.string.box_off))
        return s
    }

    // ------------------------------------------------------------------ compatibility (before root)

    private fun compatSteps(): List<Step> {
        val s = mutableListOf<Step>()
        val v = Compat.verdict(this)
        s += Step(getString(R.string.step_this_phone), State.OK,
            getString(R.string.this_phone_detail, v.name, android.os.Build.VERSION.RELEASE, Compat.soc()))
        val (state, text) = when (v.tier) {
            Compat.Tier.VERIFIED -> State.OK to getString(R.string.tier_verified)
            Compat.Tier.EXPECTED -> State.OK to getString(R.string.tier_expected)
            Compat.Tier.EXPERIMENTAL -> State.WARN to getString(R.string.tier_experimental)
            Compat.Tier.NOT_YET -> State.WARN to getString(R.string.tier_not_yet)
            Compat.Tier.UNKNOWN -> State.WARN to getString(R.string.tier_unknown)
            Compat.Tier.UNSUPPORTED -> State.FAIL to getString(R.string.tier_unsupported)
        }
        s += Step(getString(R.string.step_support), state, listOf(text, v.note).filter { it.isNotEmpty() }.joinToString("\n"))

        val b = Compat.bootloader()
        s += Step(getString(R.string.step_boot), when {
            b.unlocked -> State.OK
            b.unlockSupported == false -> State.FAIL
            else -> State.WARN
        }, getString(when {
            b.unlocked -> R.string.boot_unlocked
            b.unlockSupported == false -> R.string.boot_no
            b.unlockSwitchOn == true -> R.string.boot_switch_on
            else -> R.string.boot_switch_off
        }))

        val magisk = Compat.magiskInstalled(this)
        s += Step(getString(R.string.step_root_magisk), when (rootOk) { true -> State.OK; false -> State.FAIL; null -> State.WARN },
            getString(when {
                rootOk == true -> R.string.root_ok
                rootOk == false -> R.string.root_denied
                magisk -> R.string.root_magisk
                else -> R.string.root_none
            }))

        val ble = Compat.bleAdvertising(this)
        s += Step(getString(R.string.step_bluetooth), when (ble) { true -> State.OK; false -> State.FAIL; null -> State.WARN },
            getString(when (ble) { true -> R.string.ble_ok; false -> R.string.ble_no; null -> R.string.ble_off }))
        return s
    }

    private fun renderCompat() {
        val steps = compatSteps()
        list.removeAllViews()
        list.addView(text(getString(R.string.app_name), 26f, Color.WHITE, bold = true))
        list.addView(text(getString(R.string.compat_subtitle), 15f, Color.rgb(170, 170, 176)).apply { setPadding(0, dp(4), 0, dp(18)) })
        for (st in steps) list.addView(row(st))
        val blocked = steps.any { it.state == State.FAIL && it.title != getString(R.string.step_root_magisk) }
        val btn = Button(this).apply {
            when {
                installState == "ok" -> { text = getString(R.string.btn_restart); setOnClickListener { Installer.reboot() } }
                installState == "busy" -> { text = getString(R.string.btn_installing); isEnabled = false }
                rootOk != true -> {
                    text = getString(R.string.btn_check_root); isEnabled = !blocked
                    setOnClickListener { Thread { rootOk = Compat.rootWorks(); main.post { renderCompat() } }.start() }
                }
                else -> {
                    text = getString(R.string.btn_install); isEnabled = !blocked
                    setOnClickListener {
                        installState = "busy"; renderCompat()
                        Installer.install(this@SetupActivity) { ok, log ->
                            installState = if (ok) "ok" else log.lines().lastOrNull { it.isNotBlank() } ?: "?"
                            main.post { renderCompat() }
                        }
                    }
                }
            }
        }
        list.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(16) })
        installState?.takeIf { it != "busy" }?.let {
            list.addView(text(if (it == "ok") getString(R.string.install_ok) else getString(R.string.install_fail, it), 14f,
                if (it == "ok") State.OK.color else State.FAIL.color).apply { setPadding(0, dp(10), 0, 0) })
        }
        addReportButton()
        list.addView(text(getString(R.string.host_version, BuildConfigInfo.version(this)), 12f, Color.GRAY).apply { setPadding(0, dp(16), 0, 0) })
    }

    // ------------------------------------------------------------------ views

    private fun render(steps: List<Step>) {
        list.removeAllViews()
        list.addView(text(getString(R.string.app_name), 26f, Color.WHITE, bold = true))
        val bad = steps.count { it.state == State.FAIL }
        list.addView(text(if (bad == 0) getString(R.string.ready) else resources.getQuantityString(R.plurals.steps_to_fix, bad, bad), 15f,
            if (bad == 0) State.OK.color else State.FAIL.color).apply { setPadding(0, dp(4), 0, dp(18)) })
        for (st in steps) list.addView(row(st))
        list.addView(Button(this).apply {
            text = getString(if (Relay.pairingOpen()) R.string.pairing_open else R.string.open_pairing)
            isEnabled = !Relay.pairingOpen()
            setOnClickListener { Relay.openPairing(); render(steps()) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(16) })
        addReportButton()
        list.addView(text(getString(R.string.host_version, BuildConfigInfo.version(this)), 12f, Color.GRAY).apply { setPadding(0, dp(16), 0, 0) })
    }

    /** Opt-in, anonymous compatibility report (Api.sendCompatReport): model, chip, Android, audio setup. */
    private fun addReportButton() {
        list.addView(Button(this).apply {
            text = getString(if (reportState == "busy") R.string.btn_sending else R.string.btn_send_report)
            isEnabled = reportState != "busy" && reportState != "ok"
            setOnClickListener {
                reportState = "busy"; refresh.run()
                Api.sendCompatReport(this@SetupActivity) { ok, detail -> reportState = if (ok) "ok" else detail; main.post { refresh.run() } }
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        val (msg, color) = when (val r = reportState) {
            null, "busy" -> getString(R.string.report_note) to Color.GRAY
            "ok" -> getString(R.string.report_sent) to State.OK.color
            else -> getString(R.string.report_failed, r) to State.FAIL.color
        }
        list.addView(text(msg, 12f, color).apply { setPadding(0, dp(4), 0, 0) })
    }

    private fun row(st: Step): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, dp(8))
        addView(text(st.state.mark, 18f, st.state.color, bold = true).apply {
            gravity = Gravity.CENTER; minWidth = dp(28)
        })
        addView(LinearLayout(this@SetupActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
            addView(text(st.title, 16f, Color.WHITE, bold = true))
            addView(text(st.detail, 14f, Color.rgb(170, 170, 176)))
        })
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
