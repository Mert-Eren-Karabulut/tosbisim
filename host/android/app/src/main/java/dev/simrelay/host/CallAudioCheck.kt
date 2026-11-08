// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * End-of-call audio check: a relayed call that lost part of its audio either way reports itself (call_audio_poor) with
 * the host's numbers and log, so nobody has to be asked for a report. A Galaxy A13 host delivered half the caller's
 * frames to the phone in every call (7 Nov 2026), and the one report sent by hand came without the host's side: the
 * numbers that tell where they went (the daemon's txdrop, the bridge's backlog drops and slow writes) stayed on the
 * host. The daemon counts its side (AUDIO?), the bridge its own here; [judge] decides.
 */
object CallAudioCheck {
    /** One call's bridge numbers (AudioBridge, any thread). */
    data class Bridge(val sessions: Long, val framesIn: Long, val framesOut: Long, val writes: Long, val dropped: Long,
                      val slowestMs: Long, val stalls: Long)

    private val sessions = AtomicLong(); private val framesIn = AtomicLong(); private val framesOut = AtomicLong()
    private val writes = AtomicLong(); private val dropped = AtomicLong(); private val slowest = AtomicLong(); private val stalls = AtomicLong()
    @Volatile private var internet = false
    @Volatile private var held = false
    @Volatile private var hotspotAtStart = "off"

    /** A write at least this slow is a stall: the link stopped taking audio (a Meizu client got none for 15 s, 6 Nov). */
    const val STALL_MS = 1000L

    /** A call's audio starts (CALL_START: the daemon's totals restart too). */
    fun begin(hotspot: String) {
        listOf(sessions, framesIn, framesOut, writes, dropped, slowest, stalls).forEach { it.set(0) }
        internet = false; held = false; hotspotAtStart = hotspot
    }

    fun bridgeUp() { sessions.incrementAndGet() }
    fun frameIn() { framesIn.incrementAndGet() }
    fun dropped() { dropped.incrementAndGet() }
    fun wrote(frames: Int, ms: Long) {
        framesOut.addAndGet(frames.toLong()); writes.incrementAndGet()
        slowest.accumulateAndGet(ms, ::maxOf)
        if (ms >= STALL_MS) stalls.incrementAndGet()
    }
    fun internetUsed() { internet = true }
    /** A call was put on hold: the client sends nothing then, so its side isn't judged. */
    fun held() { held = true }

    fun bridge() = Bridge(sessions.get(), framesIn.get(), framesOut.get(), writes.get(), dropped.get(), slowest.get(), stalls.get())

    /**
     * What went wrong, or null: "both", "to_phone" (caller audio lost on its way to the phone), "from_phone" (the
     * phone's audio missing in the call) or "stall". Pure (CallAudioCheckTest). [d]: the daemon's AUDIO? totals.
     */
    fun judge(seconds: Long, d: Map<String, Long>, b: Bridge, held: Boolean): String? {
        if (seconds < 10) return null
        val toClient = (d["sent"] ?: 0) - (d["noclient"] ?: 0)
        val lostDown = (d["txdrop"] ?: 0) + b.dropped
        val down = toClient >= 500 && lostDown * 100 >= toClient * 3
        val live = d["live"] ?: 0
        val up = !held && live >= 500 && ((d["plc"] ?: 0) + (d["starved"] ?: 0)) * 100 >= live * 5
        return when {
            down && up -> "both"
            down -> "to_phone"
            up -> "from_phone"
            b.stalls > 0 -> "stall"
            else -> null
        }
    }

    /** The daemon's AUDIO? reply as numbers ("OK call=1 session=1 ... live=2420"). */
    fun totals(reply: String): Map<String, Long> =
        Regex("""\b(\w+)=(-?\d+)""").findAll(reply).associate { it.groupValues[1] to (it.groupValues[2].toLongOrNull() ?: 0) }

    /** Daemon executor, at the call's end: [reply] is AUDIO?, [seconds] since the call connected. */
    fun check(reply: String, seconds: Long) {
        if (!VoiceGate.fullVoice) return
        val d = totals(reply)
        if (d["live"] == null) return                                      // a daemon from before these totals
        val b = bridge()
        val what = judge(seconds, d, b, held) ?: return
        val toClient = ((d["sent"] ?: 0) - (d["noclient"] ?: 0)).coerceAtLeast(1)
        val live = (d["live"] ?: 0).coerceAtLeast(1)
        val detail = JSONObject().put("what", what).put("seconds", seconds)
            .put("via", if (b.sessions > 0) (if (internet) "bluetooth+internet" else "bluetooth") else if (internet) "internet" else "none")
            .put("to_phone_lost_pct", ((d["txdrop"] ?: 0) + b.dropped) * 100 / toClient)
            .put("from_phone_missing_pct", if (held) -1 else ((d["plc"] ?: 0) + (d["starved"] ?: 0)) * 100 / live)
            .put("slowest_write_ms", b.slowestMs).put("stalls", b.stalls)
            .put("frames_per_write", if (b.writes > 0) "%.2f".format(java.util.Locale.ROOT, b.framesOut.toDouble() / b.writes) else "-")
            .put("bridges", b.sessions).put("held", held)
            .put("ble_interval_ms", BleLink.lastIntervalMs)
            .put("hotspot", hotspotAtStart)
            .put("daemon", reply.removePrefix("OK ").take(300))
            .put("bridge", "in=${b.framesIn} out=${b.framesOut} writes=${b.writes} dropped=${b.dropped}")
        android.util.Log.w("SimRelayHost", "call audio poor ($what): $detail")
        Diag.event("call_audio_poor", detail)
    }
}
