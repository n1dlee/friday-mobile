package com.friday.ai.core

import kotlin.math.sqrt

/**
 * The spoken part of a recording, without the silence and room noise
 * around it.
 *
 * The voice profile is enrolled from speech only (the enroller keeps just
 * the frames its gate lets through). The wake word used to be checked
 * against the last three seconds as they were — mostly silence, fan and
 * traffic around a short "Пятница" — so the two never looked like the same
 * voice and the owner was turned away as a stranger. Checking like with
 * like fixes that.
 */
object VoiceTrim {

    private const val FRAME = 320 // 20 ms at 16 kHz
    private const val MIN_SPEECH_THRESHOLD = 150.0
    private const val NOISE_MARGIN = 2.5
    private const val FLOOR_WINDOW = 10 // 200 ms

    /** Frames kept either side of speech, so word edges aren't clipped. */
    private const val PAD_FRAMES = 3

    /** Less speech than this and trimming is not to be trusted: the whole clip is returned. */
    private const val MIN_KEPT = FRAME * 15 // 300 ms

    /** At most this much of the most recent speech: the wake word is at the end. */
    private const val MAX_KEPT = 16_000 * 2

    fun speech(audio: FloatArray): FloatArray {
        val frames = audio.size / FRAME
        if (frames == 0) return audio
        val levels = DoubleArray(frames) { f -> rms(audio, f * FRAME) }
        // The background is the quietest 200 ms: speech always has gaps between
        // words, steady noise doesn't. (A percentile fails when the clip is
        // mostly speech: the "floor" lands on the voice itself.)
        val window = minOf(FLOOR_WINDOW, frames)
        val floor = (0..frames - window).minOf { start ->
            (start until start + window).sumOf { levels[it] } / window
        }
        val threshold = maxOf(MIN_SPEECH_THRESHOLD, floor * NOISE_MARGIN)
        val loud = BooleanArray(frames) { levels[it] > threshold }
        val keep = BooleanArray(frames) { f ->
            (maxOf(0, f - PAD_FRAMES)..minOf(frames - 1, f + PAD_FRAMES)).any { loud[it] }
        }
        val kept = ArrayList<Float>()
        for (f in 0 until frames) if (keep[f]) for (i in 0 until FRAME) kept += audio[f * FRAME + i]
        if (kept.size < MIN_KEPT) return audio
        val tail = if (kept.size > MAX_KEPT) kept.subList(kept.size - MAX_KEPT, kept.size) else kept
        return tail.toFloatArray()
    }

    private fun rms(audio: FloatArray, from: Int): Double {
        var sum = 0.0
        for (i in from until from + FRAME) sum += audio[i].toDouble() * audio[i]
        return sqrt(sum / FRAME)
    }
}
