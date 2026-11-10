// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Compatibility of this phone, readable without root: the TosbiSim Host APK is installed as a normal app first and
 * tells people what their phone can do before they root it. Tiers come from profiles/support.json (shared with the
 * iPhone app and the support page). Full voice still needs the in-app call test on every phone.
 */
object Compat {
    private const val TAG = "SimRelayHost"

    enum class Tier { VERIFIED, EXPECTED, EXPERIMENTAL, NOT_YET, UNKNOWN, UNSUPPORTED }
    data class Verdict(val tier: Tier, val name: String, val note: String)

    /** Running as the real host: installed by the Magisk module as a privileged system app. */
    fun isHostInstalled(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.MODIFY_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    @Volatile private var cached: Triple<Long, String, Verdict>? = null

    /** This phone's support verdict; recomputed only when the live table or the language changes (it is ~300 KB). */
    fun verdict(ctx: Context): Verdict {
        val key = File(ctx.filesDir, "support.json").lastModified()
        val lang = java.util.Locale.getDefault().language
        cached?.let { (k, l, v) -> if (k == key && l == lang) return v }
        return compute(ctx).also { cached = Triple(key, lang, it) }
    }

    /**
     * The live table from api.tosbisim.com when we have one (Api.refreshSupportTable), else the bundled copy; a table
     * that fails to evaluate falls back to the bundled one. Never throws: this runs on every status the host sends,
     * so a bad server table would otherwise crash every host at every start (audit, 19 Oct).
     */
    private fun compute(ctx: Context): Verdict {
        val live = File(ctx.filesDir, "support.json")
        if (live.exists()) runCatching { return evaluate(JSONObject(live.readText())) }
            .onFailure { Log.w(TAG, "live support table unusable, using the bundled one: $it") }
        return runCatching { evaluate(JSONObject(ctx.assets.open("support.json").bufferedReader().readText())) }
            .onFailure { Log.w(TAG, "bundled support table unusable: $it") }
            .getOrDefault(Verdict(Tier.UNKNOWN, "${Build.MANUFACTURER} ${Build.MODEL}", ""))
    }

    /** A downloaded table is accepted only if it evaluates (Api.refreshSupportTable). */
    fun validTable(text: String): Boolean = runCatching {
        val t = JSONObject(text)
        require((t.optJSONArray("phones")?.length() ?: 0) > 0) { "no phones" }
        evaluate(t); true
    }.onFailure { Log.w(TAG, "support table rejected: $it") }.getOrDefault(false)

    private fun evaluate(table: JSONObject?): Verdict {
        val maker = Build.MANUFACTURER.lowercase()
        val fallbackName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        // Rules first: a US Samsung shares its codename with an unlockable model but can't be rooted.
        table?.optJSONArray("rules")?.let { rules ->
            for (i in 0 until rules.length()) {
                val r = rules.optJSONObject(i) ?: continue
                val makers = r.optJSONArray("manufacturers")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                // A pattern Java can't compile is skipped, not fatal
                val pattern = r.optString("model_regex")
                val regex = if (pattern.isEmpty()) null else (runCatching { pattern.toRegex() }.getOrNull() ?: continue)
                val minSdk = r.optInt("min_sdk", 0)
                val hit = when {
                    minSdk > 0 -> Build.VERSION.SDK_INT < minSdk
                    makers != null && maker !in makers -> false
                    regex != null -> regex.matches(Build.MODEL)
                    else -> makers != null
                }
                if (hit) return Verdict(Tier.UNSUPPORTED, fallbackName, local(r, "reason"))
            }
        }
        table?.optJSONArray("phones")?.let { phones ->
            val list = (0 until phones.length()).mapNotNull { phones.optJSONObject(it) }
            fun strings(p: JSONObject, k: String) = p.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            fun tierOf(p: JSONObject) = runCatching { Tier.valueOf(p.getString("tier").uppercase()) }.getOrDefault(Tier.UNKNOWN)
            // The exact model number wins over a shared codename (the S20 FE 5G is also "r8q").
            // An unsupported row without its own note (corpus rows): the maker rule's reason, so people learn why.
            fun note(p: JSONObject): String = local(p, "note").ifEmpty {
                if (tierOf(p) != Tier.UNSUPPORTED) "" else table.optJSONArray("rules")?.let { rules ->
                    (0 until rules.length()).mapNotNull { rules.optJSONObject(it) }.firstOrNull { r ->
                        r.optJSONArray("manufacturers")?.let { a -> (0 until a.length()).any { a.optString(it) == maker } } == true
                    }?.let { local(it, "reason") }
                }.orEmpty()
            }
            // A row naming this model AND this codename first: Xiaomi's model is a marketing name two phones can share
            // ("Redmi Note 9 Pro" is joyeuse, and in India curtana, where only joyeuse passed the call test; 10 Nov 2026)
            val named = list.filter { p -> strings(p, "models").any { it.equals(Build.MODEL, ignoreCase = true) } }
            (named.firstOrNull { p -> Build.DEVICE in strings(p, "devices") } ?: named.firstOrNull())?.let { p ->
                return Verdict(tierOf(p), p.optString("name", fallbackName), note(p))
            }
            // Same codename, other model number (another region of the same phone): at most "expected".
            list.firstOrNull { p -> Build.DEVICE in strings(p, "devices") }?.let { p ->
                val t = tierOf(p)
                return Verdict(if (t == Tier.VERIFIED) Tier.EXPECTED else t, "${p.optString("name")} (${Build.MODEL})", note(p))
            }
        }
        // Not listed: the chip platform's call-audio family from the AndroidDumps corpus (support.json "platforms"),
        // "expected" where every Android 11+ phone with that chip builds our auto profile ("platform_tiers", 31 Oct 2026)
        val fams = table?.optJSONObject("families")
        fun byPlatform(key: String) = table?.optJSONObject(key)?.let { pl -> Profiles.platformNames().firstNotNullOfOrNull { pl.optString(it).ifEmpty { null } } }
        val fam = byPlatform("platforms")
        if (fam != null) {
            val f = fams?.optJSONObject(fam)
            val router = f?.optBoolean("router", false) == true
            val tier = when { !router -> Tier.NOT_YET; byPlatform("platform_tiers") == "expected" -> Tier.EXPECTED; else -> Tier.EXPERIMENTAL }
            return Verdict(tier, fallbackName, f?.let { local(it, "about") }.orEmpty())
        }
        return Verdict(Tier.UNKNOWN, fallbackName, "")
    }

    /**
     * The table's verdict corrected by what the host found on this phone: a call-audio profile that fits its own card
     * (Daemon) is what "expected" means, whatever the table guessed from the model or the chip; the call test still
     * decides. A route no phone has proven on a call yet (a profile saying "experimental": a new family's first
     * release) stays experimental. Reports keep the table's tier (with the profile's source next to it).
     */
    fun onThisPhone(v: Verdict, profile: Profile?): Verdict = when {
        profile == null || v.tier !in setOf(Tier.EXPERIMENTAL, Tier.NOT_YET, Tier.UNKNOWN) -> v
        profile.json.optBoolean("experimental") -> v.copy(tier = Tier.EXPERIMENTAL)
        else -> v.copy(tier = Tier.EXPECTED)
    }

    /** "note" / "reason" in the phone's language: support.json carries a Turkish "<key>_tr". */
    private fun local(o: JSONObject, key: String): String =
        (if (java.util.Locale.getDefault().language == "tr") o.optString("${key}_tr") else "").ifEmpty { o.optString(key) }

    // ---------------------------------------------------------------- pre-root facts

    data class Boot(val unlockSupported: Boolean?, val unlockSwitchOn: Boolean?, val unlocked: Boolean)

    fun bootloader(): Boot {
        val supported = prop("ro.oem_unlock_supported").takeIf { it.isNotEmpty() }?.let { it == "1" }
        val allowed = prop("sys.oem_unlock_allowed").takeIf { it.isNotEmpty() }?.let { it == "1" }
        val unlocked = prop("ro.boot.flash.locked") == "0" || prop("ro.boot.verifiedbootstate") == "orange"
        return Boot(supported, allowed, unlocked)
    }

    fun magiskInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo("com.topjohnwu.magisk", 0); true }.getOrDefault(false) ||
            File("/system/bin/su").exists() || File("/sbin/su").exists() || File("/debug_ramdisk/su").exists()

