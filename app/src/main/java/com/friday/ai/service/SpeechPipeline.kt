package com.friday.ai.service

import com.friday.ai.core.voice.PlaybackEvent
import com.friday.ai.core.voice.PlaybackPhase
import com.friday.ai.core.voice.VoiceEngine
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** One cancellable synthesis/playback queue, with device operations supplied by its owner. */
internal class SpeechPipeline(
    scope: CoroutineScope,
    private val output: Output,
    private val onEvent: (PlaybackEvent) -> Unit
) {
    class Output(
        val synthesize: suspend (String) -> ByteArray?,
        val play: suspend (ByteArray, () -> Unit) -> Boolean,
        val fallback: suspend (String, () -> Unit) -> Boolean,
        val stop: () -> Unit
    )

    private data class Utterance(val text: String, val audio: ByteArray?)
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val worker = CoroutineScope(scope.coroutineContext + job)
    private val pending = Channel<String>(Channel.UNLIMITED)
    private val ready = Channel<Utterance>(capacity = 2)
    private val finished = CompletableDeferred<Boolean>()
    private val terminal = AtomicBoolean()

    init {
        worker.launch { synthesize() }
        worker.launch { play() }
    }

    fun offer(text: String) {
        if (text.isNotBlank() && !terminal.get()) pending.trySend(text)
    }

    fun endInput() { pending.close() }

    suspend fun awaitCompletion(): Boolean = finished.await()

    fun cancel() {
        event(PlaybackPhase.CANCELLED)
        job.cancel()
        output.stop()
        finished.complete(false)
    }

    private suspend fun synthesize() {
        try {
            for (text in pending) {
                event(PlaybackPhase.SYNTHESIZING)
                val audio = try {
                    output.synthesize(text)?.takeIf { it.isNotEmpty() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    null
                }
                currentCoroutineContext().ensureActive()
                if (audio != null) event(PlaybackPhase.READY)
                ready.send(Utterance(text, audio))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ready.close(e)
        } finally {
            ready.close()
        }
    }

    private suspend fun play() {
        var success = false
        var lastEngine = VoiceEngine.EDGE
        try {
            for (utterance in ready) {
                val engine = if (utterance.audio != null) VoiceEngine.EDGE else VoiceEngine.SYSTEM
                lastEngine = engine
                val started = { event(PlaybackPhase.PLAYING, engine) }
                val played = if (utterance.audio != null) {
                    output.play(utterance.audio, started)
                } else {
                    output.fallback(utterance.text, started)
                }
                currentCoroutineContext().ensureActive()
                if (!played) {
                    event(PlaybackPhase.FAILED, engine)
                    return
                }
                event(PlaybackPhase.WAITING, engine)
            }
            success = true
            event(PlaybackPhase.FINISHED, lastEngine)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            event(PlaybackPhase.FAILED, lastEngine)
        } finally {
            finished.complete(success)
            job.cancel()
        }
    }

    private fun event(phase: PlaybackPhase, engine: VoiceEngine = VoiceEngine.EDGE) {
        val ending = phase == PlaybackPhase.FINISHED || phase == PlaybackPhase.FAILED ||
            phase == PlaybackPhase.CANCELLED
        if (ending) {
            if (terminal.compareAndSet(false, true)) onEvent(PlaybackEvent(phase, engine))
        } else if (!terminal.get()) {
            onEvent(PlaybackEvent(phase, engine))
        }
    }
}
