package com.friday.ai.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the real matcher on real Vosk output shapes. The previous version
 * of this test re-declared its own copy of the wake-phrase list, so it would
 * have stayed green even if the engine's list was emptied.
 */
class WakeWordDetectionTest {

    private val engine = WakeWordEngine()

    private fun finalResult(text: String) = """{"text":"$text"}"""
    private fun partialResult(text: String) = """{"partial":"$text"}"""

    @Test
    fun `detects the wake word in a final result`() {
        assertTrue(engine.containsWakePhrase(finalResult("фрайдей")))
        assertTrue(engine.containsWakePhrase(finalResult("привет фрайдей")))
    }

    @Test
    fun `detects the wake word in a partial result`() {
        // Partials are what make detection feel instant — they arrive before
        // the utterance is finished.
        assertTrue(engine.containsWakePhrase(partialResult("фрайди")))
        assertTrue(engine.containsWakePhrase(partialResult("окей пятница")))
    }

    @Test
    fun `matching is case insensitive`() {
        assertTrue(engine.containsWakePhrase(finalResult("ФРАЙДЕЙ")))
        assertTrue(engine.containsWakePhrase(finalResult("Окей Пятница")))
    }

    @Test
    fun `bare russian name wakes the assistant`() {
        // Both names are first-class wake words: "Friday" and "Пятница".
        assertTrue(engine.containsWakePhrase(finalResult("пятница")))
        assertTrue(engine.containsWakePhrase(finalResult("пятница открой камеру")))
    }

    @Test
    fun `the weekday used mid sentence still does not trigger`() {
        // "пятница" doubles as the ordinary word for the day, so it only
        // counts when it opens the utterance — otherwise ordinary talk about
        // Friday would wake the assistant.
        assertFalse(engine.containsWakePhrase(finalResult("встретимся в пятницу")))
        assertFalse(engine.containsWakePhrase(finalResult("сегодня пятница")))
    }

    @Test
    fun `detects the wake word mid utterance`() {
        assertTrue(engine.containsWakePhrase(finalResult("слушай пятница включи свет")))
    }

    @Test
    fun `does not trigger on unrelated speech`() {
        assertFalse(engine.containsWakePhrase(finalResult("привет как дела")))
        assertFalse(engine.containsWakePhrase(finalResult("what is the weather today")))
        assertFalse(engine.containsWakePhrase(partialResult("открой камеру")))
    }

    @Test
    fun `empty and silent results do not trigger`() {
        assertFalse(engine.containsWakePhrase(finalResult("")))
        assertFalse(engine.containsWakePhrase(partialResult("")))
        assertFalse(engine.containsWakePhrase("""{"text":""}"""))
    }

    @Test
    fun `malformed json is treated as no match rather than crashing`() {
        // Vosk output is parsed on the audio thread; a throw here would kill
        // wake-word listening entirely.
        assertFalse(engine.containsWakePhrase("not json at all"))
        assertFalse(engine.containsWakePhrase(""))
        assertFalse(engine.containsWakePhrase("{unterminated"))
    }
}
