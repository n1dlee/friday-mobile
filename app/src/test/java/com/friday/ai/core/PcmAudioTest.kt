package com.friday.ai.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmAudioTest {

    @Test
    fun `rms of a constant signal is its magnitude`() {
        assertEquals(1000.0, PcmAudio.rms(ShortArray(8) { 1000 }, 8), 1e-9)
        assertEquals(1000.0, PcmAudio.rms(ShortArray(8) { -1000 }, 8), 1e-9)
    }

    @Test
    fun `rms only reads the samples the recorder reported`() {
        // AudioRecord fills a fixed buffer but returns a shorter count; the
        // stale tail from the previous read must not leak into the level.
        val buf = shortArrayOf(100, 100, 30000, 30000)
        assertEquals(100.0, PcmAudio.rms(buf, 2), 1e-9)
    }

    @Test
    fun `an empty read is silence, not NaN`() {
        assertEquals(0.0, PcmAudio.rms(ShortArray(4), 0), 0.0)
        assertEquals(0.0, PcmAudio.rms(ShortArray(0), 0), 0.0)
    }

    @Test
    fun `a length past the buffer is clamped rather than crashing`() {
        assertEquals(5.0, PcmAudio.rms(shortArrayOf(5, 5), 10), 1e-9)
    }

    @Test
    fun `bytes are little-endian`() {
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0xFF.toByte()),
            PcmAudio.toLittleEndian(shortArrayOf(0x1234, -1), 2)
        )
    }

    @Test
    fun `extreme samples survive the conversion`() {
        val bytes = PcmAudio.toLittleEndian(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE), 2)
        assertArrayEquals(byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F), bytes)
    }

    @Test
    fun `only the reported samples are converted`() {
        assertEquals(2, PcmAudio.toLittleEndian(shortArrayOf(1, 2, 3), 1).size)
    }
}
