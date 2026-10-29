package com.friday.ai.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import com.friday.ai.core.voice.PlaybackEvent
import com.friday.ai.core.voice.PlaybackPhase
import com.friday.ai.core.voice.VoiceEngine

/**
 * Friday's voice.
 *
 * Primary path is Microsoft Edge's neural voices over the same free websocket
 * the Edge browser uses (no API key) — they sound best. But that endpoint is
 * someone else's and has already locked us out once, so every utterance that
 * fails to synthesize falls back to [SystemTtsSpeaker], which runs on-device
 * and offline. The assistant never goes mute because a third party changed
 * their mind.
 *
 * Supports incremental speech: callers push sentences in as the LLM streams
 * them, and each is synthesized while the previous one is still playing, so
 * Friday starts talking almost immediately instead of after the whole answer
 * is generated.
 */
class EdgeTtsSpeaker(private val context: Context) {

    companion object {
        private const val TAG = "EdgeTtsSpeaker"
        private const val HTTP_FORBIDDEN = 403
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var player: MediaPlayer? = null
    private var playerOwner: SpeechSession? = null
    private val playbackLock = Any()

    @Volatile
    private var activeSession: SpeechSession? = null

    /** Offline safety net, created lazily so the engine only spins up if needed. */
    private val systemTts: SystemTtsSpeaker by lazy { SystemTtsSpeaker(context) }

    /**
     * An in-progress spoken response. Text is pushed in as it becomes
     * available; synthesis and playback run as a pipeline behind it.
     */
    inner class SpeechSession internal constructor(private val onPlayback: (PlaybackEvent) -> Unit) {

        private val pipeline = SpeechPipeline(scope, SpeechPipeline.Output(
            synthesize = { text -> phrases.read(text) ?: synthesize(text) },
            play = { audio, started -> playAudio(audio, this, started) },
            fallback = { text, started -> systemTts.speak(text, started) },
            stop = { releasePlayer(this) }
        )) { event -> if (activeSession === this) onPlayback(event) }

        internal fun event(phase: PlaybackPhase, engine: VoiceEngine = VoiceEngine.EDGE) {
            if (activeSession === this) onPlayback(PlaybackEvent(phase, engine))
        }

        /** Queues one more piece of text to speak. Safe to call repeatedly. */
        fun offer(text: String) {
            pipeline.offer(text)
        }

        /** Signals that no more text is coming. */
        fun endInput() {
            pipeline.endInput()
        }

        /** Suspends until everything queued has finished playing. */
        suspend fun awaitCompletion(): Boolean = pipeline.awaitCompletion()

        fun cancel() {
            pipeline.cancel()
        }
    }

    /**
     * Starts an incremental spoken response. Cancels whatever was being said
     * before, so the assistant never talks over itself.
     */
    fun beginSpeech(onPlayback: (PlaybackEvent) -> Unit = {}): SpeechSession {
        stop()
        return SpeechSession(onPlayback).also {
            activeSession = it
            it.event(PlaybackPhase.QUEUED)
        }
    }

    /**
     * Speaks a single complete utterance, invoking [onDone] once playback
     * finishes — or immediately if synthesis fails, so callers that resume
     * listening in the callback never hang. The callback belongs to this
     * call: a later [speakObserved] cannot steal it.
     */
    fun speakObserved(text: String, onPlayback: (PlaybackEvent) -> Unit, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        val session = beginSpeech(onPlayback)
        session.offer(text)
        session.endInput()
        scope.launch {
            session.awaitCompletion()
            onDone?.invoke()
        }
    }

    fun stop() {
        activeSession?.cancel()
        activeSession = null
        releasePlayer()
        systemTts.stop()
    }

    fun destroy() {
        stop()
        systemTts.destroy()
        scope.cancel()
    }

    /**
     * Synthesises fixed phrases ahead of time, so speaking them later costs no
     * network round-trip. Meant for the few things Friday says constantly —
     * the greeting after her name above all, where the wait was most felt.
     */
    fun prefetch(texts: Collection<String>) {
        scope.launch {
            for (text in texts) {
                if (phrases.has(text)) continue
                val audio = runCatching { synthesize(text) }.getOrNull()
                if (audio != null && audio.isNotEmpty()) phrases.write(text, audio)
            }
        }
    }

    private val phrases by lazy { PhraseCache(File(context.filesDir, "tts_cache")) }

    private fun releasePlayer(owner: SpeechSession? = null) = synchronized(playbackLock) {
        if (owner != null && playerOwner !== owner) return@synchronized
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {
            } finally {
                try { it.release() } catch (_: Exception) {}
            }
        }
        player = null
        playerOwner = null
    }

