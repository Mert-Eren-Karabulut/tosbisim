// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.os.Build
import android.util.Log
import dev.simrelay.protocol.v1.VoiceStatus
import dev.simrelay.protocol.v1.VoiceTestHostResult
import dev.simrelay.protocol.v1.VoiceTestVerdict
import org.json.JSONObject

/**
 * Who may relay call audio. Full voice is on only after the guided call test passed on this phone *and firmware
 * build* with the current profile (every phone, verified models included: full voice only where it is measured).
 * Until then calls are Basic (ring, answer, hang up from the iPhone, no audio), except the test call itself.
 */
object VoiceGate {
    private const val TAG = "SimRelayHost"
    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("voice", Context.MODE_PRIVATE)

    /** CallKit UUID of the test call in progress (Dial.voice_test), null otherwise. */
    @Volatile var testUuid: String? = null
    @Volatile private var lastResult: VoiceTestHostResult? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    val passed: Boolean
        get() {
            val p = Daemon.profile() ?: return false
            val pass = runCatching { JSONObject(prefs.getString("pass", "") ?: "") }.getOrNull() ?: return false
            return pass.optString("firmware") == Build.FINGERPRINT && pass.optString("profile") == p.id
        }

    val fullVoice get() = Daemon.profile() != null && passed

    /** May the call with this client UUID carry audio? */
    fun allows(clientUuid: String?): Boolean = fullVoice || (clientUuid != null && clientUuid == testUuid)

    fun status(): VoiceStatus {
        val v = Compat.verdict(app)
        val p = Daemon.profile()
        val pass = runCatching { JSONObject(prefs.getString("pass", "") ?: "") }.getOrNull()
        return VoiceStatus.newBuilder()
            .setSupportTier(v.tier.name.lowercase()).setDeviceName(v.name)
            .setProfileSource(p?.source.orEmpty()).setProfileId(p?.id.orEmpty())
            .setCanTest(p != null).setPassed(passed)
            .setPassedMs(if (passed) pass?.optLong("at") ?: 0 else 0)
            .setFirmware(Build.FINGERPRINT)
            .build()
    }

    /** During the test call: what reached us and what we played (daemon totals since the audio started). */
    fun check(done: (VoiceTestHostResult) -> Unit) = Daemon.query("AUDIO?") { reply ->
        fun num(k: String) = reply?.let { Regex("\\b$k=(-?\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val p = Daemon.profile()
        val r = VoiceTestHostResult.newBuilder()
            .setInCall(num("call") == 1).setSilenceControls(num("silence"))
            .setCapturedFrames(num("cap")).setVoicedFrames(num("voiced")).setSentFrames(num("sent"))
            .setPlayedFrames(num("played")).setPeak(num("peak"))
            .setProfileId(p?.id.orEmpty()).setProfileSource(p?.source.orEmpty())
            .build()
        if (r.inCall) lastResult = r
        Log.i(TAG, "call test host check: $reply")
        done(r)
    }

    /**
     * The user's answers. A pass needs the host's own measurements too: the friend's voice reached the capture
     * path and the iPhone's audio was played into the call. Returns null on success, else why it didn't pass.
     */
    fun verdict(v: VoiceTestVerdict): String? {
        val r = lastResult
        testUuid = null
        val why = when {
            !v.passed -> "not confirmed by the user"
            r == null -> "no measurement from the test call"
            r.voicedFrames < 50 -> "the caller's voice didn't reach the host's capture path"
            r.playedFrames < 100 -> "no iPhone audio was played into the call"
            !v.callerHeardUs -> "the caller didn't hear the iPhone"
            v.echo -> "the caller heard an echo"
            v.hostSound == VoiceTestVerdict.HostSound.MADE_SOUND -> "the host phone made sound"
            else -> null
        }
        val p = Daemon.profile()
        if (why == null && p != null) {
            prefs.edit().putString("pass", JSONObject().put("firmware", Build.FINGERPRINT).put("profile", p.id)
                .put("source", p.source).put("at", System.currentTimeMillis()).toString()).apply()
            Log.i(TAG, "call test PASSED: full voice on (${p.id}, ${p.source})")
        } else Log.i(TAG, "call test not passed: $why")
        lastResult = null
        return why
    }
}
