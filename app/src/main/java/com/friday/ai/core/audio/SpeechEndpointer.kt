package com.friday.ai.core.audio

import kotlin.math.sqrt

/**
 * Decides when the owner has started and finished speaking, from the
 * microphone samples alone.
 *
 * What it replaces cut long commands short: every 128 ms read was compared
 * with one fixed loudness threshold, and 800 ms "below it" ended the turn.
 * In a long sentence the voice drops — between words, at the end of words,
 * as the phone's automatic gain settles — so the turn ended mid-thought and
 * "Transcribing…" appeared while the owner was still talking. Short words
 * ("да", "стоп") had the opposite problem: they had to fill 400 ms of
 * 128 ms chunks to count at all.
 *
 * So, here:
 *  - **Time is counted in samples**, in 20 ms frames, whatever size the
 *    reads come in.
 *  - **The threshold follows the room.** Speech is what stands clear of the
 *    quietest stretch of the last few seconds; the calibrated threshold is
 *    the ceiling, so it is never stricter than before — only quiet rooms
 *    and quiet voices get the benefit.
 *  - **Hysteresis.** Once speaking, a frame needs only part of the opening
 *    level to count, so soft word endings don't look like silence.
 *  - **Longer speech, longer patience.** A quick command ends after 0.8 s of
 *    quiet; after a few seconds of talking, a pause to think gets 1.4 s.
 */
class SpeechEndpointer(
    /** The calibrated threshold: speech never has to be louder than this. */
    private val ceiling: Double,
    /** Speech already heard before recording began (a command said in one breath with the name). */
    leadMs: Long = 0,
    private val maxMs: Long = MAX_MS
) {

    enum class End { FINISHED, NO_SPEECH, MAX_DURATION, STOPPED }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME = 320 // 20 ms
        private const val FRAME_MS = 20L

        /** Nobody starts talking within this: the turn is over. */
        const val START_TIMEOUT_MS = 4_000L

        /** One turn is never longer: a long message, not a stuck microphone. */
        const val MAX_MS = 30_000L

        /** Speech shorter than this is a click or a breath, not "да". */
        const val MIN_SPEECH_MS = 200L

        /** Quiet that ends a short command… */
        const val END_SILENCE_MS = 800L

        /** …and a longer one, once the owner has been talking this long. */
        const val LONG_END_SILENCE_MS = 1_400L
        const val LONG_SPEECH_MS = 2_500L

        /** Consecutive voiced frames that start speech: a tap is shorter. */
        private const val START_FRAMES = 3

        /** Speech stands this far above the room's quietest moments. */
        private const val NOISE_MARGIN = 2.5

        /** Below this nothing is speech, however quiet the room. */
        const val MIN_THRESHOLD = 120.0

        /** Once speaking, this share of the opening level still counts as voice. */
        private const val HOLD_RATIO = 0.6

        /** The room's floor is the quietest 200 ms of the last 3 s. */
        private const val FLOOR_WINDOW = 10
        private const val FLOOR_HISTORY = 150
    }

    var speechStarted = leadMs > 0
        private set

    /** Voiced time so far, including the lead. */
    var speechMs = leadMs
        private set

    /** Enough voice to be worth transcribing; stopped by hand, whatever was said is. */
    val usable: Boolean get() = speechStarted && (speechMs >= MIN_SPEECH_MS || end == End.STOPPED)

    var end: End? = null
        private set

    /** Audio heard so far, counted in whole frames. */
    val elapsedMs: Long get() = totalMs

    private var totalMs = 0L
    private var silenceMs = 0L
    private var voicedRun = 0
    private val history = ArrayDeque<Double>()
    private val leftover = ShortArray(FRAME)
    private var leftoverCount = 0

    /** Feeds [count] samples; returns how the turn ended, once it has. */
    fun push(samples: ShortArray, count: Int = samples.size): End? {
        var i = 0
        while (i < count && end == null) {
            val take = minOf(FRAME - leftoverCount, count - i)
            samples.copyInto(leftover, leftoverCount, i, i + take)
            leftoverCount += take
            i += take
            if (leftoverCount == FRAME) {
                frame(rms(leftover))
                leftoverCount = 0
            }
        }
        return end
    }

    /** The owner pressed stop: what was said so far is the turn. */
    fun stop(): End = end ?: End.STOPPED.also { end = it }

    /** The level a frame must reach to open speech, given the room so far. */
    fun openThreshold(): Double {
        if (history.size < FLOOR_WINDOW) return ceiling
        var floor = Double.MAX_VALUE
        var sum = history.take(FLOOR_WINDOW).sum()
        for (start in 0..history.size - FLOOR_WINDOW) {
            if (start > 0) sum += history[start + FLOOR_WINDOW - 1] - history[start - 1]
            floor = minOf(floor, sum / FLOOR_WINDOW)
        }
        return minOf(ceiling, maxOf(MIN_THRESHOLD, floor * NOISE_MARGIN))
    }

    private fun frame(level: Double) {
        val open = openThreshold()
        history.addLast(level)
        if (history.size > FLOOR_HISTORY) history.removeFirst()
        totalMs += FRAME_MS
        if (speechStarted) speaking(level > open * HOLD_RATIO) else waiting(level > open)
        end = end ?: when {
            totalMs >= maxMs -> End.MAX_DURATION
            !speechStarted && totalMs >= START_TIMEOUT_MS -> End.NO_SPEECH
            speechStarted && silenceMs >= endSilence() -> End.FINISHED
            else -> null
        }
    }

    private fun waiting(voiced: Boolean) {
        voicedRun = if (voiced) voicedRun + 1 else 0
        if (voicedRun >= START_FRAMES) {
            speechStarted = true
            speechMs += voicedRun * FRAME_MS
            silenceMs = 0
        }
    }

    private fun speaking(voiced: Boolean) {
        if (voiced) {
            speechMs += FRAME_MS
            silenceMs = 0
        } else {
            silenceMs += FRAME_MS
        }
    }

    private fun endSilence(): Long = if (speechMs >= LONG_SPEECH_MS) LONG_END_SILENCE_MS else END_SILENCE_MS

    private fun rms(frame: ShortArray): Double {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        return sqrt(sum / frame.size)
    }
}
