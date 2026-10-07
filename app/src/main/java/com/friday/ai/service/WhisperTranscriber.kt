package com.friday.ai.service

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.friday.ai.core.PcmAudio
import com.friday.ai.core.RollingAudio
import com.friday.ai.core.VoiceTurn
import com.friday.ai.core.people.NameHints
import com.friday.ai.data.remote.GroqApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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

        /**
         * How long the user must stay quiet before we decide they're done.
         * This is dead air the user sits through on every single turn, so it
         * dominates perceived responsiveness. 1800ms felt sluggish next to
         * Bixby; ~900ms is close to what mainstream assistants use while
         * still tolerating a brief mid-sentence pause.
         */
        private const val DEFAULT_SILENCE_DURATION_MS = 800L

        /** A follow-up turn with no wake word: wait a bit for the user to start. */
        private const val LEAD_IN_SILENCE_MS = 4000L

        private const val MAX_RECORD_MS = 15000
        private const val MIN_SPEECH_MS = 400

        private const val MS_PER_SECOND = 1000L
        private const val MAX_RECORD_SAMPLES = (SAMPLE_RATE * MAX_RECORD_MS / MS_PER_SECOND).toInt()
        private const val MIN_BUFFER_BYTES = 4096

        /** Lead-in kept ahead of the first loud frame for speaker checking: 300 ms. */
        private const val VOICE_PRE_ROLL = SAMPLE_RATE * 3 / 10
    }

    /** Every callback is optional; implement only the stages you react to. */
    interface Listener {
        fun onRecordingStarted() = Unit
        fun onSpeechDetected() = Unit
        fun onRecordingFinished() = Unit
        fun onTranscriptionResult(text: String) = Unit
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

    private var effects: AudioEffects? = null

    var silenceThreshold: Double = DEFAULT_SILENCE_THRESHOLD
    var silenceDurationMs: Long = DEFAULT_SILENCE_DURATION_MS

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
        try {
            // VOICE_RECOGNITION rather than MIC: it is the tuned path, and it
            // is what the hardware effects below expect to sit on.
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                withContext(Dispatchers.Main) { listener?.onError("Microphone unavailable") }
                return@withContext VoiceTurn.Outcome.Nothing
            }
            effects = AudioEffects.attach(audioRecord.audioSessionId)

            val capture = capture(
                audioRecord, ShortArray(bufferSize / 2), keepVoice = speakerCheck != null, lead = lead
            )
            if (!capture.usable) VoiceTurn.Outcome.Nothing
            else transcribe(apiKey, capture, speakerCheck)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error: ${e.message}")
            withContext(Dispatchers.Main) { listener?.onError(e.message ?: "Transcription failed") }
            VoiceTurn.Outcome.Nothing
        } finally {
            // Effects first: one outliving its audio session leaks.
            effects?.release()
            effects = null
            audioRecord.stopAndRelease(TAG)
        }
    }

    /** One utterance as recorded: the PCM for Whisper and, if asked, the voice for checking. */
    private class Capture(val pcm: List<ByteArray>, val voice: FloatArray?, val usable: Boolean)

    /**
     * Decides when an utterance has started and ended, chunk by chunk.
     * Separate from the recording loop so the loop only moves audio around.
     */
    private inner class Endpointer(private val chunkMs: Long, leadMs: Long = 0) {
        /** With a lead the speaker is already mid-sentence: only the trailing silence is awaited. */
        var hasSpeech = leadMs > 0
            private set
        private var totalMs = 0L
        private var silenceMs = 0L
        private var speechMs = leadMs

        val usable: Boolean get() = hasSpeech && speechMs >= MIN_SPEECH_MS

        /** @return false once the utterance is over, or never began. */
        fun keepGoing(loud: Boolean): Boolean {
            totalMs += chunkMs
            when {
                loud -> { hasSpeech = true; silenceMs = 0; speechMs += chunkMs }
                hasSpeech -> silenceMs += chunkMs
            }
            return when {
                totalMs >= MAX_RECORD_MS -> false
                hasSpeech -> silenceMs < silenceDurationMs
                // Nobody started talking. Bail out now instead of holding the
                // mic — and the overlay — open for the full window.
                else -> totalMs < LEAD_IN_SILENCE_MS
            }
        }
    }

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
        val endpointer = Endpointer(chunkMs = buffer.size * MS_PER_SECOND / SAMPLE_RATE, leadMs = leadMs)
        lead?.let(::toPcm16)?.let { samples ->
            pcm.add(PcmAudio.toLittleEndian(samples, samples.size))
            voice?.append(samples, samples.size)
        }

        record.startRecording()
        withContext(Dispatchers.Main) { listener?.onRecordingStarted() }

        while (true) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) break
            pcm.add(PcmAudio.toLittleEndian(buffer, read))

            val energy = PcmAudio.rms(buffer, read)
            onLevel?.invoke(normaliseLevel(energy))
            val loud = energy > silenceThreshold

            voice?.let { keepForCheck(it, buffer, read, speaking = endpointer.hasSpeech || loud) }

            val startedNow = loud && !endpointer.hasSpeech
            val more = endpointer.keepGoing(loud)
            if (startedNow) withContext(Dispatchers.Main) { listener?.onSpeechDetected() }
            if (!more) break
        }

        record.stop()
        withContext(Dispatchers.Main) { listener?.onRecordingFinished() }
        return Capture(pcm, voice?.snapshot(), endpointer.usable)
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
    ): VoiceTurn.Outcome = coroutineScope {
        val verdict = if (speakerCheck != null && capture.voice != null) {
            async(Dispatchers.Default) { speakerCheck(capture.voice) }
        } else null

        val wavFile = File(cacheDir, "whisper_input.wav")
        writeWav(wavFile, capture.pcm, SAMPLE_RATE)
        val prompt = runCatching { hints() }.getOrNull()
        val text = groqApi.transcribeAudio(apiKey, wavFile, prompt = prompt)
        wavFile.delete()

        when {
            verdict?.await() == false -> VoiceTurn.Outcome.Stranger
            text.isBlank() || NameHints.isEcho(text, prompt) -> VoiceTurn.Outcome.Nothing
            else -> {
                withContext(Dispatchers.Main) { listener?.onTranscriptionResult(text) }
                VoiceTurn.Outcome.Heard(text)
            }
        }
    }

    /**
     * RMS is linear over 0..32767, but loudness isn't perceived that way — a
     * linear meter barely twitches for normal speech. The square root spreads
     * conversational level across most of the range, which is what the ring
     * needs to look alive.
     */
    private fun normaliseLevel(rms: Double): Float =
        Math.sqrt((rms / 5000.0).coerceIn(0.0, 1.0)).toFloat()



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
