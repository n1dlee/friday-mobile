package com.friday.ai.core.audio

import com.friday.ai.core.VoiceProfile
import com.friday.ai.core.audio.SpeechEndpointer.End
import com.friday.ai.service.SpeakerEmbedder
import com.friday.ai.service.voice.SpeakerGate
import io.mockk.every
import io.mockk.mockk
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RATE = 16_000

/** A voice-like tone of the given peak amplitude. */
private fun voice(ms: Int, amp: Int): ShortArray =
    ShortArray(ms * RATE / 1000) { (amp * sin(2 * PI * 180 * it / RATE)).toInt().toShort() }

/** Steady room noise, the same every run. */
private fun room(ms: Int, amp: Int): ShortArray {
    var seed = 12345L
    return ShortArray(ms * RATE / 1000) {
        seed = (seed * 1103515245 + 12345) and 0x7fffffff
        ((seed % (2 * amp + 1)) - amp).toInt().toShort()
    }
}

private fun join(vararg parts: ShortArray): ShortArray = parts.fold(ShortArray(0)) { a, b -> a + b }

/** Pushes [audio] in reads of [chunk] samples, as a microphone would. */
private fun SpeechEndpointer.feed(audio: ShortArray, chunk: Int = 2048): End? {
    var i = 0
    while (i < audio.size && end == null) {
        val n = minOf(chunk, audio.size - i)
        push(audio.copyOfRange(i, i + n), n)
        i += n
    }
    return end
}

/** A calibration like the owner's: speech had to pass 450. */
private const val CEILING = 450.0

class SpeechEndpointerTest {

    @Test
    fun `a short word is a whole command`() {
        val e = SpeechEndpointer(CEILING)
        assertEquals(End.FINISHED, e.feed(join(room(1000, 100), voice(260, 3000), room(1500, 100))))
        assertTrue(e.usable)
        // Over within the short pause: a quick "да" is not kept waiting.
        assertTrue(e.elapsedMs in 2000L..2200L)
    }

    @Test
    fun `a click is not speech`() {
        val e = SpeechEndpointer(CEILING)
        assertEquals(End.NO_SPEECH, e.feed(join(room(1000, 100), voice(40, 8000), room(4000, 100))))
        assertFalse(e.usable)
    }

    @Test
    fun `nobody speaking ends the turn after four seconds`() {
        val e = SpeechEndpointer(CEILING)
        assertEquals(End.NO_SPEECH, e.feed(room(6000, 100)))
        assertEquals(SpeechEndpointer.START_TIMEOUT_MS, e.elapsedMs)
    }

    @Test
    fun `a pause to think in a long command does not end it`() {
        val e = SpeechEndpointer(CEILING)
        val audio = join(room(500, 100), voice(3000, 3000), room(1100, 100), voice(1500, 3000), room(3000, 100))
        assertEquals(End.FINISHED, e.feed(audio))
        // Ended after the second part, not in the pause.
        assertTrue(e.elapsedMs > 500 + 3000 + 1100 + 1500)
        assertTrue(e.speechMs >= 4000)
    }

    @Test
    fun `a quick command still ends at a short pause`() {
        val e = SpeechEndpointer(CEILING)
        e.feed(join(room(500, 100), voice(1000, 3000), room(900, 100), voice(1000, 3000)))
        assertEquals(End.FINISHED, e.end)
        assertTrue(e.elapsedMs < 500 + 1000 + 900)
    }

    @Test
    fun `a voice quieter than the calibration still counts in a quiet room`() {
        // RMS about 280: under the old fixed 450 this was "silence" throughout.
        val e = SpeechEndpointer(CEILING)
        assertEquals(End.FINISHED, e.feed(join(room(1000, 60), voice(1500, 400), room(1500, 60))))
        assertTrue(e.usable)
    }

    @Test
    fun `fading word endings do not cut a sentence`() {
        val words = (1..8).map { join(voice(250, 3000), voice(200, 700)) }.toTypedArray()
        val e = SpeechEndpointer(CEILING)
        e.feed(join(room(1000, 100), *words, room(2000, 100)))
        assertEquals(End.FINISHED, e.end)
        assertTrue(e.elapsedMs > 1000 + 8 * 450)
    }

