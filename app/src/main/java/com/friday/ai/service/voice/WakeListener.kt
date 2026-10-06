package com.friday.ai.service.voice

import android.content.Context
import android.util.Log
import com.friday.ai.service.VoskModelManager
import com.friday.ai.service.WakeWordEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    /** Called with the words the wake call was heard in, once the owner is confirmed. */
    var onWake: ((heard: String) -> Unit)? = null

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
        e.onWakeWordDetected = { heard, audio -> onDetected(heard, audio) }
        engine = e
        modelPath = path
        true
    }

    /** Starts (or restarts) listening without touching the status line. */
    fun listen() {
        val e = engine ?: return
        e.resume()
        job?.cancel()
        job = scope.launch(io) { e.startListening() }
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
    private fun onDetected(heard: String, audio: FloatArray) {
        if (!gate.admitsWake(audio)) {
            Log.i(TAG, "Wake word ignored: not the enrolled speaker")
            resume()
            return
        }
        onWake?.invoke(heard)
    }
}
