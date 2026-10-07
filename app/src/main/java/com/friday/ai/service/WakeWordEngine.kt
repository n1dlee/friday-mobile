package com.friday.ai.service

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.friday.ai.core.PcmAudio
import com.friday.ai.core.RollingAudio
import com.friday.ai.core.Singing
import com.friday.ai.core.VoiceGate
import com.friday.ai.core.WakeCheck
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

        /** A frame this loud relative to the open gate still counts as speech. */
        private const val CLOSE_RATIO = 0.55

    }

    enum class EngineState { IDLE, LOADING, LISTENING, PAUSED, ERROR }

    private val _state = MutableStateFlow(EngineState.IDLE)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    /**
     * Samples handed to [recognizer] since it was made. Vosk times its words
     * against this count — across resets — so it is what places a word in
     * the audio.
     */
    private var fedSamples = 0L

    /** The last name checked for singing, and the answer: the decoder repeats it frame after frame. */
    private var sungCheck: Pair<WakeCheck.Word, Boolean>? = null
    /**
     * Receives the text the wake call was matched in — so the reply can match
     * its language — the audio it was heard in, so the caller can check whose
     * voice it was (16 kHz mono, int16 scale), and whether the speaker was
     * still talking when the name was recognised: "Пятница, включи музыку"
     * said in one breath.
     */
    var onWakeWordDetected: ((heard: String, audio: FloatArray, continuing: Boolean) -> Unit)? = null
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
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), grammar).apply {
                // Start, end and confidence of every word: how a call is told from a song.
                setWords(true)
                setPartialWords(true)
            }
            fedSamples = 0L
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
        var continuing = false
        var detectedAudio = FloatArray(0)
        // Each listening session owns its recorder. A shared field let one
        // session's teardown release the next session's microphone, which
        // left Friday deaf after the first conversation.
        var record: AudioRecord? = null
        var effects: AudioEffects? = null
        // Everything heard in the last few seconds. On detection this is
        // handed over for speaker verification: the wake word is always
        // somewhere inside it, including the part that preceded the gate
        // opening.
        val recent = RollingAudio(SAMPLE_RATE * RECENT_AUDIO_SECONDS)
        // Only what the decoder heard, so a word's timing points into it.
        val fed = RollingAudio(SAMPLE_RATE * RECENT_AUDIO_SECONDS)

        try {
            // VOICE_RECOGNITION gets the device's echo cancellation, which
            // matters when this listens while Friday is speaking (barge-in) —
            // otherwise it hears the assistant's own voice through the speaker.
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                _state.value = EngineState.ERROR
                return@withContext
            }

            // The phone's own DSP. Road noise is exactly the stationary kind
            // hardware suppression removes, and none of it runs unless asked.
            effects = AudioEffects.attach(record.audioSessionId)

            record.startRecording()
            _state.value = EngineState.LISTENING
            Log.i(TAG, "Silent wake word listening started")

            val buffer = ShortArray(bufferSize / 2)
            val frameMs = (buffer.size * 1000 / SAMPLE_RATE).coerceAtLeast(1)
            val gate = VoiceGate(energyThreshold, frameMs)

            // The last few frames before the gate opens. Without these the
            // decoder joins the word already in progress and "пятница" arrives
            // as "ница", which matches nothing.
            val preRoll = ArrayDeque<ShortArray>(gate.preRollFrames)

            while (isActive && _state.value == EngineState.LISTENING) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (read <= 0) continue

                val frame = buffer.copyOf(read)
                recent.append(buffer, read)
                val decision = gate.onFrame(PcmAudio.rms(buffer, read))

                if (decision.resetDecoder) {
                    // A real gap between utterances. Clearing here — and only
                    // here — keeps the partial transcript from accumulating
                    // fragments of every earlier attempt.
                    recognizer?.reset()
                }

                if (!decision.feed) {
                    preRoll.addLast(frame)
                    while (preRoll.size > gate.preRollFrames) preRoll.removeFirst()
                    continue
                }

                if (decision.flushPreRoll) {
                    preRoll.forEach { feed(it, fed) }
                    preRoll.clear()
                }
                val json = feed(frame, fed)

                if (json != null && containsWakePhrase(json) && believable(json, fed.snapshot())) {
                    detected = true
                    detectedIn = heardText(json)
                    detectedAudio = recent.snapshot()
                    // Still loud this very frame: the command is following the name.
                    continuing = PcmAudio.rms(buffer, read) > gate.effectiveThreshold * CLOSE_RATIO
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Listening error: ${e.message}")
        } finally {
            // Release the microphone before anyone else is told they may use
            // it — otherwise the command recorder races this teardown and can
            // fail to open the mic at all.
            effects?.release()
            record.stopAndRelease(TAG)
        }

        if (detected) {
            _state.value = EngineState.PAUSED
            recognizer?.reset()
            Log.i(TAG, "Wake word detected (continuing=$continuing); microphone released, handing over")
            onWakeWordDetected?.invoke(detectedIn, detectedAudio, continuing)
        }
    }

    /** Ends the listening loop; it releases its own microphone as it leaves. */
    fun pause() {
        _state.value = EngineState.PAUSED
    }

    fun resume() {
        _state.value = EngineState.IDLE
    }

    fun destroy() {
        _state.value = EngineState.IDLE
        try {
            recognizer?.close()
            model?.close()
        } catch (_: Exception) {}
        recognizer = null
        model = null
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
    private fun feed(frame: ShortArray, fed: RollingAudio): String? {
        val bytes = PcmAudio.toLittleEndian(frame, frame.size)
        fed.append(frame, frame.size)
        fedSamples += frame.size
        return if (recognizer?.acceptWaveForm(bytes, bytes.size) == true) recognizer?.result
        else recognizer?.partialResult
    }

    /**
     * Whether a matched call holds up: the name said in a normal length, with
     * confidence, and not sung. A partial result whose name is still being
     * said is not believed yet; the next frames decide.
     */
    private fun believable(json: String, fed: FloatArray): Boolean {
        val words = VoskResults.words(json)
        val verdict = WakeCheck.judge(words, fedSamples.toDouble() / SAMPLE_RATE, VoskResults.isFinal(json))
        if (verdict == WakeCheck.Verdict.REJECT) Log.i(TAG, "Wake call not believed: $words")
        val name = WakePhrases.nameIndexIn(words.map { it.text })?.let { words[it] }
        val sung = verdict == WakeCheck.Verdict.ACCEPT && name != null && sung(name, fed)
        if (sung) Log.i(TAG, "Wake call was sung, ignored: ${name?.text}")
        return when {
            // No timing (an older decoder): judged as before.
            words.isEmpty() -> true
            else -> verdict == WakeCheck.Verdict.ACCEPT && !sung
        }
    }

    private fun sung(name: WakeCheck.Word, fed: FloatArray): Boolean =
        sungCheck?.takeIf { it.first == name }?.second
            ?: Singing.isSung(VoskResults.wordAudio(fed, fedSamples, name)).also { sungCheck = name to it }
}

