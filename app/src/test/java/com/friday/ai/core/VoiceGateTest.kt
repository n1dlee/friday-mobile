package com.friday.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides which audio reaches the wake-word decoder. Every test
 * here describes a way the previous energy check silently broke recognition.
 */
class VoiceGateTest {

    private val frameMs = 40
    private fun gate(threshold: Double = 150.0) = VoiceGate(threshold, frameMs)

    private val quiet = 40.0
    private val loud = 400.0

    @Test
    fun `a quiet frame before speech is not fed but is kept for pre-roll`() {
        val g = gate()
        val d = g.onFrame(quiet)
        assertFalse(d.feed)
        assertFalse("nothing has been said yet, so there is nothing to reset", d.resetDecoder)
    }

    @Test
    fun `the first loud frame asks for the pre-roll to be flushed`() {
        val g = gate()
        g.onFrame(quiet)
        val d = g.onFrame(loud)
        assertTrue(d.feed)
        assertTrue("the start of the word lives in the pre-roll", d.flushPreRoll)
    }

    @Test
    fun `pre-roll is only flushed once per utterance`() {
        val g = gate()
        g.onFrame(loud)
        assertFalse(g.onFrame(loud).flushPreRoll)
    }

    @Test
    fun `a dip inside a word does not close the gate`() {
        // This is the bug: the stop consonant in "пят-ни-ца" is near silence,
        // and the old code both dropped it and reset the decoder mid-word.
        val g = gate()
        g.onFrame(loud)
        val dip = g.onFrame(120.0) // below the open threshold, above 55% of it
        assertTrue("a mid-word dip must still reach the decoder", dip.feed)
        assertFalse(dip.resetDecoder)
        assertTrue(g.isOpen)
    }

    @Test
    fun `the tail of a word survives the hangover`() {
        val g = gate()
        g.onFrame(loud)
        // 700ms of hangover at 40ms per frame.
        repeat(VoiceGate.DEFAULT_HANGOVER_MS / frameMs) {
            assertTrue("frame $it of the tail was dropped", g.onFrame(quiet).feed)
        }
        assertFalse("but it does not stay open forever", g.onFrame(quiet).feed)
    }

    @Test
    fun `the decoder is never reset in the middle of speech`() {
        val g = gate()
        repeat(50) {
            val d = g.onFrame(if (it % 3 == 0) quiet else loud)
            assertFalse("reset at frame $it would wipe the word", d.resetDecoder)
        }
    }

    @Test
    fun `a real gap between utterances clears the stale partial transcript`() {
        val g = gate()
        g.onFrame(loud)
        // Hangover (700ms) then well past the 1500ms reset threshold.
        val untilReset = (VoiceGate.DEFAULT_HANGOVER_MS + VoiceGate.DEFAULT_RESET_SILENCE_MS) / frameMs
        val fired = (1..untilReset + 2).any { g.onFrame(quiet).resetDecoder }
        assertTrue("a partial left over from a failed attempt must not persist", fired)
    }

    @Test
    fun `reset fires once per gap, not once per silent frame`() {
        val g = gate()
        g.onFrame(loud)
        // 8 seconds of silence. At one reset per 1.5s gap that is a handful.
        val resets = (1..200).count { g.onFrame(quiet).resetDecoder }
        assertTrue("got $resets resets in 8s of silence", resets in 1..6)
    }

    @Test
    fun `pre-roll covers the start of a word`() {
        // Under ~300ms of lead-in the decoder joins "пятница" mid-word and
        // hears something that matches nothing.
        val g = gate()
        assertTrue("pre-roll of ${g.preRollFrames} frames is too short", g.preRollFrames * frameMs >= 240)
    }

    @Test
    fun `hysteresis means it is harder to open than to stay open`() {
        val g = gate(threshold = 200.0)
        assertFalse("110 must not open the gate", g.onFrame(110.0).feed)
        g.onFrame(400.0)
        assertTrue("but 110 must not close it either", g.onFrame(110.0).feed)
    }
}
