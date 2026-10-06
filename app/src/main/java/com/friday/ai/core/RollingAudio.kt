package com.friday.ai.core

/**
 * A fixed-size circular buffer of the most recent audio.
 *
 * Speaker verification needs the audio the wake word was actually heard in,
 * and by the time the decoder confirms a match that audio is already in the
 * past — including the run-up before the gate opened. Keeping the last few
 * seconds costs nothing and means the check never has to ask the user to say
 * it again.
 *
 * Samples are kept at int16 scale, which is what both the recorder produces
 * and what [KaldiFbank] expects.
 */
class RollingAudio(private val capacity: Int) {

    private val buffer = FloatArray(capacity)
    private var writeIndex = 0
    private var filled = 0

    val size: Int get() = filled

    fun append(samples: ShortArray, length: Int) {
        for (i in 0 until length) {
            buffer[writeIndex] = samples[i].toFloat()
            writeIndex = (writeIndex + 1) % capacity
            if (filled < capacity) filled++
        }
    }

    /** The buffered audio in chronological order. */
    fun snapshot(): FloatArray {
        if (filled == 0) return FloatArray(0)
        val out = FloatArray(filled)
        // Oldest sample: right after the write head once the buffer has
        // wrapped, index 0 before that.
        val start = if (filled < capacity) 0 else writeIndex
        for (i in 0 until filled) {
            out[i] = buffer[(start + i) % capacity]
        }
        return out
    }

    fun clear() {
        writeIndex = 0
        filled = 0
    }
}