    /** One synthesis; after a 403 caused by a wrong phone clock, one more try with the corrected clock. */
    private suspend fun synthesize(text: String): ByteArray? {
        val first = attempt(text)
        if (first !is Attempt.Refused || !first.clockWasOff) return (first as? Attempt.Audio)?.mp3
        Log.i(TAG, "Phone clock was off; retrying with the server's time")
        return (attempt(text) as? Attempt.Audio)?.mp3
    }

    private sealed interface Attempt {
        class Audio(val mp3: ByteArray) : Attempt
        class Refused(val clockWasOff: Boolean) : Attempt
        data object Failed : Attempt
    }

    private suspend fun attempt(text: String): Attempt =
        suspendCancellableCoroutine { cont ->
            val requestId = UUID.randomUUID().toString().replace("-", "")
            val connectionId = UUID.randomUUID().toString().replace("-", "")
            val now = EdgeClock.now()
            val voice = EdgeTtsProtocol.detectVoice(text)

            val request = Request.Builder()
                .url(EdgeTtsProtocol.buildUrl(connectionId, now))
                .addHeader("User-Agent", EdgeTtsProtocol.USER_AGENT)
                .addHeader("Origin", EdgeTtsProtocol.ORIGIN)
                .addHeader("Pragma", "no-cache")
                .addHeader("Cache-Control", "no-cache")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Cookie", EdgeTtsProtocol.muidCookie())
                .build()

            val audioBuffer = ByteArrayOutputStream()

            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(EdgeTtsProtocol.buildConfigMessage(requestId, now))
                    webSocket.send(EdgeTtsProtocol.buildSsmlMessage(requestId, now, voice, text))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val data = bytes.toByteArray()
                    val start = EdgeTtsProtocol.audioPayloadStart(data)
                    if (start in 0..data.size) {
                        audioBuffer.write(data, start, data.size - start)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("turn.end")) {
                        webSocket.close(1000, null)
                        cont.resumeIfActive(Attempt.Audio(audioBuffer.toByteArray()))
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    Log.e(TAG, "Websocket failed${code?.let { " (HTTP $it)" } ?: ""}: ${t.message}")
                    if (code == HTTP_FORBIDDEN) {
                        val clockWasOff = EdgeClock.learn(response.header("Date"), now)
                        if (!clockWasOff) {
                            Log.e(TAG, "403 with a correct clock: Microsoft changed the handshake again " +
                                "(token algorithm, SEC_MS_GEC_VERSION or USER_AGENT in EdgeTtsProtocol).")
                        }
                        cont.resumeIfActive(Attempt.Refused(clockWasOff))
                    } else {
                        cont.resumeIfActive(Attempt.Failed)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    cont.resumeIfActive(Attempt.Audio(audioBuffer.toByteArray()))
                }
            })

            cont.invokeOnCancellation { ws.cancel() }
        }

    private fun CancellableContinuation<Attempt>.resumeIfActive(value: Attempt) {
        if (isActive) resume(value)
    }

    private suspend fun playAudio(mp3: ByteArray, owner: SpeechSession, onStarted: () -> Unit): Boolean {
        val file = File(context.cacheDir, "tts_${System.nanoTime()}.mp3")
        try {
            file.writeBytes(mp3)
            return suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer()
                val finished = AtomicBoolean()
                fun finish(success: Boolean) {
                    if (finished.compareAndSet(false, true) && cont.isActive) cont.resume(success)
                }
                try {
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    mp.setDataSource(file.absolutePath)
                    mp.setOnCompletionListener { finish(true) }
                    mp.setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                        finish(false)
                        true
                    }
                    mp.prepare()
                    synchronized(playbackLock) {
                        if (cont.isActive && activeSession === owner) {
                            player = mp
                            playerOwner = owner
                            mp.start()
                            onStarted()
                        } else {
                            mp.release()
                            finish(false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Playback setup failed: ${e.message}")
                    mp.release()
                    finish(false)
                }
                cont.invokeOnCancellation { releasePlayer(owner) }
            }
        } finally {
            releasePlayer(owner)
            file.delete()
        }
    }
}

/** Pre-recorded audio for fixed phrases, one file per voice and text. */
private class PhraseCache(private val dir: File) {

    fun has(text: String) = file(text).exists()

    fun read(text: String): ByteArray? =
        file(text).takeIf { it.exists() }?.let { runCatching { it.readBytes() }.getOrNull() }

    fun write(text: String, audio: ByteArray) {
        runCatching {
            dir.mkdirs()
            file(text).writeBytes(audio)
        }.onFailure { Log.w("PhraseCache", "Could not cache a phrase: ${it.message}") }
    }

    private fun file(text: String) = File(dir, EdgeTtsProtocol.cacheKey(EdgeTtsProtocol.detectVoice(text), text))
}
