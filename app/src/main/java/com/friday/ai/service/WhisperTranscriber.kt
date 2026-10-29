package com.friday.ai.service

import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import org.vosk.Model
import com.friday.ai.core.PcmAudio
import com.friday.ai.core.RollingAudio
import com.friday.ai.core.VoiceTurn
import com.friday.ai.core.audio.SpeechEndpointer
import com.friday.ai.core.people.NameHints
import com.friday.ai.data.remote.GroqApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

class WhisperTranscriber(
    private val groqApi: GroqApiService,
    private val cacheDir: File
) {

    companion object {
        private const val TAG = "WhisperTranscriber"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val DEFAULT_SILENCE_THRESHOLD = 250.0

        private const val MS_PER_SECOND = 1000L
        private const val MAX_RECORD_SAMPLES = (SAMPLE_RATE * SpeechEndpointer.MAX_MS / MS_PER_SECOND).toInt()
        private const val MIN_BUFFER_BYTES = 4096
        private const val LEVEL_RANGE = 5000.0

        /** Lead-in kept ahead of the first loud frame for speaker checking: 300 ms. */
        private const val VOICE_PRE_ROLL = SAMPLE_RATE * 3 / 10
    }

    /** Every callback is optional; implement only the stages you react to. */
    interface Listener {
        fun onRecordingStarted() = Unit
        fun onSpeechDetected() = Unit
        fun onRecordingFinished() = Unit
        fun onTranscriptionResult(text: String) = Unit
        fun onTranscriptionStarted() = Unit
        fun onOwnerCheckStarted() = Unit
        fun onOwnerChecked(accepted: Boolean) = Unit
        fun onError(message: String) = Unit
    }

    var listener: Listener? = null

    /**
     * Loudness of the frame just read, 0..1, on the recording thread.
     * Deliberately not part of [Listener]: it fires ~20x a second and only the
     * overlay's visualiser cares, so nothing else should have to implement it.
     */
    var onLevel: ((Float) -> Unit)? = null

    /** Names to expect (see [NameHints]); null for none. */
    var hints: () -> String? = { null }

    @Volatile private var activeRecord: AudioRecord? = null

    /** Aborts capture; the cancelled caller releases the recorder and discards its audio. */
    fun abort() {
        activeRecord.interruptCapture(TAG)
    }

    /** The calibrated speech level: the most a voice ever has to reach (see [SpeechEndpointer]). */
    var silenceThreshold: Double = DEFAULT_SILENCE_THRESHOLD

    /**
     * The on-device model, when the wake word has loaded it: it follows the
     * command word by word so the turn ends when the sentence does
     * ([SpokenEnd]). Null leaves the sound-based end alone.
     */
    var endpointModel: () -> Model? = { null }

    /**
     * The language to transcribe in: "ru", or null to let Whisper guess.
     * Guessing fails on short phrases — on the owner's own recordings a
     * four-second Russian sentence came back in Polish — while "ru" keeps
     * the English words of mixed speech ("включи sqrt", "Backrooms") in Latin.
     */
    @Volatile
    var language: String? = null

    @Volatile
    private var stopRequested = false

    /** Ends the recording now and transcribes what was said so far. */
    fun requestStop() {
        stopRequested = true
    }

    /**
     * Records one utterance and transcribes it.
     *
     * @param speakerCheck when given, decides from the recorded audio whether
     *   the owner was the one speaking. It runs alongside the transcription
     *   request rather than before it, so verifying costs no extra wait: the
     *   network round-trip is longer than the check. The price is that a
     *   stranger's words still reach Groq before being discarded — exactly
     *   what happened to them before verification existed.
     */
    suspend fun recordAndTranscribe(
        apiKey: String,
        speakerCheck: ((FloatArray) -> Boolean)? = null,
        /** Audio already heard (int16 scale), put in front: a command that began in the same breath as the name. */
        lead: FloatArray? = null
    ): VoiceTurn.Outcome = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            .coerceAtLeast(MIN_BUFFER_BYTES)

        var audioRecord: AudioRecord? = null
        var effects: AudioEffects? = null
        try {
            currentCoroutineContext().ensureActive()
            // VOICE_RECOGNITION rather than MIC: it is the tuned path, and it
            // is what the hardware effects below expect to sit on.
            audioRecord = openVoiceRecord(SAMPLE_RATE, bufferSize, TAG) ?: run {
                withContext(Dispatchers.Main) { listener?.onError("Microphone unavailable") }
                return@withContext VoiceTurn.Outcome.Nothing
            }
            activeRecord = audioRecord
            effects = AudioEffects.attach(audioRecord.audioSessionId)

            val capture = capture(
                audioRecord, ShortArray(bufferSize / 2), keepVoice = speakerCheck != null, lead = lead
            )
            if (!capture.usable) VoiceTurn.Outcome.Nothing
            else transcribe(apiKey, capture, speakerCheck)
        } catch (e: CancellationException) {
            // The turn was abandoned: the upload is cancelled with it, and that is no error to show.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error: ${e.javaClass.simpleName}")
            withContext(Dispatchers.Main) { listener?.onError("Ошибка записи или распознавания") }
            VoiceTurn.Outcome.Unavailable(VoiceTurn.Failure.CAPTURE)
        } finally {
            // Effects first: one outliving its audio session leaks.
            effects?.release()
            audioRecord.stopAndRelease(TAG)
            if (activeRecord === audioRecord) activeRecord = null
        }
    }

    /** One utterance as recorded: the PCM for Whisper and, if asked, the voice for checking. */
    private class Capture(
        val pcm: List<ByteArray>,
        val voice: FloatArray?,
        val usable: Boolean,
        /** What the on-device decoder heard; the fallback when Whisper hears nothing. */
        val words: String?
    )

    private suspend fun capture(
        record: AudioRecord,
        buffer: ShortArray,
        keepVoice: Boolean,
        lead: FloatArray? = null
    ): Capture {
        val pcm = mutableListOf<ByteArray>()
        // Kept only when someone will check the speaker; starts a little
        // before the first loud frame so the onset of the voice is in it.
        val voice = if (keepVoice) RollingAudio(MAX_RECORD_SAMPLES) else null
        val leadMs = (lead?.size ?: 0) * MS_PER_SECOND / SAMPLE_RATE
        val endpointer = SpeechEndpointer(ceiling = silenceThreshold, leadMs = leadMs)
        val words = endpointModel()?.let(WordFollower::open)
        var onDevice: String? = null
        try {
            stopRequested = false
            lead?.let(::toPcm16)?.let { samples ->
                pcm.add(PcmAudio.toLittleEndian(samples, samples.size))
                voice?.append(samples, samples.size)
                words?.feed(samples, samples.size, endpointer)
            }
            currentCoroutineContext().ensureActive()
            record.startRecording()
            withContext(Dispatchers.Main) { listener?.onRecordingStarted() }
            while (true) {
                currentCoroutineContext().ensureActive()
                if (stopRequested) endpointer.stop()
                if (endpointer.end != null) break
                val read = record.read(buffer, 0, buffer.size)
                currentCoroutineContext().ensureActive()
                if (read <= 0) break
                pcm.add(PcmAudio.toLittleEndian(buffer, read))
                onLevel?.invoke(Math.sqrt((PcmAudio.rms(buffer, read) / LEVEL_RANGE).coerceIn(0.0, 1.0)).toFloat())

                val wasSpeaking = endpointer.speechStarted
                endpointer.push(buffer, read)
                words?.feed(buffer, read, endpointer)
                voice?.let { keepForCheck(it, buffer, read, speaking = endpointer.speechStarted) }
                if (!wasSpeaking && endpointer.speechStarted) {
                    withContext(Dispatchers.Main) { listener?.onSpeechDetected() }
                }
            }
            onDevice = words?.heard()
        } finally {
            words?.close()
        }

        record.stop()
        val how = if (endpointer.endedByWords) "by words (${words?.wordCount})" else "by sound"
        Log.i(TAG, "Recording ended: ${endpointer.end} $how, ${endpointer.speechMs} ms of speech")
        withContext(Dispatchers.Main) { listener?.onRecordingFinished() }
        return Capture(pcm, voice?.snapshot(), endpointer.usable, onDevice)
    }

    /**
     * Before speech only the last few hundred ms are worth keeping for the
     * speaker check; a long silent lead-in dilutes the embedding.
     */
    private fun keepForCheck(voice: RollingAudio, buffer: ShortArray, read: Int, speaking: Boolean) {
        if (!speaking && voice.size > VOICE_PRE_ROLL) voice.clear()
        voice.append(buffer, read)
    }

    private fun toPcm16(audio: FloatArray): ShortArray = ShortArray(audio.size) {
        audio[it].toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /**
     * Sends the capture to Whisper, checking the speaker at the same time.
     * The check rides alongside the network request, so it adds no wait.
     */
    private suspend fun transcribe(
        apiKey: String,
        capture: Capture,
        speakerCheck: ((FloatArray) -> Boolean)?
    ): VoiceTurn.Outcome {
        val prompt = runCatching { hints() }.getOrNull()
        // A file of its own: the chat's microphone and the voice service each
        // have a transcriber, and a shared name let one overwrite the other.
        withContext(Dispatchers.Main) { listener?.onTranscriptionStarted() }
        val check = if (speakerCheck != null && capture.voice != null) {
            listener?.onOwnerCheckStarted()
            val verify: suspend () -> Boolean = {
                val accepted = speakerCheck(capture.voice)
                listener?.onOwnerChecked(accepted)
                accepted
            }
            verify
        } else null
        val result = AsrRecovery.transcribe(capture.words, prompt, apiKey.isNotBlank(), check) {
            val wavFile = File.createTempFile("friday-asr-", ".wav", cacheDir)
            try {
                writeWav(wavFile, capture.pcm, SAMPLE_RATE)
                groqApi.transcribeAudio(apiKey, wavFile, language = language, prompt = prompt)
            } finally {
                if (!wavFile.delete()) Log.w(TAG, "Could not delete the recording")
            }
        }
        if (result is VoiceTurn.Outcome.Heard) {
            withContext(Dispatchers.Main) { listener?.onTranscriptionResult(result.text) }
        }
        return result
    }

    private fun writeWav(file: File, pcmChunks: List<ByteArray>, sampleRate: Int) {
        val totalDataSize = pcmChunks.sumOf { it.size }
        FileOutputStream(file).use { fos ->
            // WAV header (44 bytes)
            fos.write("RIFF".toByteArray())
            fos.write(intToLittleEndian(36 + totalDataSize))
            fos.write("WAVE".toByteArray())
            fos.write("fmt ".toByteArray())
            fos.write(intToLittleEndian(16)) // chunk size
            fos.write(shortToLittleEndian(1)) // PCM format
            fos.write(shortToLittleEndian(1)) // mono
            fos.write(intToLittleEndian(sampleRate))
            fos.write(intToLittleEndian(sampleRate * 2)) // byte rate
            fos.write(shortToLittleEndian(2)) // block align
            fos.write(shortToLittleEndian(16)) // bits per sample
            fos.write("data".toByteArray())
            fos.write(intToLittleEndian(totalDataSize))

            for (chunk in pcmChunks) {
                fos.write(chunk)
            }
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 24 and 0xFF).toByte()
    )

    private fun shortToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte()
    )
}
