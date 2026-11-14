// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

/**
 * Call audio for a Bluetooth link that carries very little: the caller's voice at a lower bit rate, two frames a write.
 *
 * A Galaxy A13 host's chip sends in the smallest radio packets, 27 bytes, and in a bad stretch its link passes one
 * write and at most two such packets per connection slot (15 ms): about 62 writes a second whether a write is 21
 * bytes or 37, and a pair of normal frames (three packets) takes two slots, so pairing them gained nothing (10 to 13
 * Nov 2026). The phone got 59 to 72% of the caller. What fits is 20 ms of audio in two radio packets: each frame
 * capped at [FRAME] bytes by the daemon (FRAME_MAX, about 15 kbit/s for 24, still wideband), two of them a write
 * ([WRITE] bytes), 50 writes a second.
 *
 * This is for that kind of link only: the chip's record must have shown the small packets ([LinkRadio.smallPackets]),
 * and on every other host nothing here runs. On such a host a Bluetooth audio channel is lean from its first frame.
 *
 * It used to wait for the link to fall behind (frames dropped from the bridge's backlog). That sign comes far too
 * late: Android's Bluetooth takes about 500 frames into its own queue before a write ever blocks, so the host saw
 * nothing for the first 12 s of a call while the phone got 58 frames of 100 a second from the first one, and the
 * queued audio then had to drain before lean helped: a call was choppy for 20 s and what followed arrived seconds
 * late (14 Nov 2026). Nothing may pile up there, so nothing is waited for.
 *
 * Lean lasts for that Bluetooth audio channel: a call that moves to the internet starts normal (the phone's own rule
 * still moves a fading call there, lean or not).
 */
object LeanAudio {
    /** A frame's Opus bytes when lean. */
    const val FRAME = 19
    /** One write when lean: two frames, each with the stream's 2-byte length and the daemon's 3-byte header. */
    const val WRITE = 2 * (2 + 3 + FRAME)
    /** How long a write waits for its second frame (one comes every 10 ms; a hold must not keep a frame back). */
    const val PAIR_WAIT_MS = 15L

    init {
        // Two radio packets hold one write: its L2CAP header (4 bytes) and length (2), then the frames
        check(WRITE + 6 <= 2 * HciRing.SMALLEST)
    }

    /** adb debug (AUDIO_LEAN --ez always true): every Bluetooth audio channel is lean, on any host. */
    @Volatile var always = false

    /** Why a Bluetooth audio channel is lean, for the log; null: it is not. [small]: [LinkRadio.smallPackets]. Pure (LeanAudioTest). */
    fun why(small: Boolean, always: Boolean = this.always): String? = when {
        always -> "lean on every host (debug)"
        small -> "this host's Bluetooth sends in small radio packets"
        else -> null
    }
}