/** Reading Vosk's answers: the words with their timing, and whether the answer is final. */
internal object VoskResults {

    private const val SAMPLE_RATE = 16_000

    /** Judged when a word's timing doesn't fit the audio: the last second. */
    private const val FALLBACK_SAMPLES = SAMPLE_RATE

    /**
     * The stretch of [fed] that [word] was said in. [fed] ends at sample
     * [fedSamples] of everything the decoder has had, which is what Vosk's
     * times count from.
     */
    fun wordAudio(fed: FloatArray, fedSamples: Long, word: WakeCheck.Word): FloatArray {
        val fromEnd = (fedSamples - word.start * SAMPLE_RATE).toInt()
        val untilEnd = (fedSamples - word.end * SAMPLE_RATE).toInt().coerceAtLeast(0)
        return if (fromEnd in (untilEnd + 1)..fed.size) {
            fed.copyOfRange(fed.size - fromEnd, fed.size - untilEnd)
        } else {
            fed.copyOfRange(maxOf(0, fed.size - FALLBACK_SAMPLES), fed.size)
        }
    }

    /** Words with their timing, from a final ("result") or partial ("partial_result") answer. */
    fun words(jsonResult: String): List<WakeCheck.Word> = try {
        val obj = JSONObject(jsonResult)
        val list = obj.optJSONArray("result") ?: obj.optJSONArray("partial_result")
        if (list == null) {
            emptyList()
        } else {
            (0 until list.length()).map { i ->
                val w = list.getJSONObject(i)
                WakeCheck.Word(
                    w.optString("word"), w.optDouble("start"), w.optDouble("end"),
                    if (w.has("conf")) w.optDouble("conf") else null
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun isFinal(jsonResult: String): Boolean = try {
        !JSONObject(jsonResult).has("partial")
    } catch (_: Exception) {
        false
    }
}
