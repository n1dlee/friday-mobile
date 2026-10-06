package com.friday.ai.service

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.friday.ai.core.PcmAudio
import com.friday.ai.core.RollingAudio
import com.friday.ai.core.VoiceGate
import com.friday.ai.core.WakePhrases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class WakeWordEngine {

    companion object {
        private const val TAG = "WakeWordEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val DEFAULT_ENERGY_THRESHOLD = 150.0

        /**
         * How much audio to keep for speaker verification. Long enough that a
         * wake word plus its run-up always fits; short enough that the buffer
         * is trivial.
         */
        private const val RECENT_AUDIO_SECONDS = 3

    }

    enum class EngineState { IDLE, LOADING, LISTENING, PAUSED, ERROR }

    private val _state = MutableStateFlow(EngineState.IDLE)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var effects: AudioEffects? = null
    private var detectedAudio: FloatArray = FloatArray(0)
    /**
     * Receives the text the wake call was matched in — so the reply can match
     * its language — and the audio it was heard in, so the caller can check
     * whose voice it was. The audio is 16 kHz mono at int16 scale.
     */
    var onWakeWordDetected: ((String, FloatArray) -> Unit)? = null
    var energyThreshold: Double = DEFAULT_ENERGY_THRESHOLD

    fun loadModel(modelPath: String): Boolean {
        return try {
            _state.value = EngineState.LOADING
            model = Model(modelPath)
            // Grammar-constrained recognition: instead of transcribing free
            // speech (which requires guessing among the model's entire
            // vocabulary), the decoder only has to pick among these phrases
            // plus "[unk]". This is dramatically more accurate and lower
            // latency for wake-word spotting than substring-matching a
            // free-form transcript.
            val grammar = JSONArray(WakePhrases.grammarVocabulary() + "[unk]").toString()
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), grammar)
            _state.value = EngineState.IDLE
            Log.i(TAG, "Vosk model loaded from $modelPath with grammar: $grammar")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Vosk model: ${e.message}")
            _state.value = EngineState.ERROR
            false
        }
    }

    suspend fun startListening() = withContext(Dispatchers.IO) {
        if (model == null || recognizer == null) {
            Log.e(TAG, "Model not loaded")
            return@withContext
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            .coerceAtLeast(4096)

        var detected = false
        var detectedIn = ""
        // Everything heard in the last few seconds. On detection this is
        // handed over for speaker verification: the wake word is always
        // somewhere inside it, including the part that preceded the gate
        // opening.
        val recent = RollingAudio(SAMPLE_RATE * RECENT_AUDIO_SECONDS)

        try {
            // VOICE_RECOGNITION gets the device's echo cancellation, which
            // matters when this listens while Friday is speaking (barge-in) —
            // otherwise it hears the assistant's own voice through the speaker.
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                _state.value = EngineState.ERROR
                return@withContext
            }

            // The phone's own DSP. Road noise is exactly the stationary kind
            // hardware suppression removes, and none of it runs unless asked.
            audioRecord?.audioSessionId?.let { effects = AudioEffects.attach(it) }

            audioRecord?.startRecording()
            _state.value = EngineState.LISTENING
            Log.i(TAG, "Silent wake word listening started")

            val buffer = ShortArray(bufferSize / 2)
            val frameMs = (buffer.size * 1000 / SAMPLE_RATE).coerceAtLeast(1)
            val gate = VoiceGate(energyThreshold, frameMs)

            // The last few frames before the gate opens. Without these the
            // decoder joins the word already in progress and "пятница" arrives
            // as "ница", which matches nothing.
            val preRoll = ArrayDeque<ByteArray>(gate.preRollFrames)

            while (isActive && _state.value == EngineState.LISTENING) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read <= 0) continue

                val bytes = PcmAudio.toLittleEndian(buffer, read)
                recent.append(buffer, read)
                val decision = gate.onFrame(PcmAudio.rms(buffer, read))

                if (decision.resetDecoder) {
                    // A real gap between utterances. Clearing here — and only
                    // here — keeps the partial transcript from accumulating
                    // fragments of every earlier attempt.
                    recognizer?.reset()
                }

                if (!decision.feed) {
                    preRoll.addLast(bytes)
                    while (preRoll.size > gate.preRollFrames) preRoll.removeFirst()
                    continue
                }

                var json: String? = null
                if (decision.flushPreRoll) {
                    preRoll.forEach { feed(it) }
                    preRoll.clear()
                }
                json = feed(bytes)

                if (json != null && containsWakePhrase(json)) {
                    detected = true
                    detectedIn = heardText(json)
                    detectedAudio = recent.snapshot()
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Listening error: ${e.message}")
        } finally {
            // Release the microphone before anyone else is told they may use
            // it — otherwise the command recorder races this teardown and can
            // fail to open the mic at all.
            stopAudioRecord()
        }

        if (detected) {
            _state.value = EngineState.PAUSED
            recognizer?.reset()
            Log.i(TAG, "Wake word detected; microphone released, handing over")
            onWakeWordDetected?.invoke(detectedIn, detectedAudio)
        }
    }

    fun pause() {
        _state.value = EngineState.PAUSED
        stopAudioRecord()
    }

    fun resume() {
        _state.value = EngineState.IDLE
    }

    fun destroy() {
        _state.value = EngineState.IDLE
        stopAudioRecord()
        try {
            recognizer?.close()
            model?.close()
        } catch (_: Exception) {}
        recognizer = null
        model = null
    }

    private fun stopAudioRecord() {
        // Before the AudioRecord: an effect outliving its session leaks.
        effects?.release()
        effects = null
        audioRecord.stopAndRelease(TAG)
        audioRecord = null
    }

    /**
     * Pure check against a Vosk result — no side effects, so the caller stays
     * in control of teardown ordering (see the microphone handover above).
     */
    internal fun containsWakePhrase(jsonResult: String): Boolean {
        val text = heardText(jsonResult)
        return if (text.isBlank()) false
        else WakePhrases.isWakeCall(text)
            .also { if (it) Log.i(TAG, "Wake call matched in: $text") }
    }

    /** The words behind a Vosk result, whether final or partial. */
    internal fun heardText(jsonResult: String): String = try {
        val obj = JSONObject(jsonResult)
        (obj.optString("text", "") + " " + obj.optString("partial", "")).trim()
    } catch (_: Exception) {
        ""
    }

    /** Hands one frame to the decoder and returns whatever it has so far. */
    private fun feed(bytes: ByteArray): String? =
        if (recognizer?.acceptWaveForm(bytes, bytes.size) == true) recognizer?.result
        else recognizer?.partialResult


}