    /**
     * The root this phone has, as its manager app shows: the screens name that app and its menus, diagnostics report it.
     * Magisk is what the guide installs; APatch and KernelSU users came anyway (a Mi 11 Pro on APatch, 4 Nov 2026), and
     * every screen sent them to "Magisk → Superuser". Their manager apps tell them apart without root.
     */
    enum class RootKind(val label: String, val modulesTab: Int, val packages: List<String>) {
        MAGISK("Magisk", R.string.tab_modules_magisk, listOf("com.topjohnwu.magisk")),
        APATCH("APatch", R.string.tab_modules_apatch, listOf("me.bmax.apatch")),
        KERNELSU("KernelSU", R.string.tab_modules_kernelsu, listOf("me.weishu.kernelsu", "com.rifsxd.ksunext", "com.sukisu.ultra"));
        val id get() = name.lowercase()
    }

    private fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** APatch or KernelSU when its manager is installed, else Magisk (also a hidden one, under another name). */
    fun rootKind(ctx: Context): RootKind =
        listOf(RootKind.APATCH, RootKind.KERNELSU).firstOrNull { k -> k.packages.any { installed(ctx, it) } } ?: RootKind.MAGISK

    /** The root manager's own app, when it can be opened. */
    fun rootManagerIntent(ctx: Context): android.content.Intent? =
        rootKind(ctx).packages.firstNotNullOfOrNull { runCatching { ctx.packageManager.getLaunchIntentForPackage(it) }.getOrNull() }

