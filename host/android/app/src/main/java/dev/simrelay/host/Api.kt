// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * api.tosbisim.com: opt-in compatibility reports and the live support table. Anonymous: a random id per install (not
 * the pairing id; hashed on arrival), no account. Calls run off the main thread; [done] gets (ok, detail).
 */
object Api {
    private const val TAG = "SimRelayHost"
    private const val BASE = "https://api.tosbisim.com/api/v1"

    fun installId(ctx: Context): String {
        val p = ctx.getSharedPreferences("api", Context.MODE_PRIVATE)
        return p.getString("install_id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("install_id", it).apply() }
    }

    /** Compatibility report: this phone, its support verdict, bootloader/root/Bluetooth facts and the audio markers. */
    fun sendCompatReport(ctx: Context, done: (Boolean, String) -> Unit) = Thread {
        val v = Compat.verdict(ctx)
        val b = Compat.bootloader()
        val p = Daemon.profile()
        val body = JSONObject()
            .put("kind", "precheck").put("app", "host").put("app_version", BuildConfigInfo.version(ctx))
            .put("install_id", installId(ctx))
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("device", Build.DEVICE)
            .put("soc", Compat.soc()).put("sdk", Build.VERSION.SDK_INT).put("firmware", Build.FINGERPRINT)
            .put("tier", v.tier.name.lowercase())
            .put("profile_id", p?.id ?: JSONObject.NULL).put("profile_source", p?.source ?: JSONObject.NULL)
            .put("payload", JSONObject()
                .put("android", Build.VERSION.RELEASE)
                .put("host_installed", Compat.isHostInstalled(ctx))
                .put("bootloader", JSONObject().put("unlock_supported", b.unlockSupported).put("unlock_switch_on", b.unlockSwitchOn).put("unlocked", b.unlocked))
                .put("magisk", Compat.magiskInstalled(ctx))
                .put("ble_advertising", Compat.bleAdvertising(ctx))
                .put("voice_passed", Compat.isHostInstalled(ctx) && VoiceGate.passed)
                .put("audio", Discovery.audio()))
        val r = post("/reports", body)
        Log.i(TAG, "compat report: ${r.second}")
        done(r.first, r.second)
    }.start()

    /** Live support table (ETag-cached in filesDir); Compat falls back to the bundled copy when this has none. */
    fun refreshSupportTable(ctx: Context) = Thread {
        runCatching {
            val f = File(ctx.filesDir, "support.json")
            val etag = ctx.getSharedPreferences("api", Context.MODE_PRIVATE).getString("support_etag", null)
            val c = (URL("$BASE/support").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                if (f.exists()) etag?.let { setRequestProperty("If-None-Match", it) }
            }
            when (c.responseCode) {
                200 -> {
                    // Whole file or nothing: a reader (the setup screen) must never see half a table
                    val text = c.inputStream.bufferedReader().readText()
                    // Only a table that evaluates replaces ours: every status reads it (a bad one must not reach phones)
                    if (!Compat.validTable(text)) {
                        Diag.event("support_table_rejected", org.json.JSONObject().put("what", "invalid").put("bytes", text.length), withLog = false)
                        return@runCatching
                    }
                    val tmp = File(ctx.filesDir, "support.json.tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(f)) { tmp.delete(); error("rename failed") }
                    ctx.getSharedPreferences("api", Context.MODE_PRIVATE).edit().putString("support_etag", c.getHeaderField("ETag")).apply()
                    Log.i(TAG, "support table updated (${c.getHeaderField("ETag")})")
                }
                304 -> Unit
                else -> Log.w(TAG, "support table: HTTP ${c.responseCode}")
            }
        }.onFailure { Log.i(TAG, "support table not refreshed: $it") }
    }.start()

    /** POST and parse the JSON answer (blocking; call off the main thread). null on any failure. */
    fun postJson(path: String, body: JSONObject): JSONObject? = postStatus(path, body).second

    /**
     * POST: (HTTP status, the parsed JSON answer of a 2xx or null). Status -1: no answer at all (offline, timeout). A
     * caller with a queue needs the status: a request the server refuses (4xx) never succeeds however often it is
     * sent again (audit, 20 Oct 2026).
     */
    fun postStatus(path: String, body: JSONObject): Pair<Int, JSONObject?> = try {
        val c = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) { Log.w(TAG, "POST $path: HTTP $code"); code to null }
        else code to runCatching { JSONObject(c.inputStream.bufferedReader().readText()) }.getOrNull()
    } catch (e: Exception) { Log.w(TAG, "POST $path: $e"); -1 to null }

    fun getJson(path: String): JSONObject? = runCatching {
        val c = (URL(BASE + path).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
        if (c.responseCode !in 200..299) return null
        JSONObject(c.inputStream.bufferedReader().readText())
    }.onFailure { Log.w(TAG, "GET $path: $it") }.getOrNull()

    private fun post(path: String, body: JSONObject): Pair<Boolean, String> = runCatching {
        val c = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10000; readTimeout = 10000
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        (code in 200..299) to "HTTP $code"
    }.getOrElse { false to it.toString() }
}
