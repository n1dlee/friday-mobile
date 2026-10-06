package com.friday.ai.core

import kotlin.math.sqrt

/** 16-bit PCM helpers shared by every recorder in the app. */
object PcmAudio {

    /** Root-mean-square level of the first [length] samples, on the int16 scale. */
    fun rms(buffer: ShortArray, length: Int): Double {
        val n = length.coerceAtMost(buffer.size)
        if (n <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val s = buffer[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / n)
    }

    /** The first [length] samples as little-endian bytes, the layout WAV and Vosk expect. */
    fun toLittleEndian(samples: ShortArray, length: Int): ByteArray {
        val n = length.coerceIn(0, samples.size)
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = samples[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
        }
        return bytes
    }
}
