package com.friday.ai.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Whether a stretch of voice was sung rather than said.
 *
 * The wake-word decoder only knows a handful of words, so a sung syllable
 * gets forced onto the nearest of them — "пятница" — and the owner's own
 * voice passes the speaker check. What sets singing apart is pitch: speech
 * glides, its vowels last a tenth of a second, and its pitch never sits
 * still; a sung note holds one pitch for a third of a second or more.
 */
object Singing {

    private const val SAMPLE_RATE = 16_000
    private const val WINDOW = 640 // 40 ms
    private const val HOP = 160 // 10 ms
    private const val MIN_LAG = 32 // 500 Hz
    private const val MAX_LAG = 267 // 60 Hz

    /** How periodic a frame must be to have a pitch at all. */
    private const val VOICED = 0.7

    /** Quieter frames are breath and room, not voice. */
    private const val MIN_RMS = 200.0

    /** A note may wobble this much from one frame to the next (vibrato, drift)… */
    private const val STEP_SEMITONES = 0.5

    /** …and this much from where it started, and still be the same note. */
    private const val SPAN_SEMITONES = 1.0

    /** A held pitch this long is a sung note: spoken vowels are a third of this. */
    const val HELD_NOTE_MS = 300

    private const val SEMITONES_PER_OCTAVE = 12.0

    fun isSung(audio: FloatArray): Boolean = longestHeldNoteMs(audio) >= HELD_NOTE_MS

    /** The longest run of frames that stay on one pitch, in milliseconds. */
    fun longestHeldNoteMs(audio: FloatArray): Int {
        var best = 0
        var run = 0
        var start = 0.0
        var previous = 0.0
        var from = 0
        while (from + WINDOW + MAX_LAG < audio.size) {
            val note = pitch(audio, from)?.let(::semitones)
            val holds = note != null && run > 0 &&
                abs(note - previous) <= STEP_SEMITONES && abs(note - start) <= SPAN_SEMITONES
            when {
                note == null -> run = 0
                holds -> run++
                else -> {
                    run = 1
                    start = note
                }
            }
            if (note != null) previous = note
            best = maxOf(best, run)
            from += HOP
        }
        return best * HOP * MILLIS / SAMPLE_RATE
    }

    /** Fundamental frequency of the frame at [from], in Hz, or null if it is not voiced. */
    internal fun pitch(audio: FloatArray, from: Int): Double? {
        var energy = 0.0
        for (i in from until from + WINDOW) energy += audio[i].toDouble() * audio[i]
        if (sqrt(energy / WINDOW) < MIN_RMS) return null
        val corr = DoubleArray(MAX_LAG + 2)
        for (lag in MIN_LAG..MAX_LAG + 1) {
            var cross = 0.0
            var shifted = 0.0
            for (i in from until from + WINDOW) {
                cross += audio[i].toDouble() * audio[i + lag]
                shifted += audio[i + lag].toDouble() * audio[i + lag]
            }
            corr[lag] = if (shifted > 0) cross / sqrt(energy * shifted) else 0.0
        }
        val best = (MIN_LAG..MAX_LAG).maxOf { corr[it] }
        if (best < VOICED) return null
        // The first peak nearly as strong as the best: two periods correlate as
        // well as one, and taking the strongest would jump an octave at random.
        val lag = (MIN_LAG..MAX_LAG).firstOrNull { l ->
            corr[l] >= best * FIRST_PEAK && corr[l] >= corr[l - 1] && corr[l] >= corr[l + 1]
        } ?: (MIN_LAG..MAX_LAG).maxBy { corr[it] }
        return SAMPLE_RATE.toDouble() / lag
    }

    private const val FIRST_PEAK = 0.9

    private fun semitones(hz: Double): Double = SEMITONES_PER_OCTAVE * ln(hz) / ln(2.0)

    private const val MILLIS = 1000
}
