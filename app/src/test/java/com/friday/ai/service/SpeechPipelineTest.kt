package com.friday.ai.service

import com.friday.ai.core.voice.PlaybackEvent
import com.friday.ai.core.voice.PlaybackPhase
import com.friday.ai.core.voice.VoiceEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechPipelineTest {
    @Test
    fun `cancel during synthesis never starts playback or fallback`() = runTest {
        var synthesisCancelled = false
        var played = false
        var fallback = false
        val events = mutableListOf<PlaybackEvent>()
        val pipeline = SpeechPipeline(backgroundScope, SpeechPipeline.Output(
            synthesize = { try { awaitCancellation() } finally { synthesisCancelled = true } },
            play = { _, _ -> played = true; true },
            fallback = { _, _ -> fallback = true; true },
            stop = {}
        ), events::add)
        pipeline.offer("sentence")
        runCurrent()
        pipeline.cancel()
        runCurrent()
        assertTrue(synthesisCancelled)
        assertFalse(played)
        assertFalse(fallback)
        assertFalse(pipeline.awaitCompletion())
        assertEquals(1, events.count { it.phase == PlaybackPhase.CANCELLED })
    }

    @Test
    fun `fallback reports actual system start and failure cannot become successful completion`() = runTest {
        val events = mutableListOf<PlaybackEvent>()
        val pipeline = SpeechPipeline(backgroundScope, SpeechPipeline.Output(
            synthesize = { null },
            play = { _, _ -> error("No synthesized audio") },
            fallback = { _, start -> start(); false },
            stop = {}
        ), events::add)
        pipeline.offer("sentence")
        pipeline.endInput()
        runCurrent()
        assertFalse(pipeline.awaitCompletion())
        assertTrue(PlaybackEvent(PlaybackPhase.PLAYING, VoiceEngine.SYSTEM) in events)
        assertEquals(1, events.count { it.phase == PlaybackPhase.FAILED })
        assertFalse(events.any { it.phase == PlaybackPhase.FINISHED })
        pipeline.cancel()
        assertEquals(1, events.count { it.phase in setOf(PlaybackPhase.FAILED, PlaybackPhase.CANCELLED) })
    }

    @Test
    fun `close during playback cancels the player and never starts queued sentences`() = runTest {
        val playing = CompletableDeferred<Unit>()
        var starts = 0
        var stopped = 0
        var cleaned = false
        val pipeline = SpeechPipeline(backgroundScope, SpeechPipeline.Output(
            synthesize = { byteArrayOf(1) },
            play = { _, started ->
                starts++
                started()
                playing.complete(Unit)
                try { awaitCancellation() } finally { cleaned = true }
            },
            fallback = { _, _ -> false },
            stop = { stopped++ }
        ), {})
        pipeline.offer("one")
        pipeline.offer("two")
        pipeline.endInput()
        runCurrent()
        assertTrue(playing.isCompleted)
        pipeline.cancel()
        runCurrent()
        assertEquals(1, starts)
        assertEquals(1, stopped)
        assertTrue(cleaned)
    }

    @Test
    fun `completed sentence is not reported as still playing while generation has stalled`() = runTest {
        val events = mutableListOf<PlaybackEvent>()
        val pipeline = SpeechPipeline(backgroundScope, SpeechPipeline.Output(
            synthesize = { byteArrayOf(1) },
            play = { _, started -> started(); true },
            fallback = { _, _ -> false },
            stop = {}
        ), events::add)
        pipeline.offer("one sentence")
        runCurrent()
        assertEquals(PlaybackPhase.WAITING, events.last().phase)
        assertFalse(events.any { it.phase == PlaybackPhase.FINISHED })
        pipeline.cancel()
    }
}
