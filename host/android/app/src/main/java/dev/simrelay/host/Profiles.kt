// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject

/**
 * A device profile: how to route call audio on this phone. [source]: "measured" (profiles/<soc>/<model>.json, our own
 * experiments), "candidate" (profiles/candidates/, desk study) or "auto" (built on the phone from its own audio
 * config files, AutoProfile). Whatever the source, audio only flows after the call test passed (VoiceGate).
 */
data class Profile(val id: String, val json: JSONObject, val source: String = "measured") {
    /** SET lines for simrelayd (see host/daemon/simrelayd.c). */
    fun daemonSetLines(): List<String> {
        val cap = json.getJSONObject("capture")
        val injObj = json.getJSONObject("inject")
        val inj = injObj.getJSONObject("route_by_session")
        val mute = json.getJSONObject("mute")
        return listOf(
            "SET capture ${cap.getString("route")}",
            "SET rec_config ${cap.getString("rec_config")}",
            "SET inject.1 ${inj.getString("1")}",
            "SET inject.2 ${inj.getString("2")}",
            "SET mute.tx ${mute.getString("tx")}",
            "SET mute.rx ${mute.getString("rx")}",
            "SET mute.vsid ${mute.getLong("vsid")}",
            "SET mute.ramp ${mute.getInt("ramp_ms")}",
            // PCM device numbers for the M5 audio pump
            "SET capture.pcm ${cap.getInt("pcm")}",
            "SET inject.pcm ${injObj.getInt("pcm")}",
            "SET inject.rate ${injObj.getInt("rate")}",
            "SET inject.channels ${injObj.getInt("channels")}",
        ) + silenceLines()
    }

    /** Controls held for the daemon's lifetime so the host never makes a sound (e.g. amplifier mutes). */
    private fun silenceLines(): List<String> {
        val s = json.optJSONObject("silence") ?: return emptyList()
        return s.keys().asSequence().map { "SET silence $it=${s.getInt(it)}" }.toList()
    }

    /** Every control name the routes use (to confirm them on the live card). */
    fun controlNames(): List<String> {
        val cap = json.getJSONObject("capture")
        val inj = json.getJSONObject("inject").getJSONObject("route_by_session")
        val mute = json.getJSONObject("mute")
        return listOf(cap.getString("route"), cap.getString("rec_config"), inj.getString("1"), inj.getString("2"),
            mute.getString("tx"), mute.getString("rx"))
    }
}

object Profiles {
    private const val TAG = "SimRelayHost"

    /** Manufacturer profile (profiles/oem/<maker>.json, match.manufacturer): box-mode settings keys. */
    fun forManufacturer(ctx: Context): JSONObject? {
        val maker = Build.MANUFACTURER.lowercase()
        for (file in ctx.assets.list("oem").orEmpty().filter { it.endsWith(".json") }) {
            val json = runCatching { JSONObject(ctx.assets.open("oem/$file").bufferedReader().readText()) }
                .onFailure { Log.w(TAG, "bad profile oem/$file: $it") }.getOrNull() ?: continue
            val makers = json.optJSONObject("match")?.optJSONArray("manufacturer") ?: continue
            if ((0 until makers.length()).any { makers.getString(it).lowercase() == maker }) return json
        }
        return null
    }

    /** First profile whose match.devices contains Build.DEVICE, or null (= Basic mode only). */
    fun forThisDevice(ctx: Context): Profile? {
        val assets = ctx.assets
        for (dir in assets.list("").orEmpty()) {
            for (file in assets.list(dir).orEmpty().filter { it.endsWith(".json") }) {
                val json = runCatching {
                    JSONObject(assets.open("$dir/$file").bufferedReader().readText())
                }.onFailure { Log.w(TAG, "bad profile $dir/$file: $it") }.getOrNull() ?: continue
                val devices = json.optJSONObject("match")?.optJSONArray("devices") ?: continue
                if ((0 until devices.length()).any { devices.getString(it) == Build.DEVICE })
                    return Profile(json.getString("id"), json, if (dir == "candidates") "candidate" else "measured")
            }
        }
        return null
    }
}

/**
 * Qualcomm "legacy" audio (msm-pcm-routing, the S20 FE's stack) on a phone without a profile: build one from the
 * phone's own vendor files. audio_platform_info*.xml gives the in-call music PCM id (USECASE_INCALL_MUSIC_UPLINK),
 * mixer_paths*.xml the routing control of the incall-music-uplink(2) paths; capture is MultiMedia1 (PCM 0) with
 * VOC_REC_DL as on every legacy platform. Each control must exist on the live card ([has], simrelayd HAS).
 * No host-silence controls are known for an unmeasured phone: the call test asks the user about host sound.
 */
object AutoProfile {
    private const val TAG = "SimRelayHost"
    private val DIRS = listOf("/vendor/etc", "/odm/etc", "/vendor/etc/audio")

    fun build(has: (String) -> Boolean): Profile? = runCatching {
        val files = DIRS.flatMap { d -> java.io.File(d).listFiles().orEmpty().toList() }
        val pcmIds = files.filter { it.name.startsWith("audio_platform_info") && it.name.endsWith(".xml") }
            .mapNotNull { f -> Regex("""name="USECASE_INCALL_MUSIC_UPLINK"[^>]*id="(\d+)"""").find(f.readText())?.groupValues?.get(1)?.toInt() }
            .distinct()
        val mixers = files.filter { it.name.startsWith("mixer_paths") && it.name.endsWith(".xml") }.map { it.readText() }
        // AOSP names the path incall-music-uplink, Samsung incall_music_uplink.
        fun pathCtl(suffix: String): String? = mixers.firstNotNullOfOrNull { x ->
            Regex("""<path name="incall[-_]music[-_]uplink$suffix">\s*<ctl name="([^"]+)" value="1"\s*/>""").find(x)?.groupValues?.get(1)
        }
        val pcm = pcmIds.singleOrNull() ?: run { Log.i(TAG, "auto profile: incall music PCM ids $pcmIds"); return null }
        val inj1 = pathCtl("") ?: run { Log.i(TAG, "auto profile: no incall music uplink path"); return null }
        val inj2 = pathCtl("2") ?: inj1.replace("Incall_Music ", "Incall_Music_2 ")
        val names = listOf("MultiMedia1 Mixer VOC_REC_DL", "Voc Rec Config", inj1, inj2, "Voice Tx Device Mute", "Voice Rx Device Mute")
        val missing = names.filterNot(has)
        if (missing.isNotEmpty()) { Log.i(TAG, "auto profile: missing controls $missing"); return null }
        val json = JSONObject("""{"id":"auto/${Build.DEVICE}","router":"qcom-legacy","card":0,
            "capture":{"pcm":0,"route":"MultiMedia1 Mixer VOC_REC_DL","rec_config":"Voc Rec Config","rate":16000,"channels":1},
            "inject":{"pcm":$pcm,"rate":48000,"channels":2,"route_by_session":{"1":${JSONObject.quote(inj1)},"2":${JSONObject.quote(inj2)}}},
            "mute":{"tx":"Voice Tx Device Mute","rx":"Voice Rx Device Mute","vsid":4294967295,"ramp_ms":20}}""")
        Log.i(TAG, "auto profile built: inject PCM $pcm, routes '$inj1' / '$inj2'")
        Profile(json.getString("id"), json, "auto")
    }.onFailure { Log.w(TAG, "auto profile: $it") }.getOrNull()
}
