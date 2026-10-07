package com.friday.ai.core

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTrimTest {

    private val rate = 16_000

    private fun silence(ms: Int, level: Float = 20f) =
        FloatArray(rate * ms / 1000) { (if (it % 2 == 0) level else -level) }
    private fun tone(ms: Int, amplitude: Float = 3000f) =
        FloatArray(rate * ms / 1000) { (amplitude * sin(2 * PI * 220 * it / rate)).toFloat() }

    @Test
    fun `silence around a word is dropped, the word kept`() {
        val audio = silence(1500) + tone(600) + silence(900)
        val speech = VoiceTrim.speech(audio)
        // 600 ms of voice plus a few frames of padding either side.
        assertTrue("kept ${speech.size}", speech.size in rate * 600 / 1000..rate * 800 / 1000)
    }

    @Test
    fun `only the most recent two seconds of speech are kept`() {
        val audio = tone(1500) + silence(400) + tone(1500)
        assertTrue(VoiceTrim.speech(audio).size <= rate * 2)
    }

    @Test
    fun `too little speech to trust, the clip is left whole`() {
        val audio = silence(2000) + tone(100)
        assertEquals(audio.size, VoiceTrim.speech(audio).size)
    }

    @Test
    fun `loud steady noise isn't mistaken for speech`() {
        val audio = silence(2000, level = 400f) + tone(600, amplitude = 4000f) + silence(500, level = 400f)
        assertTrue(VoiceTrim.speech(audio).size < rate)
    }
}

class StripCallTest {

    @Test
    fun `the name in front of a command goes`() {
        assertEquals("включи музыку в Spotify.", WakePhrases.stripCall("Пятница, включи музыку в Spotify."))
        assertEquals("какая погода", WakePhrases.stripCall("окей пятница какая погода"))
        assertEquals("what's the time", WakePhrases.stripCall("Friday - what's the time"))
    }

    @Test
    fun `a name inside the sentence stays`() {
        assertEquals("напомни в пятницу позвонить", WakePhrases.stripCall("напомни в пятницу позвонить"))
    }

    @Test
    fun `only the name leaves nothing`() {
        assertEquals("", WakePhrases.stripCall("Пятница."))
    }
}
