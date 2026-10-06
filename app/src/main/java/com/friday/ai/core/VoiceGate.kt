package com.friday.ai.core

/**
 * Decides which audio frames reach the wake-word decoder.
 *
 * The bug this replaces: the engine dropped every frame quieter than a fixed
 * threshold **and reset the decoder each time**. A spoken word is not
 * uniformly loud — it starts quietly, and the stop consonant in the middle of
 * "пят-ни-ца" is very nearly silence. So the first frames of the word were
 * thrown away and the decoder's state was wiped mid-word, which is why the
 * wake word so rarely fired on the first try.
 *
 * Three things fix it:
 *
 *  - **Hysteresis.** It takes more energy to open the gate than to hold it
 *    open, so a dip inside a word doesn't close it.
 *  - **Hangover.** Once open, the gate stays open for a while after the sound
 *    stops, so word endings survive.
 *  - **Pre-roll.** The caller keeps the last few frames buffered; when the
 *    gate opens they get flushed first, so the decoder hears the *beginning*
 *    of the word rather than joining halfway through.
 *
 * The decoder is only reset after a long silence — a real gap between
 * utterances — which also stops the partial transcript accumulating rubbish
 * across separate attempts.
 */
class VoiceGate(
    var openThreshold: Double,
    private val frameMs: Int,
    private val hangoverMs: Int = DEFAULT_HANGOVER_MS,
    private val resetAfterSilenceMs: Int = DEFAULT_RESET_SILENCE_MS
) {

    companion object {
        /** Long enough to carry a trailing vowel, short enough not to hold the gate open on room noise. */
        const val DEFAULT_HANGOVER_MS = 700

        /** A gap this long means the previous utterance is over. */
        const val DEFAULT_RESET_SILENCE_MS = 1500

        /** How much audio to keep so the start of a word is never lost. */
        const val DEFAULT_PRE_ROLL_MS = 320

        /** Holding the gate open is easier than opening it — see hysteresis above. */
        const val CLOSE_RATIO = 0.55

        /** How far above the measured background a sound must be to count as speech. */
        const val NOISE_MARGIN = 2.2

        /**
         * Half the window the background is measured over. Speech is bursty,
         * so any two consecutive blocks of this length contain a gap between
         * words; steady road noise contains none. That difference is the whole
         * basis of the estimate.
         */
        const val FLOOR_BLOCK_MS = 1500

        /**
         * The floor may not push the gate arbitrarily high, or a genuinely
         * noisy place would make Friday deaf instead of merely picky.
         */
        const val MAX_FLOOR_MULTIPLIER = 6.0

        /**
         * Nobody says a wake word for five seconds. Past this the gate is
         * being held open by something continuous — an engine, a fan, a
         * television — and hysteresis alone would keep it open forever, so it
         * is forced shut and re-judged against the background it has since
         * measured.
         */
        const val MAX_OPEN_MS = 5000
    }

    /**
     * @param feed         hand this frame to the decoder
     * @param flushPreRoll first, hand it everything buffered before this frame
     * @param resetDecoder the previous utterance is over; clear decoder state
     */
    data class Decision(
        val feed: Boolean,
        val flushPreRoll: Boolean = false,
        val resetDecoder: Boolean = false
    )

    private var open = false
    private var silenceMs = 0
    private var spokenMs = 0

    /**
     * Background estimate: the quietest frame seen in the last one to two
     * blocks.
     *
     * A running average cannot be used here. The obvious version — average
     * only while the gate is shut — never learns anything in a car, because
     * road noise is loud enough to hold the gate open, so the estimator is
     * starved exactly when it is needed. Tracking the *minimum* has no such
     * circularity: speech has gaps between words and the engine does not.
     */
    private var blockMin = Double.MAX_VALUE
    private var previousBlockMin = Double.MAX_VALUE
    private var blockMs = 0

    /**
     * Zero until a full block has been measured: a single loud frame is not
     * evidence about the room, and trusting it would let the very first sound
     * raise the bar above itself.
     */
    private val noiseFloor: Double
        get() = if (previousBlockMin == Double.MAX_VALUE) 0.0
        else minOf(blockMin, previousBlockMin)

    /**
     * The threshold in force for the utterance currently being let through.
     *
     * Latched when the gate opens. Otherwise a long word would raise the floor
     * with its own quietest part and then fail to clear the bar it had just
     * lifted.
     */
    private var latchedThreshold = 0.0

    /**
     * The threshold actually in force: the configured one, raised if the room
     * is loud, and capped so a noisy place cannot silence Friday entirely.
     */
    val effectiveThreshold: Double
        get() = (noiseFloor * NOISE_MARGIN)
            .coerceIn(openThreshold, openThreshold * MAX_FLOOR_MULTIPLIER)

    /** Frames to keep in the caller's pre-roll buffer, given the frame size. */
    val preRollFrames: Int
        get() = (DEFAULT_PRE_ROLL_MS / frameMs.coerceAtLeast(1)).coerceIn(1, 32)

    /** True while the gate is passing audio through. */
    val isOpen: Boolean get() = open

    fun onFrame(energy: Double): Decision {
        // Measured before the decision, and on every frame regardless of gate
        // state — the minimum is what makes that safe.
        observeBackground(energy)

        val threshold = if (open) latchedThreshold else effectiveThreshold
        val loud = if (open) energy >= threshold * CLOSE_RATIO else energy >= threshold
        if (loud && !open) latchedThreshold = threshold

        if (loud) {
            val justOpened = !open
            open = true
            silenceMs = 0
            spokenMs += frameMs
            if (spokenMs >= MAX_OPEN_MS) {
                open = false
                spokenMs = 0
                return Decision(feed = false, resetDecoder = true)
            }
            return Decision(feed = true, flushPreRoll = justOpened)
        }

        if (open) {
            silenceMs += frameMs
            if (silenceMs <= hangoverMs) {
                // Still inside the word's tail, or a pause between two words
                // of the same phrase.
                return Decision(feed = true)
            }
            open = false
            spokenMs = 0
            return Decision(feed = false)
        }

        silenceMs += frameMs
        if (silenceMs >= resetAfterSilenceMs) {
            // Only reset once per gap, not on every frame of it.
            silenceMs = 0
            return Decision(feed = false, resetDecoder = true)
        }
        return Decision(feed = false)
    }

    private fun observeBackground(energy: Double) {
        blockMin = minOf(blockMin, energy)
        blockMs += frameMs
        if (blockMs >= FLOOR_BLOCK_MS) {
            previousBlockMin = blockMin
            blockMin = Double.MAX_VALUE
            blockMs = 0
        }
    }

    /** Clears the gate's state. The noise estimate is kept: the room has not changed. */
    fun reset() {
        open = false
        silenceMs = 0
        spokenMs = 0
    }
}
