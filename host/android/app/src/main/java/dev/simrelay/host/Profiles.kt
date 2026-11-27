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
    /**
     * SET lines for simrelayd (see host/daemon/simrelayd.c). Every field is optional: a platform that doesn't use
     * Qualcomm's record config or device mutes leaves them out ("-" clears the daemon's Qualcomm defaults), and
     * "call_controls" describes any other platform's call routing as data: {"<control>": value | [values]}.
     */
    fun daemonSetLines(): List<String> {
        val cap = json.optJSONObject("capture") ?: JSONObject()
        val inj = json.optJSONObject("inject") ?: JSONObject()
        val routes = inj.optJSONObject("route_by_session")
        val mute = json.optJSONObject("mute")
        val l = mutableListOf<String>()
        fun name(k: String, v: String?) { l += "SET $k ${v?.takeIf { it.isNotEmpty() } ?: "-"}" }
        name("capture", cap.optString("route"))
        name("rec_config", cap.optString("rec_config"))
        name("inject.1", routes?.optString("1"))
        // optString gives "" (not null) for a missing key: a profile with one route uses it on both SIMs' sessions
        name("inject.2", routes?.optString("2")?.takeIf { it.isNotEmpty() } ?: routes?.optString("1"))
        name("mute.tx", mute?.optString("tx"))
        name("mute.rx", mute?.optString("rx"))
        mute?.let { l += "SET mute.vsid ${it.optLong("vsid", 4294967295L)}"; l += "SET mute.ramp ${it.optInt("ramp_ms", 20)}" }
        l += "SET capture.pcm ${cap.optInt("pcm", 0)}"
        l += "SET capture.rate ${cap.optInt("rate", 16000)}"
        l += "SET capture.channels ${cap.optInt("channels", 1)}"
        l += "SET inject.pcm ${inj.optInt("pcm", 0)}"
        l += "SET inject.rate ${inj.optInt("rate", 48000)}"
        l += "SET inject.channels ${inj.optInt("channels", 2)}"
        l += "SET call.clear 1"
        json.optJSONObject("call_controls")?.let { cc ->
            for (k in cc.keys()) {
                val v = cc.get(k)
                val values = if (v is org.json.JSONArray) (0 until v.length()).joinToString(",") { v.getLong(it).toString() } else v.toString()
                l += "SET call $k=$values"
            }
        }
        return l + silenceLines()
    }

    /** Every mixer control this profile drives ("Voc Rec Config" is optional: older kernels lack it, the daemon skips it). */
    fun controlNames(): List<String> {
        val routes = json.optJSONObject("inject")?.optJSONObject("route_by_session")
        val mute = json.optJSONObject("mute")
        fun keys(k: String) = json.optJSONObject(k)?.keys()?.asSequence()?.toList().orEmpty()
        return (listOfNotNull(json.optJSONObject("capture")?.optString("route"), mute?.optString("tx"), mute?.optString("rx")) +
            routes?.keys()?.asSequence()?.map { routes.optString(it) }?.toList().orEmpty() + keys("call_controls") + keys("silence"))
            .filter { it.isNotEmpty() && it != "-" }.distinct()
    }

    /**
     * This profile on this phone's sound card: each PCM looked up by its "pcm_name" (the measured phone's number can differ
     * on a sibling) and every control it drives checked with [has]. Null and why when it doesn't fit: the daemon skips a
     * missing control, so a sibling's card would route half a call, or open the wrong PCM. [pcms] empty (no root to read
     * /proc/asound): the numbers are kept.
     */
    fun onThisCard(has: (String) -> Boolean, pcms: Map<String, Int>): Pair<Profile?, String> {
        val j = JSONObject(json.toString())
        val why = mutableListOf<String>()
        for (k in listOf("capture", "inject")) {
            val o = j.optJSONObject(k) ?: continue
            val name = o.optString("pcm_name")
            if (name.isEmpty() || pcms.isEmpty()) continue
            pcms[name]?.let { o.put("pcm", it) } ?: run { why += "no PCM $name" }
        }
        controlNames().filterNot(has).takeIf { it.isNotEmpty() }?.let { why += "missing controls $it" }
        return (if (why.isEmpty()) Profile(id, j, source) else null) to why.joinToString("; ")
    }

    /** Voice session (1/2) for a SIM slot: the profile's map, else Qualcomm's VoiceMMode numbering (slot + 1). */
    fun sessionForSlot(slot: Int): Int = json.optJSONObject("session_by_slot")?.optInt(slot.toString(), slot + 1) ?: (slot + 1)

    /** Controls held for the daemon's lifetime so the host never makes a sound (e.g. amplifier mutes). */
    private fun silenceLines(): List<String> {
        val s = json.optJSONObject("silence") ?: return emptyList()
        return s.keys().asSequence().map { "SET silence $it=${s.getInt(it)}" }.toList()
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

    /**
     * The profile for this phone, or null (= Basic mode only): one whose match.devices lists this exact phone first,
     * else one for its family (match.platforms has this chipset platform AND match.manufacturers its maker), so one
     * measured phone covers its siblings. Either way, call audio only flows after this phone's own call test.
     */
    fun forThisDevice(ctx: Context): Profile? {
        val assets = ctx.assets
        val all = assets.list("").orEmpty().filter { it != "oem" }.flatMap { dir ->
            assets.list(dir).orEmpty().filter { it.endsWith(".json") }.mapNotNull { file ->
                runCatching { dir to JSONObject(assets.open("$dir/$file").bufferedReader().readText()) }
                    .onFailure { Log.w(TAG, "bad profile $dir/$file: $it") }.getOrNull()
            }
        }.filter { (_, j) -> j.has("id") && j.optJSONObject("match") != null }
        fun list(j: JSONObject, k: String) = j.optJSONObject("match")?.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it).lowercase() } }.orEmpty()
        all.firstOrNull { (_, j) -> Build.DEVICE.lowercase() in list(j, "devices") }?.let { (dir, j) ->
            return Profile(j.getString("id"), j, if (dir == "candidates") "candidate" else "measured")
        }
        val platform = platformNames()
        all.firstOrNull { (_, j) -> list(j, "platforms").any { it in platform } && Build.MANUFACTURER.lowercase() in list(j, "manufacturers") }
            ?.let { (_, j) -> Log.i(TAG, "profile ${j.getString("id")} matched by family ($platform)"); return Profile(j.getString("id"), j, "family") }
        return null
    }

    /** This phone's chipset platform names: ro.board.platform (e.g. kona), Build.BOARD and, on Android 12+, SOC_MODEL. */
    fun platformNames(): Set<String> {
        val prop = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "ro.board.platform") as String
        }.getOrDefault("")
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""
        return setOf(prop, Build.BOARD, Build.HARDWARE, soc).filter { it.isNotBlank() && it != "unknown" }.map { it.lowercase() }.toSet()
    }
}

