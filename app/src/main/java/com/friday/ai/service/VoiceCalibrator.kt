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

    suspend fun calibrate(): CalibrationResult = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            .coerceAtLeast(4096)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
        )

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

            val wakeThreshold = ambientNoise + (speechEnergy - ambientNoise) * 0.15
            val whisperThreshold = ambientNoise + (speechEnergy - ambientNoise) * 0.12

            withContext(Dispatchers.Main) { listener?.onPhase(Phase.DONE) }

            CalibrationResult(
                ambientNoise = ambientNoise,
                speechEnergy = speechEnergy,
                wakeWordThreshold = wakeThreshold.coerceIn(50.0, 1000.0),
                whisperThreshold = whisperThreshold.coerceIn(40.0, 800.0)
            )
        } finally {
            audioRecord.stopAndRelease(TAG)
        }
    }

}
