package com.friday.ai.core

import com.friday.ai.core.WakeCheck.Verdict
import com.friday.ai.core.WakeCheck.Word
import com.friday.ai.service.VoskResults
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RATE = 16_000

/** A voice-like tone: a fundamental and two harmonics, its pitch following [hz] over time. */
private fun voice(seconds: Double, hz: (Double) -> Double): FloatArray {
    var phase = 0.0
    return FloatArray((seconds * RATE).toInt()) { i ->
        phase += 2 * PI * hz(i.toDouble() / RATE) / RATE
        (4000 * sin(phase) + 2000 * sin(2 * phase) + 1000 * sin(3 * phase)).toFloat()
    }
}

class SingingTest {

    @Test
    fun `a held note is sung`() {
        assertTrue(Singing.isSung(voice(0.6) { 220.0 }))
        // Vibrato around one note is still the note.
        assertTrue(Singing.isSung(voice(0.6) { t -> 220.0 * (1 + 0.01 * sin(2 * PI * 5 * t)) }))
    }

    @Test
    fun `speech intonation glides, so it is not singing`() {
        assertFalse(Singing.isSung(voice(0.5) { t -> 260.0 - 160.0 * t }))
    }

    @Test
    fun `short vowels between consonants are not a held note`() {
        val syllables = listOf(140.0, 190.0, 160.0).flatMap { hz ->
            voice(0.12) { hz }.toList() + FloatArray(1200).toList()
        }.toFloatArray()
        assertFalse(Singing.isSung(syllables))
    }

    @Test
    fun `silence has no pitch`() {
        assertEquals(0, Singing.longestHeldNoteMs(FloatArray(RATE)))
        assertEquals(0, Singing.longestHeldNoteMs(FloatArray(100)))
    }
}

class WakeCheckTest {

    private fun name(start: Double, end: Double, conf: Double? = null) = Word("пятница", start, end, conf)

    @Test
    fun `a name said in the usual time is believed`() {
        assertEquals(Verdict.ACCEPT, WakeCheck.judge(listOf(name(1.0, 1.5, 0.95)), 1.8, final = true))
        assertEquals(Verdict.ACCEPT, WakeCheck.judge(listOf(name(1.0, 1.5), Word("включи", 1.6, 1.9)), 1.9, false))
    }

    @Test
    fun `a drawn-out, sung name is not`() {
        assertEquals(Verdict.REJECT, WakeCheck.judge(listOf(name(1.0, 2.4, 0.9)), 2.6, final = true))
        // Already too long while still going: no need to wait for the end.
        assertEquals(Verdict.REJECT, WakeCheck.judge(listOf(name(1.0, 2.2)), 2.2, final = false))
    }

    @Test
    fun `a name still being said is waited for`() {
        assertEquals(Verdict.WAIT, WakeCheck.judge(listOf(name(1.0, 1.4)), 1.45, final = false))
        assertEquals(Verdict.ACCEPT, WakeCheck.judge(listOf(name(1.0, 1.4)), 1.6, final = false))
    }

    @Test
    fun `a guessed name is not believed`() {
        assertEquals(Verdict.REJECT, WakeCheck.judge(listOf(name(1.0, 1.5, 0.3)), 1.8, final = true))
    }

    @Test
    fun `only a name at the start counts`() {
        val words = listOf(Word("[unk]", 0.2, 0.6), name(0.7, 1.2, 0.9))
        assertEquals(Verdict.REJECT, WakeCheck.judge(words, 1.5, final = true))
        assertEquals(Verdict.ACCEPT, WakeCheck.judge(listOf(Word("окей", 0.2, 0.5), name(0.6, 1.1)), 1.4, false))
    }

    @Test
    fun `the engine reads word timing from both result shapes`() {
        val final = """{"result":[{"conf":0.97,"end":1.5,"start":1.02,"word":"пятница"}],"text":"пятница"}"""
        assertEquals(listOf(Word("пятница", 1.02, 1.5, 0.97)), VoskResults.words(final))
        val partial = """{"partial":"окей пятница","partial_result":[{"end":0.5,"start":0.2,"word":"окей"}]}"""
        assertEquals("окей", VoskResults.words(partial).single().text)
        assertTrue(VoskResults.words("""{"partial":"пятница"}""").isEmpty())
    }
}

class WordAudioTest {

    @Test
    fun `a word is cut from the audio by its timing`() {
        // 2 s fed in all; the buffer holds the last second (samples 16000..31999).
        val fed = FloatArray(RATE) { (RATE + it).toFloat() }
        val cut = VoskResults.wordAudio(fed, 2L * RATE, Word("пятница", 1.25, 1.75))
        assertEquals(RATE / 2, cut.size)
        assertEquals(1.25f * RATE, cut.first())
        // Timing outside what is kept: the last second instead.
        assertEquals(RATE, VoskResults.wordAudio(fed, 2L * RATE, Word("пятница", 0.2, 0.7)).size)
    }
}
