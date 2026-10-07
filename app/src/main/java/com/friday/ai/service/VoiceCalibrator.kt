package com.friday.ai.service

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.friday.ai.core.PcmAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VoiceCalibrator {

    companion object {
        private const val TAG = "VoiceCalibrator"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val SILENCE_DURATION_MS = 2000L
        private const val SPEECH_DURATION_MS = 3000L
        private const val WAKE_SHARE = 0.15
        private const val WHISPER_SHARE = 0.12
        private const val MIN_WAKE = 50.0
        private const val MAX_WAKE = 600.0
        private const val MIN_WHISPER = 40.0
        private const val MAX_WHISPER = 450.0

        /**
         * Where the thresholds are kept. "_v2": values measured by older
         * versions came from a different microphone path and are wrong for
         * this one, so they are left unread until the owner calibrates again.
         */
        const val PREF_WAKE = "wake_threshold_v2"
        const val PREF_WHISPER = "whisper_threshold_v2"
    }

    data class CalibrationResult(
        val ambientNoise: Double,
        val speechEnergy: Double,
        val wakeWordThreshold: Double,
        val whisperThreshold: Double
    )

    interface Listener {
        fun onPhase(phase: Phase)
        fun onEnergyUpdate(energy: Double)
    }

    enum class Phase { MEASURING_SILENCE, WAITING_FOR_SPEECH, MEASURING_SPEECH, DONE }

    var listener: Listener? = null

    private fun result(ambientNoise: Double, speechEnergy: Double): CalibrationResult {
        val wakeThreshold = ambientNoise + (speechEnergy - ambientNoise) * WAKE_SHARE
        val whisperThreshold = ambientNoise + (speechEnergy - ambientNoise) * WHISPER_SHARE
        return CalibrationResult(
            ambientNoise = ambientNoise,
            speechEnergy = speechEnergy,
            // Capped lower than before: a threshold above ordinary speech is deafness.
            wakeWordThreshold = wakeThreshold.coerceIn(MIN_WAKE, MAX_WAKE),
            whisperThreshold = whisperThreshold.coerceIn(MIN_WHISPER, MAX_WHISPER)
        )
    }

    suspend fun calibrate(): CalibrationResult = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            .coerceAtLeast(4096)

        // Measured exactly the way Friday listens: the same source and the
        // same noise suppression and gain control. Calibrating on MIC (louder,
        // differently processed on Samsung) gave thresholds the real path's
        // speech hardly reached, so Friday seemed deaf to her own owner.
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
        )
        val effects = AudioEffects.attach(audioRecord.audioSessionId)

        try {
            audioRecord.startRecording()
            val buffer = ShortArray(bufferSize / 2)
            val chunkMs = (buffer.size * 1000L) / SAMPLE_RATE

            withContext(Dispatchers.Main) { listener?.onPhase(Phase.MEASURING_SILENCE) }
            val silenceEnergies = mutableListOf<Double>()
            var elapsed = 0L
            while (elapsed < SILENCE_DURATION_MS) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read <= 0) break
                val e = PcmAudio.rms(buffer, read)
                silenceEnergies.add(e)
                withContext(Dispatchers.Main) { listener?.onEnergyUpdate(e) }
                elapsed += chunkMs
            }
            val ambientNoise = silenceEnergies.average()

            withContext(Dispatchers.Main) { listener?.onPhase(Phase.WAITING_FOR_SPEECH) }
            val speechThreshold = ambientNoise * 2.5
            var speechDetected = false
            while (!speechDetected) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read <= 0) break
                val e = PcmAudio.rms(buffer, read)
                withContext(Dispatchers.Main) { listener?.onEnergyUpdate(e) }
                if (e > speechThreshold) speechDetected = true
            }

            withContext(Dispatchers.Main) { listener?.onPhase(Phase.MEASURING_SPEECH) }
            val speechEnergies = mutableListOf<Double>()
            elapsed = 0L
            while (elapsed < SPEECH_DURATION_MS) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read <= 0) break
                val e = PcmAudio.rms(buffer, read)
                speechEnergies.add(e)
                withContext(Dispatchers.Main) { listener?.onEnergyUpdate(e) }
                elapsed += chunkMs
            }
            val speechEnergy = speechEnergies.average()

            withContext(Dispatchers.Main) { listener?.onPhase(Phase.DONE) }
            result(ambientNoise, speechEnergy)
        } finally {
            effects.release()
            audioRecord.stopAndRelease(TAG)
        }
    }

}
