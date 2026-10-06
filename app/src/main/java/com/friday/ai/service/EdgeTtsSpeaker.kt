package com.friday.ai.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

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
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var activeSession: SpeechSession? = null

    /** Offline safety net, created lazily so the engine only spins up if needed. */
    private val systemTts: SystemTtsSpeaker by lazy { SystemTtsSpeaker(context) }

    /** One queued utterance: either synthesized audio, or text to fall back on. */
    private sealed interface Playable {
        @JvmInline value class Audio(val bytes: ByteArray) : Playable
        @JvmInline value class Fallback(val text: String) : Playable
    }

    /**
     * An in-progress spoken response. Text is pushed in as it becomes
     * available; synthesis and playback run as a pipeline behind it.
     */
    inner class SpeechSession internal constructor() {

        private val pendingText = Channel<String>(Channel.UNLIMITED)

        // Small buffer: stay a sentence or two ahead without synthesizing an
        // entire long answer that the user may interrupt.
        private val readyAudio = Channel<Playable>(capacity = 2)

        private val finished = CompletableDeferred<Unit>()

        @Volatile
        private var spokeAnything = false

        private val synthJob: Job = scope.launch {
            try {
                for (text in pendingText) {
                    val audio = phrases.read(text) ?: runCatching { synthesize(text) }
                        .onFailure { Log.e(TAG, "Synthesis failed: ${it.message}") }
                        .getOrNull()
                    // Queue either way so utterances still play in order.
                    if (audio != null && audio.isNotEmpty()) {
                        readyAudio.send(Playable.Audio(audio))
                    } else {
                        Log.w(TAG, "Edge gave no audio; using on-device voice for: ${text.take(40)}")
                        readyAudio.send(Playable.Fallback(text))
                    }
                }
            } finally {
                readyAudio.close()
            }
        }

        private val playJob: Job = scope.launch {
            try {
                for (item in readyAudio) {
                    when (item) {
                        is Playable.Audio -> playAudio(item.bytes)
                        is Playable.Fallback -> systemTts.speak(item.text)
                    }
                    spokeAnything = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback stopped: ${e.message}")
            } finally {
                finished.complete(Unit)
            }
        }

        /** Queues one more piece of text to speak. Safe to call repeatedly. */
        fun offer(text: String) {
            if (text.isBlank()) return
            pendingText.trySend(text)
        }

        /** Signals that no more text is coming. */
        fun endInput() {
            pendingText.close()
        }

        /** Suspends until everything queued has finished playing. */
        suspend fun awaitCompletion() {
            finished.await()
        }

        /** True if at least one utterance actually played. */
        fun spoke(): Boolean = spokeAnything

        fun cancel() {
            pendingText.close()
            synthJob.cancel()
            playJob.cancel()
            releasePlayer()
            finished.complete(Unit)
        }
    }

    /**
     * Starts an incremental spoken response. Cancels whatever was being said
     * before, so the assistant never talks over itself.
     */
    fun beginSpeech(): SpeechSession {
        stop()
        return SpeechSession().also { activeSession = it }
    }

    /**
     * Speaks a single complete utterance, invoking [onDone] once playback
     * finishes — or immediately if synthesis fails, so callers that resume
     * listening in the callback never hang. The callback belongs to this
     * call: a later [speak] cannot steal it.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        val session = beginSpeech()
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

    private fun releasePlayer() {
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {
            } finally {
                try { it.release() } catch (_: Exception) {}
            }
        }
        player = null
    }

    private suspend fun synthesize(text: String): ByteArray? =
        suspendCancellableCoroutine { cont ->
            val requestId = UUID.randomUUID().toString().replace("-", "")
            val connectionId = UUID.randomUUID().toString().replace("-", "")
            val now = System.currentTimeMillis()
            val voice = EdgeTtsProtocol.detectVoice(text)

            val request = Request.Builder()
                .url(EdgeTtsProtocol.buildUrl(connectionId, now))
                .addHeader("User-Agent", EdgeTtsProtocol.USER_AGENT)
                .addHeader("Origin", EdgeTtsProtocol.ORIGIN)
                .addHeader("Pragma", "no-cache")
                .addHeader("Cache-Control", "no-cache")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
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
                        cont.resumeIfActive(audioBuffer.toByteArray())
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    Log.e(TAG, "Websocket failed${code?.let { " (HTTP $it)" } ?: ""}: ${t.message}")
                    if (code == 403) {
                        Log.e(TAG, "403 = Microsoft rejected the client fingerprint. " +
                            "Bump USER_AGENT / SEC_MS_GEC_VERSION in EdgeTtsProtocol.")
                    }
                    cont.resumeIfActive(null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    cont.resumeIfActive(audioBuffer.toByteArray())
                }
            })

            cont.invokeOnCancellation { ws.cancel() }
        }

    private fun CancellableContinuation<ByteArray?>.resumeIfActive(value: ByteArray?) {
        if (isActive) resume(value)
    }

    private suspend fun playAudio(mp3: ByteArray) {
        val file = File(context.cacheDir, "tts_${System.nanoTime()}.mp3")
        try {
            file.writeBytes(mp3)
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer()
                player = mp
                var finished = false
                fun finish() {
                    if (finished) return
                    finished = true
                    if (cont.isActive) cont.resume(Unit)
                }
                try {
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    mp.setDataSource(file.absolutePath)
                    mp.setOnCompletionListener { finish() }
                    mp.setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                        finish()
                        true
                    }
                    mp.prepare()
                    mp.start()
                } catch (e: Exception) {
                    Log.e(TAG, "Playback setup failed: ${e.message}")
                    finish()
                }
                cont.invokeOnCancellation { releasePlayer() }
            }
        } finally {
            releasePlayer()
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
        }.onFailure { Log.w("PhraseCache", "Could not cache '$text': ${it.message}") }
    }

    private fun file(text: String) = File(dir, EdgeTtsProtocol.cacheKey(EdgeTtsProtocol.detectVoice(text), text))
}
