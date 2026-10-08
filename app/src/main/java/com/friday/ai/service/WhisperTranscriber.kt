package com.friday.ai.service

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.friday.ai.core.PcmAudio
import com.friday.ai.core.RollingAudio
import com.friday.ai.core.VoiceTurn
import com.friday.ai.core.audio.SpeechEndpointer
import com.friday.ai.core.audio.WhisperArtifacts
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

        private const val MS_PER_SECOND = 1000L
        private const val MAX_RECORD_SAMPLES = (SAMPLE_RATE * SpeechEndpointer.MAX_MS / MS_PER_SECOND).toInt()
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

    /** The calibrated speech level: the most a voice ever has to reach (see [SpeechEndpointer]). */
    var silenceThreshold: Double = DEFAULT_SILENCE_THRESHOLD

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
        stopRequested = false
        lead?.let(::toPcm16)?.let { samples ->
            pcm.add(PcmAudio.toLittleEndian(samples, samples.size))
            voice?.append(samples, samples.size)
        }

        record.startRecording()
        withContext(Dispatchers.Main) { listener?.onRecordingStarted() }

        while (true) {
            if (stopRequested) endpointer.stop()
            if (endpointer.end != null) break
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) break
            pcm.add(PcmAudio.toLittleEndian(buffer, read))
            onLevel?.invoke(normaliseLevel(PcmAudio.rms(buffer, read)))

            val wasSpeaking = endpointer.speechStarted
            endpointer.push(buffer, read)
            voice?.let { keepForCheck(it, buffer, read, speaking = endpointer.speechStarted) }
            if (!wasSpeaking && endpointer.speechStarted) withContext(Dispatchers.Main) { listener?.onSpeechDetected() }
        }

        record.stop()
        Log.i(TAG, "Recording ended: ${endpointer.end}, ${endpointer.speechMs} ms of speech")
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

        val prompt = runCatching { hints() }.getOrNull()
        // A file of its own: the chat's microphone and the voice service each
        // have a transcriber, and a shared name let one overwrite the other.
        val wavFile = File.createTempFile("friday-asr-", ".wav", cacheDir)
        val heard = try {
            writeWav(wavFile, capture.pcm, SAMPLE_RATE)
            groqApi.transcribeAudio(apiKey, wavFile, language = language, prompt = prompt)
        } finally {
            if (!wavFile.delete()) Log.w(TAG, "Could not delete the recording")
        }
        val text = WhisperArtifacts.clean(heard)
        if (text != heard.trim()) Log.i(TAG, "Dropped made-up text from the transcript")

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
