package com.friday.ai.service.voice

import com.friday.ai.agent.FridayAgent
import com.friday.ai.core.SystemPromptBuilder
import com.friday.ai.core.voice.PlaybackEvent
import com.friday.ai.core.voice.PlaybackPhase
import com.friday.ai.core.voice.VoiceEngine
import com.friday.ai.service.EdgeTtsSpeaker
import com.friday.ai.service.FridayMemory
import com.friday.ai.service.FridayOverlayManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenAnswerTest {
    @Test
    fun `cancel generation propagates and old speech events cannot redraw or resume the turn`() = runTest {
        val agent = mockk<FridayAgent>()
        val prompts = mockk<SystemPromptBuilder>()
        val memory = mockk<FridayMemory>(relaxed = true)
        val session = mockk<VoiceSession>(relaxed = true)
        val speaker = mockk<EdgeTtsSpeaker>()
        val speech = mockk<EdgeTtsSpeaker.SpeechSession>(relaxed = true)
        val overlay = mockk<FridayOverlayManager>(relaxed = true)
        coEvery { memory.getSessionHistory(any(), any()) } returns emptyList()
        coEvery { memory.buildMemoryContext() } returns ""
        every { prompts.build(any(), any(), any()) } returns "system"
        val entered = CompletableDeferred<Unit>()
        every { agent.reply(any(), any(), any()) } returns flow {
            emit("Ответ.")
            entered.complete(Unit)
            awaitCancellation()
        }
        lateinit var event: (PlaybackEvent) -> Unit
        every { speaker.beginSpeech(any()) } answers {
            event = firstArg()
            speech
        }
        var current = true
        var playbackEvents = 0
        val answer = SpokenAnswer(agent, prompts, memory, session, speaker, overlay)
        val job = launch { answer.answer("question", { current }) { playbackEvents++ } }
        entered.await()
        current = false
        job.cancelAndJoin()
        event(PlaybackEvent(PlaybackPhase.PLAYING, VoiceEngine.EDGE))
        assertEquals(0, playbackEvents)
        verify(exactly = 1) { speech.cancel() }
        verify(exactly = 1) { speech.endInput() }
        verify(exactly = 0) { overlay.streaming(any(), true, any()) }
        coVerify(exactly = 0) { session.log(any(), any(), any()) }
    }
}
