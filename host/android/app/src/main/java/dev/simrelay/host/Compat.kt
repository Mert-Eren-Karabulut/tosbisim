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
            list.firstOrNull { p -> strings(p, "models").any { it.equals(Build.MODEL, ignoreCase = true) } }?.let { p ->
                return Verdict(tierOf(p), p.optString("name", fallbackName), note(p))
            }
            // Same codename, other model number (another region of the same phone): at most "expected".
            list.firstOrNull { p -> Build.DEVICE in strings(p, "devices") }?.let { p ->
                val t = tierOf(p)
                return Verdict(if (t == Tier.VERIFIED) Tier.EXPECTED else t, "${p.optString("name")} (${Build.MODEL})", note(p))
            }
        }
        // Not listed: the chip platform's call-audio family from the AndroidDumps corpus (support.json "platforms").
        val fams = table?.optJSONObject("families")
        val fam = table?.optJSONObject("platforms")?.let { pl -> Profiles.platformNames().firstNotNullOfOrNull { pl.optString(it).ifEmpty { null } } }
        if (fam != null) {
            val f = fams?.optJSONObject(fam)
            val router = f?.optBoolean("router", false) == true
            return Verdict(if (router) Tier.EXPERIMENTAL else Tier.NOT_YET, fallbackName, f?.let { local(it, "about") }.orEmpty())
        }
        return Verdict(Tier.UNKNOWN, fallbackName, "")
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

    fun bleAdvertising(ctx: Context): Boolean? {
        val a = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return if (a.isEnabled) a.isMultipleAdvertisementSupported else null      // unknown while Bluetooth is off
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

    fun soc(): String =
        (if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else "")
            .ifEmpty { prop("ro.board.platform") }

    /** Root works for this app (asks Magisk; the user grants it once). Call only on a user action. */
    fun rootWorks(): Boolean = Root.sh("id -u", 20_000).last == "0"

    fun prop(name: String): String = runCatching {
        val p = ProcessBuilder("getprop", name).start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor(2, TimeUnit.SECONDS) }
    }.getOrDefault("")
}

/** Installs the embedded Magisk module (no APK inside: it adopts this installed app) and restarts the phone. */
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
            val r = Root.sh("magisk --install-module ${Root.q(zip.absolutePath)}", 120_000)
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
