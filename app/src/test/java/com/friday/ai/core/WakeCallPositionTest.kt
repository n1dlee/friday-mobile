package com.friday.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why the position rule stays strict.
 *
 * Relaxing it looked attractive — a hesitation before the name really does get
 * a genuine call rejected — but grammar-constrained decoding collapses every
 * out-of-grammar word to `[unk]`, hesitation and prepositions alike. So
 * "ээ, пятница" and "…в пятницу" reach this code as the same string, and no
 * rule here can separate them. Waking up inside someone's sentence is the
 * worse of the two failures, so the strict rule wins and the missed calls are
 * fixed upstream in [VoiceGate] instead.
 */
class WakeCallPositionTest {

    @Test
    fun `an unknown token before the name does not wake it`() {
        // Both of these arrive here identically, which is the whole point.
        assertFalse(WakePhrases.isWakeCall("[unk] пятница"))
        assertFalse(WakePhrases.isWakeCall("[unk] [unk] пятница"))
    }

    @Test
    fun `the weekday is not a wake call`() {
        assertFalse(WakePhrases.isWakeCall("встретимся в пятницу"))
        assertFalse(WakePhrases.isWakeCall("сегодня пятница"))
        assertFalse(WakePhrases.isWakeCall("в эту пятницу я занят"))
    }

    @Test
    fun `a call opening the utterance works`() {
        assertTrue(WakePhrases.isWakeCall("пятница"))
        assertTrue(WakePhrases.isWakeCall("пятница какая погода"))
        assertTrue(WakePhrases.isWakeCall("фрайдей"))
    }

    @Test
    fun `one attention word in front is allowed`() {
        assertTrue(WakePhrases.isWakeCall("окей пятница"))
        assertTrue(WakePhrases.isWakeCall("слушай пятница включи свет"))
        assertTrue(WakePhrases.isWakeCall("hey friday"))
    }

    @Test
    fun `two attention words are not`() {
        // Beyond one, this stops being a call and starts being a sentence.
        assertFalse(WakePhrases.isWakeCall("окей слушай пятница"))
    }

    @Test
    fun `nothing at all is not a wake call`() {
        assertFalse(WakePhrases.isWakeCall(""))
        assertFalse(WakePhrases.isWakeCall("   "))
        assertFalse(WakePhrases.isWakeCall("какая погода завтра"))
    }

    @Test
    fun `language follows the name that matched`() {
        assertEquals(WakePhrases.Language.RUSSIAN, WakePhrases.detectLanguage("пятница"))
        assertEquals(WakePhrases.Language.RUSSIAN, WakePhrases.detectLanguage("окей пятница"))
        assertEquals(WakePhrases.Language.ENGLISH, WakePhrases.detectLanguage("hey friday"))
        assertEquals(null, WakePhrases.detectLanguage("встретимся в пятницу"))
    }
}
