// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * The phone's sound hardware as its kernel describes it, sent once per firmware as an anonymous "dump" report: every
 * mixer control's name, type, value count and range or enum items (the daemon's read-only INVENTORY: no control is
 * read or written), the PCM streams and the sound node of the device tree. This is what lets a family's call route be
 * built and proven from people's own phones rather than a donor phone per model (docs/10-proving-voice-paths.md).
 *
 * A large card (5306 controls on the S20 FE: 33 KB gzipped) is split into parts that each decode on their own, so
 * every report stays under the server's 64 KB payload limit whatever the phone.
 */
object Inventory {
    private const val TAG = "SimRelayHost"
    private const val FORMAT = 1                     // bump when the content changes: every phone sends it again
    private const val FILE = "/data/adb/tosbisim/inventory.txt"
    private const val PART_BYTES = 40_000            // gzip+base64 per report; the rest of the report is ~3 KB
    private val exec = Bg.single("inventory")
    private lateinit var app: Context

    fun init(ctx: Context) { app = ctx.applicationContext }

    private fun key() = "$FORMAT:${Build.FINGERPRINT}"
    private fun prefs() = app.getSharedPreferences("inventory", Context.MODE_PRIVATE)

    /** Not sent yet for this firmware (the daemon then writes its file and [send] reports it). */
    fun due(): Boolean = ::app.isInitialized && Diag.enabled && prefs().getString("sent", null) != key()

    /** The daemon wrote [FILE] ([reply]: its INVENTORY answer); read, pack and queue it off the daemon thread. */
    fun send(reply: String) = exec.execute {
        if (!due()) return@execute
        val lines = Root.sh("cat $FILE", 20_000).takeIf { it.ok }?.out?.lines()?.filter { it.isNotBlank() }.orEmpty()
        if (lines.isEmpty()) { Log.w(TAG, "inventory: nothing read"); return@execute }
        val parts = split(lines)
        val extra = JSONObject()
            .put("daemon", reply)
            .put("cards", read("cat /proc/asound/cards"))
            .put("pcm", read("cat /proc/asound/pcm"))
            .put("dt_sound", dtSound())
        parts.forEachIndexed { i, gz ->
            val detail = JSONObject().put("what", "hw_inventory").put("format", FORMAT)
                .put("part", i + 1).put("parts", parts.size).put("controls", lines.size).put("controls_gz", gz)
            if (i == 0) extra.keys().forEach { detail.put(it, extra.get(it)) }
            Diag.event("hw_inventory", detail, withLog = false, kind = "dump")
        }
        prefs().edit().putString("sent", key()).apply()
        Log.i(TAG, "inventory: ${lines.size} controls queued in ${parts.size} report(s)")
    }

    /** Lines gzipped in groups whose base64 stays under [PART_BYTES] (text compresses ~8x: start from that, then check). */
    private fun split(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        var from = 0
        while (from < lines.size) {
            var to = lines.size
            var gz = pack(lines.subList(from, to))
            while (gz.length > PART_BYTES && to - from > 1) {
                to = from + maxOf(1, ((to - from).toLong() * PART_BYTES / gz.length * 9 / 10).toInt())
                gz = pack(lines.subList(from, to))
            }
            out.add(gz)
            from = to
        }
        return out
    }

    private fun pack(lines: List<String>): String {
        val b = ByteArrayOutputStream()
        GZIPOutputStream(b).use { it.write(lines.joinToString("\n", postfix = "\n").toByteArray()) }
        return Base64.encodeToString(b.toByteArray(), Base64.NO_WRAP)
    }

    private fun read(cmd: String) = Root.sh(cmd, 10_000).out.take(8000)

    /**
     * The device tree's sound node(s), one "path=text" per property (Exynos names its mixer file here, others the card,
     * codec and routing). Toybox has no character classes, so the shell only turns NULs into spaces; values that
     * aren't text (phandles, cell arrays) are marked "(binary)" here.
     */
    private fun dtSound(): String {
        val out = Root.sh("for d in \$(find /proc/device-tree/ -maxdepth 4 -type d -name 'sound*' 2>/dev/null | head -4); do " +
            "for f in \$d/*; do [ -f \"\$f\" ] && [ \$(wc -c < \"\$f\") -le 1024 ] && { echo \"@@ \$f\"; tr '\\0' ' ' < \"\$f\"; echo; }; " +
            "done; done", 15_000).out
        return out.split("\n@@ ", "@@ ").filter { it.isNotBlank() }.joinToString("\n") { block ->
            val path = block.substringBefore('\n')
            val value = block.substringAfter('\n', "").trim()
            "$path=" + if (value.all { it in ' '..'~' || it == '\n' }) value.replace('\n', ' ') else "(binary)"
        }.take(12_000)
    }
}