    /** Before the host runs: false without Bluetooth LE at all, null while Bluetooth is off. Whether advertising works
     *  shows once the host advertises ([BleLink.advStatus]): no flag answers that ahead of time. */
    fun bluetoothLe(ctx: Context): Boolean? {
        if (!ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)) return false
        val a = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return if (a.isEnabled) true else null
    }

    /** The Bluetooth controller's LE abilities, for diagnostics: what separates "should work" from "works" on
     *  phones we never held (advertising sets, PHYs, data length). Null while Bluetooth is off. */
    @Suppress("MissingPermission")
    fun bleCaps(ctx: Context): org.json.JSONObject? {
        val a = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!a.isEnabled) return null
        return runCatching {
            org.json.JSONObject()
                .put("multi_adv", a.isMultipleAdvertisementSupported).put("ext_adv", a.isLeExtendedAdvertisingSupported)
                .put("le_2m", a.isLe2MPhySupported).put("le_coded", a.isLeCodedPhySupported)
                .put("periodic_adv", a.isLePeriodicAdvertisingSupported).put("max_adv_len", a.leMaximumAdvertisingDataLength)
                .put("offload_filter", a.isOffloadedFilteringSupported)
        }.getOrNull()
    }

    /** The chip as Android names it; some custom ROMs say "unknown unknown" (a Galaxy J7 Prime): the board platform then. */
    fun soc(): String =
        (if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "")
            .split(' ').filter { it.isNotEmpty() && !it.equals(Build.UNKNOWN, ignoreCase = true) }.joinToString(" ")
            .ifEmpty { prop("ro.board.platform") }.ifEmpty { Build.HARDWARE }

    /** Root works for this app (asks Magisk; the user grants it once). Call only on a user action. */
    fun rootWorks(): Boolean = Root.sh("id -u", 20_000).last == "0"

    fun prop(name: String): String = runCatching {
        val p = ProcessBuilder("getprop", name).start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor(2, TimeUnit.SECONDS) }
    }.getOrDefault("")
}

/** Installs the embedded module (no APK inside: it adopts this installed app) with the root manager's own installer. */
object Installer {
    private const val TAG = "SimRelayHost"
    private const val ASSET = "module/tosbisim-host-module.zip"

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Installs the embedded module; false (and no [done]) while another install is still running. */
    fun install(ctx: Context, done: (ok: Boolean, log: String) -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        Thread { try { run(ctx, done) } finally { running.set(false) } }.start()
        return true
    }

    private fun run(ctx: Context, done: (ok: Boolean, log: String) -> Unit) {
        val r = runCatching {
            val zip = File(ctx.cacheDir, "tosbisim-host-module.zip")
            ctx.assets.open(ASSET).use { i -> zip.outputStream().use { i.copyTo(it) } }
            // APatch and KernelSU install modules with their own daemon; told apart as root by its files, not by app
            val z = Root.q(zip.absolutePath)
            val r = Root.sh("if [ -x /data/adb/apd ]; then /data/adb/apd module install $z; " +
                "elif [ -x /data/adb/ksud ]; then /data/adb/ksud module install $z; else magisk --install-module $z; fi", 120_000)
            r.ok to r.out
        }.getOrElse { false to "$it" }
        Log.i(TAG, "module install ${if (r.first) "ok" else "FAILED"}:\n${r.second.takeLast(800)}")
        if (r.first) Diag.milestone("module_installed")
        else Diag.event("module_install_failed", org.json.JSONObject().put("output", Scrub.text(r.second.takeLast(3000))))
        done(r.first, r.second)
    }

    fun reboot() {
        Root.background("svc power reboot || reboot")
    }
}
