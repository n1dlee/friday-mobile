package com.friday.ai.core

import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the filterbank against values produced by
 * `torchaudio.compliance.kaldi.fbank` — the exact function the speaker model
 * was trained on.
 *
 * This matters more than a normal unit test. Wrong features do not make the
 * embeddings slightly worse; they make them meaningless, and nothing about
 * the running app would look broken. Every constant that could plausibly
 * drift — window type, its denominator, the pre-emphasis edge case, the FFT
 * padding, the mel edges — changes these numbers.
 *
 * Golden values came from:
 *   kaldi.fbank(w, num_mel_bins=80, frame_length=25, frame_shift=10,
 *               dither=0.0, sample_frequency=16000,
 *               window_type='hamming', use_energy=False)
 */
class KaldiFbankTest {

    /** Reproduces the reference generator exactly; integer noise plus a tone. */
    private fun makeWave(n: Int, seed: Long, f0: Double): FloatArray {
        var s = seed
        return FloatArray(n) { i ->
            s = (1103515245L * s + 12345L) % (1L shl 31)
            val noise = ((s shr 16) % 2000) - 1000
            val tone = 6000.0 * sin(2.0 * Math.PI * f0 * i / 16000.0)
            (noise + tone).toInt().toFloat()
        }
    }

    @Test
    fun `the generator matches the one used for the golden values`() {
        // If this drifts, every other assertion here is meaningless.
        val w = makeWave(6, 12345, 130.0)
        assertEquals(
            listOf(468f, 1294f, -271f, 1413f, 1143f, 559f),
            w.toList()
        )
    }

    @Test
    fun `frame count follows Kaldi snip_edges`() {
        assertEquals(0, KaldiFbank.frameCount(399))
        assertEquals(1, KaldiFbank.frameCount(400))
        assertEquals(1, KaldiFbank.frameCount(559))
        assertEquals(2, KaldiFbank.frameCount(560))
        assertEquals(98, KaldiFbank.frameCount(16000))
    }

    @Test
    fun `output shape matches torchaudio`() {
        val f = KaldiFbank.compute(makeWave(16000, 12345, 130.0))
        assertEquals(98, f.size)
        assertEquals(80, f[0].size)
    }

    @Test
    fun `first frame matches torchaudio bin for bin`() {
        val f = KaldiFbank.compute(makeWave(16000, 12345, 130.0))
        val gold = doubleArrayOf(
            14.0822, 15.34635, 19.51813, 20.59285, 20.57282, 19.92394, 16.95783, 11.28266
        )
        gold.forEachIndexed { i, expected ->
            assertClose("frame 0, bin $i", expected, f[0][i])
        }
    }

    @Test
    fun `a later frame matches too`() {
        // Guards the framing arithmetic, not just the first window.
        val f = KaldiFbank.compute(makeWave(16000, 12345, 130.0))
        val gold = doubleArrayOf(
            14.04897, 15.25497, 19.46794, 20.55451, 20.53032, 19.86605, 16.60117, 13.23499
        )
        gold.forEachIndexed { i, expected ->
            assertClose("frame 10, bin $i", expected, f[10][i])
        }
    }

    @Test
    fun `whole-frame and whole-matrix aggregates match`() {
        // A per-bin check can pass while a handful of high bins are wrong;
        // these two catch that.
        val f = KaldiFbank.compute(makeWave(16000, 12345, 130.0))
        assertClose("frame 50 sum", 1411.24207, f[50].sum(), tolerance = 0.05)
        val mean = f.sumOf { row -> row.sum().toDouble() } / (f.size * 80)
        assertClose("matrix mean", 17.76111, mean.toFloat())
    }

    @Test
    fun `mean subtraction zeroes every bin`() {
        val f = KaldiFbank.compute(makeWave(16000, 12345, 130.0))
        KaldiFbank.subtractMean(f)
        for (b in 0 until 80) {
            val mean = f.sumOf { it[b].toDouble() } / f.size
            assertTrue("bin $b mean $mean", abs(mean) < 1e-3)
        }
    }

    @Test
    fun `too little audio yields nothing rather than crashing`() {
        assertEquals(0, KaldiFbank.compute(FloatArray(100)).size)
        assertEquals(0, KaldiFbank.compute(FloatArray(0)).size)
    }

    @Test
    fun `silence floors at the log epsilon instead of diverging`() {
        val f = KaldiFbank.compute(FloatArray(16000))
        assertTrue(f.isNotEmpty())
        // ln(1.1920929e-07) — the floor, not -inf or NaN.
        f[0].forEach {
            assertTrue("got $it", it.isFinite())
            assertClose("silence floor", -15.9424, it, tolerance = 0.01)
        }
    }

    private fun assertClose(what: String, expected: Double, actual: Float, tolerance: Double = 1e-3) {
        assertTrue(
            "$what: expected $expected but was $actual",
            abs(expected - actual) <= tolerance
        )
    }

    private fun assertClose(what: String, expected: Double, actual: Double, tolerance: Double = 1e-3) =
        assertClose(what, expected, actual.toFloat(), tolerance)
}
