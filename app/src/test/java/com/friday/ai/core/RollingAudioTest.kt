package com.friday.ai.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RollingAudioTest {

    private fun shorts(vararg v: Int) = ShortArray(v.size) { v[it].toShort() }

    @Test
    fun `an unfilled buffer returns what it has, in order`() {
        val a = RollingAudio(10)
        a.append(shorts(1, 2, 3), 3)
        assertEquals(listOf(1f, 2f, 3f), a.snapshot().toList())
        assertEquals(3, a.size)
    }

    @Test
    fun `once wrapped it keeps the most recent audio, oldest first`() {
        // Getting this backwards would feed the verifier time-reversed audio,
        // which produces a confident and completely wrong answer.
        val a = RollingAudio(4)
        a.append(shorts(1, 2, 3, 4, 5, 6), 6)
        assertEquals(listOf(3f, 4f, 5f, 6f), a.snapshot().toList())
        assertEquals(4, a.size)
    }

    @Test
    fun `exactly full is not treated as wrapped`() {
        val a = RollingAudio(3)
        a.append(shorts(7, 8, 9), 3)
        assertEquals(listOf(7f, 8f, 9f), a.snapshot().toList())
    }

    @Test
    fun `appends across many calls stay in order`() {
        val a = RollingAudio(5)
        (1..8).forEach { a.append(shorts(it), 1) }
        assertEquals(listOf(4f, 5f, 6f, 7f, 8f), a.snapshot().toList())
    }

    @Test
    fun `only the requested length is taken from the buffer`() {
        // AudioRecord fills a fixed array but reports a shorter read.
        val a = RollingAudio(10)
        a.append(shorts(1, 2, 3, 99, 99), 3)
        assertEquals(listOf(1f, 2f, 3f), a.snapshot().toList())
    }

    @Test
    fun `an empty buffer is empty, not null`() {
        assertEquals(0, RollingAudio(8).snapshot().size)
    }

    @Test
    fun `clear starts over`() {
        val a = RollingAudio(4)
        a.append(shorts(1, 2, 3, 4, 5), 5)
        a.clear()
        a.append(shorts(9), 1)
        assertEquals(listOf(9f), a.snapshot().toList())
    }
}
