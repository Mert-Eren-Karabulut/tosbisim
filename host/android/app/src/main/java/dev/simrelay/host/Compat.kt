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

    fun verdict(ctx: Context): Verdict {
        // The live table from api.tosbisim.com when we have one (Api.refreshSupportTable), else the bundled copy.
        val table = runCatching {
            val live = File(ctx.filesDir, "support.json")
            JSONObject(if (live.exists()) live.readText() else ctx.assets.open("support.json").bufferedReader().readText())
        }
            .onFailure { Log.w(TAG, "support.json: $it") }.getOrNull()
        val maker = Build.MANUFACTURER.lowercase()
        val fallbackName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        // Rules first: a US Samsung shares its codename with an unlockable model but can't be rooted.
        table?.optJSONArray("rules")?.let { rules ->
            for (i in 0 until rules.length()) {
                val r = rules.getJSONObject(i)
                val makers = r.optJSONArray("manufacturers")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                val regex = r.optString("model_regex").takeIf { it.isNotEmpty() }?.toRegex()
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
            val list = (0 until phones.length()).map { phones.getJSONObject(it) }
            fun strings(p: JSONObject, k: String) = p.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            fun tierOf(p: JSONObject) = runCatching { Tier.valueOf(p.getString("tier").uppercase()) }.getOrDefault(Tier.UNKNOWN)
            // The exact model number wins over a shared codename (the S20 FE 5G is also "r8q").
            list.firstOrNull { p -> strings(p, "models").any { it.equals(Build.MODEL, ignoreCase = true) } }?.let { p ->
                return Verdict(tierOf(p), p.optString("name", fallbackName), local(p, "note"))
            }
            // Same codename, other model number (another region of the same phone): at most "expected".
            list.firstOrNull { p -> Build.DEVICE in strings(p, "devices") }?.let { p ->
                val t = tierOf(p)
                return Verdict(if (t == Tier.VERIFIED) Tier.EXPECTED else t, "${p.optString("name")} (${Build.MODEL})", local(p, "note"))
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

    fun soc(): String =
        (if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else "")
            .ifEmpty { prop("ro.board.platform") }

    /** Root works for this app (asks Magisk; the user grants it once). Call only on a user action. */
    fun rootWorks(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(20, TimeUnit.SECONDS) && out.lines().lastOrNull() == "0"
    }.getOrDefault(false)

    fun prop(name: String): String = runCatching {
        val p = ProcessBuilder("getprop", name).start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor(2, TimeUnit.SECONDS) }
    }.getOrDefault("")
}

/** Installs the embedded Magisk module (no APK inside: it adopts this installed app) and restarts the phone. */
object Installer {
    private const val TAG = "SimRelayHost"
    private const val ASSET = "module/tosbisim-host-module.zip"

    fun install(ctx: Context, done: (ok: Boolean, log: String) -> Unit) = Thread {
        val r = runCatching {
            val zip = File(ctx.cacheDir, "tosbisim-host-module.zip")
            ctx.assets.open(ASSET).use { i -> zip.outputStream().use { i.copyTo(it) } }
            val p = ProcessBuilder("su", "-c", "magisk --install-module '${zip.absolutePath}'").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val finished = p.waitFor(120, TimeUnit.SECONDS)
            (finished && p.exitValue() == 0) to out
        }.getOrElse { false to "$it" }
        Log.i(TAG, "module install ${if (r.first) "ok" else "FAILED"}:\n${r.second.takeLast(800)}")
        done(r.first, r.second)
    }.start()

    fun reboot() {
        runCatching { ProcessBuilder("su", "-c", "svc power reboot || reboot").start() }
    }
}