/**
 * Qualcomm "legacy" audio (msm-pcm-routing, the S20 FE's stack) on a phone without a profile: build one from the
 * phone's own vendor files. audio_platform_info*.xml gives the in-call music PCM id (USECASE_INCALL_MUSIC_UPLINK),
 * mixer_paths*.xml the routing control of the incall-music-uplink(2) paths; capture is MultiMedia1 (PCM 0) with
 * VOC_REC_DL as on every legacy platform. Each control must exist on the live card ([has], simrelayd HAS).
 * No host-silence controls are known for an unmeasured phone: the call test asks the user about host sound.
 */
/**
 * The vendor audio files and what the Qualcomm auto profile reads from them: one parser for [AutoProfile] and the
 * compatibility report ([Discovery]), so a report shows exactly what the host would build.
 */
object VendorAudio {
    private val DIRS = listOf("/vendor/etc", "/odm/etc", "/vendor/etc/audio")
    private val SKU_PARENTS = listOf("/vendor/etc/audio", "/odm/etc/audio")

    /**
     * Qualcomm's per-SKU folders, which the HAL reads first on newer builds (Xiaomi 11T Pro, Android 13: mixer_paths
     * and audio_platform_info only in /vendor/etc/audio/sku_lahaina; 2 Nov 2026). This chip's only: one vendor image
     * carries several SoCs' (sku_lahaina, sku_shima, sku_yupik).
     */
    fun skuDirs(): List<java.io.File> {
        val plat = Compat.prop("ro.board.platform").lowercase()
        if (plat.isEmpty()) return emptyList()
        return SKU_PARENTS.flatMap { d -> java.io.File(d).listFiles().orEmpty().filter { it.isDirectory && (it.name == "sku_$plat" || it.name.startsWith("sku_${plat}_")) } }
    }

