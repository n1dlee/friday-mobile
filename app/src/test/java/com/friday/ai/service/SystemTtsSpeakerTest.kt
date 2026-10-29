package com.friday.ai.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SystemTtsSpeakerTest {
    @Test
    fun `cancelling old system speech cannot stop replacement or accept its old completion`() = runTest {
        val engine = mockk<TextToSpeech>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val listeners = mutableListOf<UtteranceProgressListener>()
        val ids = mutableListOf<String>()
        every { engine.setOnUtteranceProgressListener(any()) } answers {
            listeners += firstArg<UtteranceProgressListener>()
            TextToSpeech.SUCCESS
        }
        every { engine.speak(any<CharSequence>(), any(), any(), any()) } answers {
            ids += arg<String>(3)
            TextToSpeech.SUCCESS
        }
        val speaker = SystemTtsSpeaker(context) { _, listener ->
            listener.onInit(TextToSpeech.SUCCESS)
            engine
        }
        val old = async { speaker.speak("old") }
        runCurrent()
        var starts = 0
        val next = async { speaker.speak("replacement") { starts++ } }
        runCurrent()
        old.cancelAndJoin()
        verify(exactly = 0) { engine.stop() }
        listeners.last().onStart(ids.first())
        listeners.last().onDone(ids.first())
        runCurrent()
        assertEquals(0, starts)
        assertFalse(next.isCompleted)
        listeners.last().onStart(ids.last())
        listeners.last().onDone(ids.last())
        runCurrent()
        assertEquals(1, starts)
        assertTrue(next.await())
        speaker.destroy()
    }
}