    @Test
    fun `in a noisy room the calibrated level holds`() {
        val e = SpeechEndpointer(CEILING)
        // Noise around RMS 230: under the calibration, so not speech.
        assertEquals(End.NO_SPEECH, e.feed(room(5000, 400)))
        val talk = SpeechEndpointer(CEILING)
        assertEquals(End.FINISHED, talk.feed(join(room(1000, 400), voice(1000, 4000), room(1500, 400))))
    }

    @Test
    fun `the read size changes nothing`() {
        val audio = join(room(700, 100), voice(1200, 3000), room(500, 100), voice(300, 3000), room(1500, 100))
        val ends = listOf(320, 2048, 777, 4096, 160).map { chunk ->
            SpeechEndpointer(CEILING).also { it.feed(audio, chunk) }.let { it.elapsedMs to it.speechMs }
        }
        assertEquals(1, ends.toSet().size)
    }

    @Test
    fun `a long message is cut at thirty seconds, not fifteen`() {
        val e = SpeechEndpointer(CEILING)
        assertNull(e.feed(voice(20_000, 3000)))
        assertEquals(End.MAX_DURATION, e.feed(voice(15_000, 3000)))
        assertEquals(SpeechEndpointer.MAX_MS, e.elapsedMs)
    }

    @Test
    fun `a command begun with the name is already speech`() {
        val e = SpeechEndpointer(CEILING, leadMs = 600)
        assertTrue(e.speechStarted)
        assertEquals(End.FINISHED, e.feed(room(1200, 100)))
        assertTrue(e.usable)
    }

    @Test
    fun `stop ends it at once`() {
        val e = SpeechEndpointer(CEILING)
        e.feed(join(room(300, 100), voice(500, 3000)))
        assertEquals(End.STOPPED, e.stop())
        assertEquals(End.STOPPED, e.push(voice(100, 3000)))
    }
}

class WhisperArtifactsTest {

    @Test
    fun `subtitle credits from a song are not a command`() {
        val song = "Субтитры создавал DimaTorzok Субтитры создавал DimaTorzok Продолжение следует..."
        assertEquals("", WhisperArtifacts.clean(song))
        assertEquals("", WhisperArtifacts.clean("Thank you. Thank you. Thank you."))
        assertEquals("", WhisperArtifacts.clean("Спасибо за просмотр!"))
    }

    @Test
    fun `real words around a credit are kept`() {
        assertEquals("Включи музыку.", WhisperArtifacts.clean("Включи музыку. Субтитры сделал DimaTorzok"))
    }

    @Test
    fun `ordinary thanks and talk about subtitles stay`() {
        assertEquals("Спасибо", WhisperArtifacts.clean("Спасибо"))
        assertEquals("Thank you, Friday", WhisperArtifacts.clean("Thank you, Friday"))
        assertEquals("Включи субтитры", WhisperArtifacts.clean("Включи субтитры"))
    }
}

class SpeakerGateTrustTest {

    private var now = 0L
    private val embedder = mockk<SpeakerEmbedder>()
    private val gate = SpeakerGate(embedder) { now }

    init {
        val profile = VoiceProfile.Profile(floatArrayOf(1f, 0f), threshold = 0.6f, cohesion = 0.8f, sampleCount = 5)
        gate.load(VoiceProfile.serialise(profile))
        // Every utterance scores 0.55: a little under the owner's threshold.
        every { embedder.embed(any()) } returns floatArrayOf(0.55f, sqrt(1 - 0.55f * 0.55f))
    }

    private val audio = FloatArray(RATE) { (3000 * sin(2 * PI * 180 * it / RATE)).toFloat() }

    @Test
    fun `right after the owner's wake word, a command is given the benefit`() {
        assertTrue(gate.admitsWake(audio))
        now += 5_000
        assertTrue(gate.commandCheck()!!(audio))
    }

    @Test
    fun `without a recent wake word, the full threshold applies`() {
        assertFalse(gate.commandCheck()!!(audio))
        assertTrue(gate.admitsWake(audio))
        now += 31_000
        assertFalse(gate.commandCheck()!!(audio))
    }
}
