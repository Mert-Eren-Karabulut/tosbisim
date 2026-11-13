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
 * This is for that kind of link only, and only when it struggles:
 *  - the chip's record must have shown the small packets ([LinkRadio.smallPackets]). On every other host a link that
 *    stops taking audio means the phone is leaving Bluetooth range, and nothing here runs;
 *  - the internet goes first. A phone with Internet mode set up moves a fading call there by itself within seconds
 *    (its own rule, on what it hears); the host then waits [PATIENCE_S] of trouble before it goes lean, which is
 *    only reached when that move did not happen.
 * Lean lasts for that Bluetooth audio channel: the next call, and a call that moves to the internet, start normal.
 */
object LeanAudio {
    /** A frame's Opus bytes when lean. */
    const val FRAME = 19
    /** One write when lean: two frames, each with the stream's 2-byte length and the daemon's 3-byte header. */
    const val WRITE = 2 * (2 + 3 + FRAME)
    /** How long a write waits for its second frame (one comes every 10 ms; a hold must not keep a frame back). */
    const val PAIR_WAIT_MS = 15L

    /** A second in which this many frames fell out of the bridge's backlog is a troubled one (50 ms of the caller gone). */
    const val TROUBLED = 5
    /** Trouble is two troubled seconds within this many. */
    const val WINDOW_S = 6
    /** With Internet mode set up: how long the trouble must have lasted, the phone's move to the internet coming first. */
    const val PATIENCE_S = 5

    init {
        // Two radio packets hold one write: its L2CAP header (4 bytes) and length (2), then the frames
        check(WRITE + 6 <= 2 * HciRing.SMALLEST)
    }

    /** adb debug (AUDIO_LEAN --ez always true): every Bluetooth audio channel goes lean from its start, on any host. */
    @Volatile var always = false

    /**
     * When a Bluetooth audio channel goes lean: fed once a second with the frames dropped in it, true once. [patient]:
     * Internet mode is set up, so the phone's own move comes first. Pure (LeanAudioTest).
     */
    class Watch(private val patient: Boolean) {
        private val recent = ArrayDeque<Boolean>()
        private var second = 0
        private var since = -1                       // the second this trouble began; -1 when the window is clear

        fun second(dropped: Int): Boolean {
            second++
            val troubled = dropped >= TROUBLED
            recent.addLast(troubled)
            if (recent.size > WINDOW_S) recent.removeFirst()
            if (recent.none { it }) since = -1
            if (!troubled) return false
            if (since < 0) since = second
            return recent.count { it } >= 2 && (!patient || second - since >= PATIENCE_S)
        }
    }
}
