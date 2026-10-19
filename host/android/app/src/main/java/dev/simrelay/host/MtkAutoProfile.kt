// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.os.Build
import android.util.Log
import org.json.JSONObject

/**
 * MediaTek AFE (the Redmi Note 8 Pro's stack; per the AndroidDumps corpus also newer Dimensity chips) on a phone without
 * a profile, from the vendor's own audio_device.xml: the modem paths PLAYBACKn_TO_MD1 (our audio into the call) and
 * MD1_TO_CAPTURE* (the caller's audio), their controls switched on for the call; MD1_TO_ADDA_DL's controls (caller to
 * the DAC, host mic into the call) and any DL->DAC link of our playback channel held at 0 (host silence). PCM numbers
 * from /proc/asound/pcm by name (PLAYBACK2 -> Playback_2, CAPTURE_MONO_1 -> Capture_Mono_1). Rates: simrelayd negotiates.
 * Each control must exist on the live card ([has], simrelayd HAS); the call test still decides full voice.
 */
object MtkAutoProfile {
    private const val TAG = "SimRelayHost"

    fun build(xml: String, has: (String) -> Boolean): Profile? {
        fun path(name: String): List<Pair<String, String>> =
            Regex("<path name=\"${Regex.escape(name)}\"[^>]*>(.*?)</path>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
                ?.let { body -> Regex("<kctl name=\"([^\"]+)\" value=\"([^\"]+)\"").findAll(body).map { it.groupValues[1] to it.groupValues[2] }.toList() }
                .orEmpty()
        val toMd = Regex("<path name=\"(PLAYBACK(\\d+)_TO_MD1)\"").findAll(xml).map { it.groupValues[1] to it.groupValues[2] }.toList()
            .sortedBy { if (it.second == "2") 0 else 1 }.firstOrNull()
            ?: run { Log.i(TAG, "auto profile (MediaTek): no PLAYBACKn_TO_MD1 path"); return null }
        val fromMd = Regex("<path name=\"(MD1_TO_CAPTURE[A-Z_0-9]*)\"").findAll(xml).map { it.groupValues[1] }.toList()
            .sortedBy { when (it) { "MD1_TO_CAPTURE_MONO_1" -> 0; "MD1_TO_CAPTURE2" -> 1; else -> 2 } }.firstOrNull()
            ?: run { Log.i(TAG, "auto profile (MediaTek): no MD1_TO_CAPTURE path"); return null }
        val pcmNames = AsoundPcm.byName()
        val injName = "Playback_${toMd.second}"
        val capName = feName(fromMd.removePrefix("MD1_TO_"))
        val injPcm = pcmNames[injName]
        val capPcm = pcmNames[capName]
        if (injPcm == null || capPcm == null) { Log.i(TAG, "auto profile (MediaTek): PCM $injName=$injPcm, $capName=$capPcm"); return null }
        val on = (path(toMd.first) + path(fromMd)).filter { it.second == "1" || it.second.equals("On", true) }.map { it.first }
        val dl = "DL${toMd.second}"
        // Every DAC link of the modem's capture side (both channels) and of our playback channel, if the card has it
        val mdPcm = on.firstNotNullOfOrNull { Regex("PCM_(\\d+)_").find(it)?.groupValues?.get(1) }
        val off = (path("MD1_TO_ADDA_DL").map { it.first } +
            (1..4).flatMap { ch -> (1..2).flatMap { s -> listOfNotNull("ADDA_DL_CH$ch ${dl}_CH$s",
                mdPcm?.let { "ADDA_DL_CH$ch PCM_${it}_CAP_CH$s" }) } }.filter(has)).distinct()
        val missing = (on + off).distinct().filterNot(has)
        if (on.isEmpty() || missing.isNotEmpty()) { Log.i(TAG, "auto profile (MediaTek): on=$on missing=$missing"); return null }
        val controls = JSONObject().apply { off.forEach { put(it, 0) }; on.forEach { put(it, 1) } }
        val json = JSONObject().put("id", "auto/${Build.DEVICE}").put("router", "mtk-afe").put("card", 0)
            .put("capture", JSONObject().put("pcm", capPcm).put("rate", 16000).put("channels", if ("Mono" in capName) 1 else 2)
                .put("route", "-").put("rec_config", "-"))
            .put("inject", JSONObject().put("pcm", injPcm).put("rate", 48000).put("channels", 2).put("route_by_session", JSONObject()))
            .put("session_by_slot", JSONObject().put("0", 1).put("1", 1))
            .put("call_controls", controls)
        Log.i(TAG, "auto profile built (MediaTek): ${toMd.first} -> $injName (pcm $injPcm), $fromMd -> $capName (pcm $capPcm), " +
            "${on.size} on, ${off.size} held off")
        return Profile(json.getString("id"), json, "auto")
    }

    /** CAPTURE_MONO_1 -> Capture_Mono_1, CAPTURE2 -> Capture_2 (MediaTek's front-end PCM names). */
    fun feName(s: String): String = s.split('_').joinToString("_") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
        .replace(Regex("^Capture(\\d)"), "Capture_$1")

}

/** The sound card's PCM devices by name, from /proc/asound/pcm (needs root: apps can't read /proc/asound). Profiles
 *  name PCMs where the number varies per board (MediaTek "Capture_Mono_1", Qualcomm "MultiMedia9"). */
object AsoundPcm {
    fun byName(): Map<String, Int> = runCatching {
        ProcessBuilder("su", "-c", "cat /proc/asound/pcm").start().inputStream.bufferedReader().readText()
    }.getOrDefault("").lines().mapNotNull { Regex("^(\\d+)-(\\d+): (\\S+)").find(it)?.groupValues }
        .associate { it[3] to it[2].toInt() }
}
