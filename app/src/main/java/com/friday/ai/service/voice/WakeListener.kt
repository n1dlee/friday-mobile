package com.friday.ai.service.voice

import android.content.Context
import android.util.Log
import com.friday.ai.service.VoskModelManager
import com.friday.ai.service.WakeWordEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The always-on microphone: waits for "Пятница", and only passes it on when
 * the voice belongs to the owner.
 *
 * A stranger's wake word gets no reaction at all — not a refusal, which
 * would only tell them an assistant is listening.
 */
class WakeListener(
    private val context: Context,
    private val scope: CoroutineScope,
    private val gate: SpeakerGate,
    private val notifier: ServiceNotifier,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    private companion object {
        const val TAG = "WakeListener"
        const val IDLE_STATUS = "Listening for \"Friday\""
    }

    /**
     * Called once the owner is confirmed, with the words the call was heard
     * in, whether they were still speaking, and the audio up to that point
     * (for a command said in the same breath).
     */
    var onWake: ((heard: String, continuing: Boolean, audio: FloatArray) -> Unit)? = null

    /** Where the on-device model lives; the offline recogniser shares it. */
    var modelPath: String? = null
        private set

    private var engine: WakeWordEngine? = null
    private var job: Job? = null

    /**
     * Fetches and loads the on-device model. Slow the first time — it may
     * download — so it runs off the main thread and reports through the
     * notification.
     *
     * @return true once listening can start
     */
    suspend fun initialise(energyThreshold: Double?): Boolean = withContext(io) {
        val models = VoskModelManager(context)
        if (!models.isModelReady()) {
            notifier.update("Downloading voice model...")
            models.downloadModel()
        }
        val path = models.getModelPath() ?: run {
            notifier.update("Voice model unavailable")
            return@withContext false
        }
        val e = WakeWordEngine()
        if (!e.loadModel(path)) {
            notifier.update("Failed to load voice model")
            return@withContext false
        }
        energyThreshold?.let { e.energyThreshold = it }
        e.onWakeWordDetected = { heard, audio, continuing -> onDetected(heard, audio, continuing) }
        engine = e
        modelPath = path
        true
    }

    /**
     * Starts (or restarts) listening without touching the status line. The
     * previous session is stopped and waited for first: two sessions at once
     * meant two recorders fighting over the microphone.
     */
    fun listen() {
        val e = engine ?: return
        val previous = job
        job = scope.launch(io) {
            e.pause()
            previous?.cancelAndJoin()
            e.resume()
            e.startListening()
        }
    }

    /** Stops listening and waits until the microphone is free for someone else. */
    suspend fun stop() {
        engine?.pause()
        job?.cancelAndJoin()
        job = null
    }

    /** Back to idle listening after a conversation. */
    fun resume() {
        notifier.update(IDLE_STATUS)
        listen()
    }

    fun destroy() {
        job?.cancel()
        engine?.destroy()
        engine = null
    }

    /** Runs on the recording thread: the speaker check is heavy and must stay off main. */
    private fun onDetected(heard: String, audio: FloatArray, continuing: Boolean) {
        if (!gate.admitsWake(audio)) {
            Log.i(TAG, "Wake word ignored: not the enrolled speaker")
            resume()
            return
        }
        onWake?.invoke(heard, continuing, audio)
    }
}
