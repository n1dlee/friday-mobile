package com.friday.ai.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adaptive part of the gate: in a car the background is loud all the time,
 * and a fixed threshold either opens on the engine or stops hearing the user.
 */
class VoiceGateNoiseTest {

    private val frameMs = 40
    private fun gate() = VoiceGate(openThreshold = 150.0, frameMs = frameMs)

    /** Runs [seconds] of steady background through a closed gate. */
    private fun soak(g: VoiceGate, level: Double, seconds: Int) {
        repeat(seconds * 1000 / frameMs) { g.onFrame(level) }
    }

    @Test
    fun `a quiet room leaves the configured threshold alone`() {
        val g = gate()
        soak(g, 20.0, 10)
        assertTrue("got ${g.effectiveThreshold}", g.effectiveThreshold in 150.0..151.0)
    }

    @Test
    fun `steady road noise raises the bar`() {
        val g = gate()
        soak(g, 300.0, 20)
        assertTrue(
            "the gate must climb above the engine, got ${g.effectiveThreshold}",
            g.effectiveThreshold > 300.0
        )
    }

    @Test
    fun `road noise alone never opens the gate`() {
        val g = gate()
        // Twenty seconds of driving, then five more.
        soak(g, 300.0, 20)
        repeat(5 * 1000 / frameMs) {
            assertFalse("the engine opened the gate", g.onFrame(300.0).feed)
        }
    }

    @Test
    fun `a voice over road noise still gets through`() {
        val g = gate()
        soak(g, 300.0, 20)
        // Speech in a car is well above the engine — that is why people can
        // hear each other in one.
        assertTrue("a raised voice must still open the gate", g.onFrame(1400.0).feed)
    }

    @Test
    fun `something holding the gate open forever is eventually let go`() {
        // Hysteresis alone would keep the gate open on a continuous sound
        // indefinitely, and it would never get the chance to notice that the
        // sound is the room rather than a person.
        val g = gate()
        val frames = VoiceGate.MAX_OPEN_MS / frameMs
        repeat(frames + 1) { g.onFrame(400.0) }
        assertFalse("the gate never let go", g.isOpen)
    }

    @Test
    fun `a noisy room cannot make Friday deaf`() {
        val g = gate()
        soak(g, 5000.0, 60)
        assertTrue(
            "the floor must stay capped, got ${g.effectiveThreshold}",
            g.effectiveThreshold <= 150.0 * VoiceGate.MAX_FLOOR_MULTIPLIER + 0.001
        )
    }

    @Test
    fun `leaving the car relaxes the gate again`() {
        val g = gate()
        soak(g, 300.0, 20)
        val inCar = g.effectiveThreshold
        soak(g, 20.0, 20)
        assertTrue(
            "got $inCar then ${g.effectiveThreshold}",
            g.effectiveThreshold < inCar
        )
    }

    @Test
    fun `talking does not progressively deafen the assistant`() {
        // Real speech is bursty, and the gaps between phrases are what the
        // minimum tracker latches onto. If a conversation could raise the
        // floor, Friday would go deaf the longer you talked to her.
        val g = gate()
        repeat(8) {
            repeat(1500 / frameMs) { g.onFrame(3000.0) }  // 1.5s of speech
            repeat(800 / frameMs) { g.onFrame(25.0) }     // 0.8s gap
        }
        assertTrue(
            "a conversation raised the bar to ${g.effectiveThreshold}",
            g.effectiveThreshold in 150.0..151.0
        )
    }

    @Test
    fun `sustained loud sound is noise, not speech, and does raise the bar`() {
        // Eight unbroken seconds is not a sentence. Treating it as background
        // is the correct reading — that is a hairdryer, not a person.
        val g = gate()
        repeat(200) { g.onFrame(3000.0) }
        assertTrue("got ${g.effectiveThreshold}", g.effectiveThreshold > 150.0)
    }

    @Test
    fun `the bar does not move underneath a word already being heard`() {
        val g = gate()
        soak(g, 20.0, 5)
        g.onFrame(2000.0)
        // A long word whose quietest part would otherwise lift the floor above
        // the word itself.
        repeat(100) {
            assertTrue("dropped frame $it mid-word", g.onFrame(600.0).feed)
        }
    }
}
