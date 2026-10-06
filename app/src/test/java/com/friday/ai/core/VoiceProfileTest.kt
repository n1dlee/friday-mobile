package com.friday.ai.core

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceProfileTest {

    private val rng = Random(7)

    /** An embedding near [base], [spread] controlling how far. */
    private fun near(base: FloatArray, spread: Float): FloatArray =
        FloatArray(base.size) { base[it] + (rng.nextFloat() - 0.5f) * 2f * spread }

    private fun randomEmbedding(dim: Int = 256) = FloatArray(dim) { rng.nextFloat() - 0.5f }

    @Test
    fun `too few samples is not a profile`() {
        val e = List(VoiceProfile.MIN_SAMPLES - 1) { randomEmbedding() }
        assertNull(VoiceProfile.enroll(e))
        assertNull(VoiceProfile.enroll(emptyList()))
    }

    @Test
    fun `the owner is accepted by their own profile`() {
        val me = randomEmbedding()
        val profile = VoiceProfile.enroll(List(6) { near(me, 0.25f) })!!
        repeat(20) {
            assertTrue("a fresh reading of the same voice was rejected",
                VoiceProfile.accepts(profile, near(me, 0.25f)))
        }
    }

    @Test
    fun `an unrelated voice is rejected`() {
        val me = randomEmbedding()
        val profile = VoiceProfile.enroll(List(6) { near(me, 0.25f) })!!
        var rejected = 0
        repeat(50) { if (!VoiceProfile.accepts(profile, randomEmbedding())) rejected++ }
        assertTrue("only $rejected of 50 strangers were rejected", rejected >= 45)
    }

    @Test
    fun `the threshold is calibrated to how consistent the enrolment was`() {
        val me = randomEmbedding()
        val tight = VoiceProfile.enroll(List(6) { near(me, 0.10f) })!!
        val loose = VoiceProfile.enroll(List(6) { near(me, 0.60f) })!!
        assertTrue(
            "a varied enrolment must not produce a stricter bar than a tight one: " +
                "${tight.threshold} vs ${loose.threshold}",
            loose.threshold <= tight.threshold
        )
    }

    @Test
    fun `the threshold stays inside its bounds however odd the enrolment`() {
        val me = randomEmbedding()
        listOf(0.01f, 0.3f, 2.0f, 20f).forEach { spread ->
            val p = VoiceProfile.enroll(List(6) { near(me, spread) })!!
            assertTrue(
                "spread $spread gave ${p.threshold}",
                p.threshold in VoiceProfile.MIN_THRESHOLD..VoiceProfile.MAX_THRESHOLD
            )
        }
    }

    @Test
    fun `cohesion reports the least typical sample, not the average`() {
        // Averaging would hide one bad recording behind five good ones, and
        // the threshold derived from it would then exclude that reading.
        val me = randomEmbedding()
        val samples = MutableList(5) { near(me, 0.05f) }
        samples.add(randomEmbedding())
        val p = VoiceProfile.enroll(samples)!!
        assertTrue("cohesion ${p.cohesion} ignored the outlier", p.cohesion < 0.8f)
    }

    @Test
    fun `the centroid is unit length`() {
        val p = VoiceProfile.enroll(List(6) { randomEmbedding() })!!
        val norm = kotlin.math.sqrt(p.centroid.sumOf { it.toDouble() * it })
        assertEquals(1.0, norm, 1e-4)
    }

    @Test
    fun `scoring is scale invariant`() {
        // The model's output magnitude must not change the decision.
        val me = randomEmbedding()
        val p = VoiceProfile.enroll(List(6) { near(me, 0.2f) })!!
        val probe = near(me, 0.2f)
        val loud = FloatArray(probe.size) { probe[it] * 42f }
        assertEquals(VoiceProfile.score(p, probe), VoiceProfile.score(p, loud), 1e-4f)
    }

    @Test
    fun `a zero embedding does not produce NaN`() {
        val p = VoiceProfile.enroll(List(6) { randomEmbedding() })!!
        val score = VoiceProfile.score(p, FloatArray(256))
        assertTrue("got $score", score.isFinite())
    }

    // --- storage ------------------------------------------------------

    @Test
    fun `a profile survives a round trip`() {
        val p = VoiceProfile.enroll(List(6) { randomEmbedding() })!!
        val back = VoiceProfile.deserialise(VoiceProfile.serialise(p))
        assertNotNull(back)
        assertEquals(p.sampleCount, back!!.sampleCount)
        assertEquals(p.threshold, back.threshold, 1e-6f)
        p.centroid.forEachIndexed { i, v ->
            assertTrue("dim $i", abs(v - back.centroid[i]) < 1e-6f)
        }
    }

    @Test
    fun `nonsense in storage reads as not enrolled`() {
        // A corrupt profile must never take down the voice loop at startup.
        listOf(null, "", "   ", "garbage", "1|2|3", "a|b|c|d", "0.5|0.9|6|").forEach {
            assertNull("'$it'", VoiceProfile.deserialise(it))
        }
    }

    @Test
    fun `an empty profile means everyone is let through`() {
        // Failing open is deliberate: an assistant that answers nobody is
        // worse than one that occasionally answers the wrong person.
        assertNull(VoiceProfile.deserialise(""))
    }

    @Test
    fun `mismatched dimensions score as no match rather than crashing`() {
        val p = VoiceProfile.enroll(List(6) { randomEmbedding(256) })!!
        assertFalse(VoiceProfile.accepts(p, FloatArray(128) { 1f }))
    }

    // --- versions ----------------------------------------------------

    @Test
    fun `a new profile covers commands`() {
        val p = VoiceProfile.enroll(List(8) { randomEmbedding() })!!
        assertEquals(VoiceProfile.CURRENT_VERSION, p.version)
        assertTrue(p.coversCommands)
    }

    @Test
    fun `an old wake-word-only profile still loads, but not for commands`() {
        // Stored before versioning: four fields, no tag. It must keep
        // guarding the wake word and must not start judging commands, which
        // it rejected about half the time.
        val centroid = FloatArray(256) { 0.0625f }.joinToString(",")
        val old = VoiceProfile.deserialise("0.55|0.9|6|$centroid")
        assertNotNull(old)
        assertEquals(1, old!!.version)
        assertFalse(old.coversCommands)
        assertEquals(0.55f, old.threshold, 1e-6f)
    }

    @Test
    fun `the version survives a round trip`() {
        val p = VoiceProfile.enroll(List(8) { randomEmbedding() })!!
        val back = VoiceProfile.deserialise(VoiceProfile.serialise(p))!!
        assertEquals(p.version, back.version)
        assertEquals(p, back)
    }

    @Test
    fun `a malformed version tag reads as not enrolled`() {
        val c = FloatArray(4) { 0.5f }.joinToString(",")
        assertNull(VoiceProfile.deserialise("vX|0.5|0.9|8|$c"))
        assertNull(VoiceProfile.deserialise("v2|0.5|0.9|$c"))
    }

    @Test
    fun `enrolment mixes the wake word with ordinary speech`() {
        val phrases = VoiceProfile.ENROLMENT_PHRASES
        val wake = phrases.count { WakePhrases.isWakeCall(it) }
        assertTrue("enough samples to build a profile", phrases.size >= VoiceProfile.MIN_SAMPLES)
        assertTrue("the wake word itself must be in the set, got $wake", wake >= 2)
        assertTrue(
            "most of the set must be other speech, or the profile learns the word",
            phrases.size - wake > wake
        )
        // A command that started with the wake word would just be another wake sample.
        phrases.filterNot { WakePhrases.isWakeCall(it) }.forEach {
            assertFalse("'$it' contains the name", it.lowercase().contains("пятниц"))
        }
    }
}