    /** This chip's SKU folder first (what the HAL uses), then the classic places. */
    fun files(): List<java.io.File> = (skuDirs() + DIRS.map { java.io.File(it) }).flatMap { d -> d.listFiles().orEmpty().filter { it.isFile } }
    private fun text(f: java.io.File) = runCatching { f.readText() }.getOrDefault("")
    fun platformInfos(files: List<java.io.File>) = files.filter { it.name.startsWith("audio_platform_info") && it.name.endsWith(".xml") }
    fun mixers(files: List<java.io.File>) = files.filter { it.name.startsWith("mixer_paths") && it.name.endsWith(".xml") }.map(::text)

    /** USECASE_INCALL_MUSIC_UPLINK PCM ids the vendor lists (one = usable; none or several = find the front end by name). */
    fun incallMusicPcmIds(files: List<java.io.File>): List<Int> = platformInfos(files)
        .mapNotNull { f -> Regex("""name="USECASE_INCALL_MUSIC_UPLINK"[^>]*id="(\d+)"""").find(text(f))?.groupValues?.get(1)?.toInt() }
        .distinct()

    /**
     * The control that routes in-call music to the uplink for voice session 1 ([two] false) or 2. The
     * incall-music-uplink(2) path (AOSP dashes, Samsung underscores); older platforms (Snapdragon 660/710/845, Android
     * 8-9) have no such path and keep the control in "low-latency-playback incall-music" etc. (138 of 862 legacy phones
     * in the corpus, 19 Oct): then the most used Incall_Music front end, never MultiMedia1 (our capture front end; its
     * playback side carries system sounds).
     */
    fun incallRoute(mixers: List<String>, two: Boolean): String? {
        val suffix = if (two) "2" else ""
        mixers.firstNotNullOfOrNull { x ->
            Regex("""<path name="incall[-_]music[-_]uplink$suffix">\s*<ctl name="([^"]+)" value="1"\s*/>""").find(x)?.groupValues?.get(1)
        }?.let { return it }
        return mixers.flatMap { x ->
            Regex("""<ctl name="(Incall_Music${if (two) "_2" else ""} Audio Mixer (MultiMedia\d+))" value="1"""").findAll(x).map { it.groupValues }.toList()
        }.filter { it[2] != "MultiMedia1" }.groupingBy { it[1] }.eachCount().maxByOrNull { it.value }?.key
    }

    /**
     * The in-call music route when the vendor's files name none: the sound card's own. A custom ROM's audio files can
     * leave the path out while its kernel still has the control and the stream: a Mi 6 on LineageOS 22 (the only way
     * that phone gets an Android this app runs on) stayed in Basic mode with no call test to try (27 Nov 2026), a
     * Galaxy S5 on a custom Android 13 likewise.
     *
     * The front end the vendor lists a PCM for when it lists one ([pcmIds]), then MultiMedia2 (what Qualcomm's HAL
     * uses when nothing says otherwise), then MultiMedia9 (the most named one in stock files); never MultiMedia1 (see
     * [incallRoute]). It must have a playback stream by that name ([playback]: name to PCM) and the control ([has]).
     * Pure (AutoProfileTest).
     */
    fun kernelRoute(pcmIds: List<Int>, has: (String) -> Boolean, playback: Map<String, Int>): String? {
        val listed = pcmIds.singleOrNull()?.let { id -> playback.entries.firstOrNull { it.value == id }?.key }
        return (listOfNotNull(listed) + listOf("MultiMedia2", "MultiMedia9")).distinct()
            .filter { it != "MultiMedia1" && it in playback }.map { "Incall_Music Audio Mixer $it" }.firstOrNull(has)
    }
}

object AutoProfile {
    private const val TAG = "SimRelayHost"

    /** Why the last [build] found nothing (or what it built), for the auto_profile report; empty when no builder said. */
    @Volatile var why = ""; private set

    /** [ask]: simrelayd's reply to "HAS <control>" (null when the daemon didn't answer). */
    fun build(ask: (String) -> String?): Profile? = runCatching {
        why = ""
        val has = { n: String -> ask(n)?.startsWith("OK") == true }
        val files = VendorAudio.files()
        files.firstOrNull { it.name == "audio_device.xml" }?.let { return@runCatching MtkAutoProfile.build(it.readText(), has) }
        // Samsung's pre-ABOX Exynos (custom ROMs only): known by its kernel controls, whatever the ROM's audio files say
        val (samsung, said) = SamsungMixerAutoProfile.build(Build.DEVICE, ask, AsoundPcm::byName)
        if (said.isNotEmpty()) {
            why = said
            Log.i(TAG, "auto profile: $said")
            return@runCatching samsung
        }
        val (p, found) = qcom(Build.DEVICE, VendorAudio.mixers(files), VendorAudio.incallMusicPcmIds(files), has, AsoundPcm::byName, AsoundPcm::playbackByName)
        why = found
        Log.i(TAG, "auto profile${if (p != null) " built" else ""}: $found")
        p
    }.onFailure { Log.w(TAG, "auto profile: $it") }.getOrNull()

    /**
     * Qualcomm's legacy voice path: the profile, or null, and what to say about it. The in-call music route comes
     * from the vendor's mixer paths, else from the sound card itself ([VendorAudio.kernelRoute]); either way the call
     * test decides whether the phone gets call audio. [pcms]: every PCM by name, [playback]: those with a playback
     * stream. Pure (AutoProfileTest).
     */
    fun qcom(device: String, mixers: List<String>, pcmIds: List<Int>, has: (String) -> Boolean,
             pcms: () -> Map<String, Int>, playback: () -> Map<String, Int>): Pair<Profile?, String> {
        val vendor = VendorAudio.incallRoute(mixers, false)
        val inj1 = vendor ?: VendorAudio.kernelRoute(pcmIds, has, playback())
            ?: return null to "no incall music control in the vendor paths, and none on the sound card with a playback stream"
        // The PCM: the platform-info id when the vendor lists one, else by name. The route names its front end
        // ("Incall_Music Audio Mixer MultiMedia9" -> PCM "MultiMedia9"): 9 of 29 legacy phones in the AndroidDumps
        // corpus list no id (the HAL then uses its built-in default), 19 Oct. A route from the card is always by name.
        val fe = Regex("""Audio Mixer (MultiMedia\d+)""").find(inj1)?.groupValues?.get(1)
        val pcm = (if (vendor != null) pcmIds.singleOrNull() else null) ?: fe?.let { pcms()[it] }
            ?: return null to "incall music PCM ids $pcmIds, front end $fe not found by name"
        val inj2 = (if (vendor != null) VendorAudio.incallRoute(mixers, true) else null) ?: inj1.replace("Incall_Music ", "Incall_Music_2 ")
        val names = listOf("MultiMedia1 Mixer VOC_REC_DL", inj1, inj2, "Voice Tx Device Mute", "Voice Rx Device Mute")
        val missing = names.filterNot(has)
        if (missing.isNotEmpty()) return null to "missing controls $missing"
        // The record channel count (1 = mono, 2 = DL+UL): only newer msm-pcm-voice kernels have it; older ones
        // (msm8953, sdm660, msm8996, sdm710: 14 of 34 sampled kernels, tools/corpus/out/kernels.md) always record mono.
        val recConfig = "Voc Rec Config".takeIf(has).orEmpty()
        val json = JSONObject("""{"id":"auto/$device","router":"qcom-legacy","card":0,
            "capture":{"pcm":0,"route":"MultiMedia1 Mixer VOC_REC_DL","rec_config":"$recConfig","rate":16000,"channels":1},
            "inject":{"pcm":$pcm,"rate":48000,"channels":2,"route_by_session":{"1":${JSONObject.quote(inj1)},"2":${JSONObject.quote(inj2)}}},
            "mute":{"tx":"Voice Tx Device Mute","rx":"Voice Rx Device Mute","vsid":4294967295,"ramp_ms":20}}""")
        if (vendor == null) json.put("route_from", "sound card")      // for the report: the vendor's files named none
        return Profile(json.getString("id"), json, "auto") to
            "inject PCM $pcm, routes '$inj1' / '$inj2'" + if (vendor == null) " (from the sound card: the vendor paths name none)" else ""
    }
}

/**
 * Device discovery for compatibility reports, readable without root: what the vendor audio files say about the in-call
 * paths (the same markers AutoProfile uses), the vendor in-call music switch and the sound card. Lets us see which
 * phones look supportable before anyone roots them.
 */
object Discovery {
    fun audio(): org.json.JSONObject = runCatching {
        val files = VendorAudio.files()
        val o = org.json.JSONObject()
        // What AutoProfile itself would pick (same parser)
        val mixers = VendorAudio.mixers(files)
        o.put("auto_inject_route", VendorAudio.incallRoute(mixers, false) ?: "")
        o.put("auto_inject_route2", VendorAudio.incallRoute(mixers, true) ?: "")
        o.put("auto_incall_pcm_ids", org.json.JSONArray(VendorAudio.incallMusicPcmIds(files)))
        val ids = org.json.JSONObject()
        VendorAudio.platformInfos(files).forEach { f ->
            val t = runCatching { f.readText() }.getOrDefault("")
            val m = Regex("""name="(USECASE_INCALL_MUSIC_UPLINK2?|USECASE_INCALL_REC_[A-Z_]+|USECASE_VOICE_CALL)"[^>]*id="(\d+)"""").findAll(t)
            val one = org.json.JSONObject()
            m.forEach { one.put(it.groupValues[1], it.groupValues[2].toInt()) }
            if (one.length() > 0) ids.put(f.name, one)
        }
        o.put("platform_info_pcm_ids", ids)
        val paths = org.json.JSONObject()
        files.filter { it.name.startsWith("mixer_paths") && it.name.endsWith(".xml") }.forEach { f ->
            val t = runCatching { f.readText() }.getOrDefault("")
            Regex("""<path name="(incall[-_]music[-_]uplink2?)">\s*<ctl name="([^"]+)"""").findAll(t)
                .forEach { paths.put("${f.name}:${it.groupValues[1]}", it.groupValues[2]) }
        }
        o.put("mixer_incall_paths", paths)
        // MediaTek: the modem call paths in audio_device.xml (what MtkAutoProfile builds from)
        files.firstOrNull { it.name == "audio_device.xml" }?.let { f ->
            val t = runCatching { f.readText() }.getOrDefault("")
            o.put("mtk_md_paths", org.json.JSONArray(Regex("<path name=\"((?:PLAYBACK\\d*_TO_MD\\d)|(?:MD\\d_TO_CAPTURE[A-Z_0-9]*))\"")
                .findAll(t).map { it.groupValues[1] }.distinct().toList()))
        }
        o.put("vendor_files", org.json.JSONArray(files.map { it.name }.filter { it.startsWith("mixer_paths") || it.startsWith("audio_") }.sorted()))
        // Where the audio files are, when they aren't in the usual places (folders only; e.g. sku_lahaina)
        o.put("vendor_audio_dirs", org.json.JSONArray(listOf("/vendor/etc/audio", "/odm/etc/audio").flatMap { d ->
            java.io.File(d).listFiles().orEmpty().filter { it.isDirectory }.map { "${it.parentFile?.path}/${it.name}" } }.sorted()))
        o.put("sku_dirs", org.json.JSONArray(VendorAudio.skuDirs().map { it.path }))
        o.put("incall_music_prop", Compat.prop("vendor.audio.feature.incall_music.enable"))
        o.put("board_platform", Compat.prop("ro.board.platform"))
        o.put("sound_cards", runCatching { java.io.File("/proc/asound/cards").readText().trim() }.getOrDefault(""))
        o
    }.getOrElse { org.json.JSONObject().put("error", it.toString()) }
}
