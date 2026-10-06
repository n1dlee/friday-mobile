package com.friday.ai.service

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.friday.ai.core.KaldiFbank
import com.friday.ai.core.PcmAudio
import com.friday.ai.core.VoiceGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Records single utterances for building a voice profile.
 *
 * Enrolment audio is captured through the same source and the same gate as
 * the wake word, on purpose: a profile built from cleaner recordings than the
 * ones it will be compared against is a profile that rejects its owner.
 */
class VoiceEnroller {

    private companion object {
        const val TAG = "VoiceEnroller"
        const val SAMPLE_RATE = KaldiFbank.SAMPLE_RATE
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** Give up if nobody speaks. */
        const val LEAD_IN_MS = 6000

        /** Stop once the phrase has clearly ended. */
        const val TRAILING_SILENCE_MS = 700

        const val MAX_UTTERANCE_MS = 4000

        /** Shorter than this is a cough, not a wake word. */
        const val MIN_SPEECH_MS = 300
    }

    /**
     * Records one spoken phrase.
     *
     * @return the utterance at int16 scale, or null if nothing was said or the
     *   microphone was unavailable
     */
    suspend fun recordOne(): FloatArray? = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            .coerceAtLeast(4096)
        var record: AudioRecord? = null
        var effects: AudioEffects? = null

        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) return@withContext null

            effects = AudioEffects.attach(record.audioSessionId)
            record.startRecording()

            val buffer = ShortArray(bufferSize / 2)
            val frameMs = (buffer.size * 1000 / SAMPLE_RATE).coerceAtLeast(1)
            val gate = VoiceGate(openThreshold = 150.0, frameMs = frameMs)

            val captured = ArrayList<Float>(SAMPLE_RATE * 2)
            var elapsedMs = 0
            var speechMs = 0
            var trailingSilenceMs = 0
            var started = false

            while (elapsedMs < LEAD_IN_MS + MAX_UTTERANCE_MS) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) break
                elapsedMs += frameMs

                val decision = gate.onFrame(PcmAudio.rms(buffer, read))
                if (decision.feed) {
                    started = true
                    speechMs += frameMs
                    trailingSilenceMs = 0
                    for (i in 0 until read) captured.add(buffer[i].toFloat())
                } else if (started) {
                    trailingSilenceMs += frameMs
                    if (trailingSilenceMs >= TRAILING_SILENCE_MS) break
                } else if (elapsedMs >= LEAD_IN_MS) {
                    break
                }
                if (speechMs >= MAX_UTTERANCE_MS) break
            }

            if (!started || speechMs < MIN_SPEECH_MS) return@withContext null
            FloatArray(captured.size) { captured[it] }
        } catch (e: Exception) {
            Log.e(TAG, "Enrolment recording failed: ${e.message}")
            null
        } finally {
            effects?.release()
            record.stopAndRelease(TAG)
        }
    }

}
