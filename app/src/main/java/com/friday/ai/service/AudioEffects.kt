package com.friday.ai.service

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Turns on the phone's own DSP for a recording session.
 *
 * Samsung and every other major vendor ship hardware noise suppression, echo
 * cancellation and gain control, but none of it is applied unless an app asks
 * for it explicitly — `VOICE_RECOGNITION` alone does not switch them on. This
 * is the single most effective thing available against steady background
 * noise: road and engine noise is exactly the stationary kind these filters
 * are built to remove.
 *
 * All three are optional per device, so each is attempted separately and a
 * missing one is not an error.
 */
class AudioEffects private constructor(
    private val effects: List<AudioEffect>
) {

    companion object {
        private const val TAG = "AudioEffects"

        fun attach(audioSessionId: Int): AudioEffects {
            val created = mutableListOf<AudioEffect>()

            fun enable(name: String, available: Boolean, factory: () -> AudioEffect?) {
                if (!available) {
                    Log.d(TAG, "$name not available on this device")
                    return
                }
                try {
                    factory()?.let {
                        it.enabled = true
                        created += it
                        Log.i(TAG, "$name enabled")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "$name failed: ${e.message}")
                }
            }

            enable("NoiseSuppressor", NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(audioSessionId)
            }
            enable("AcousticEchoCanceler", AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(audioSessionId)
            }
            // Gain control brings a quiet voice up over the floor, which is the
            // other half of being heard in a car.
            enable("AutomaticGainControl", AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(audioSessionId)
            }

            return AudioEffects(created)
        }
    }

    /** Must be called before the AudioRecord it is attached to is released. */
    fun release() {
        effects.forEach {
            try {
                it.enabled = false
                it.release()
            } catch (_: Exception) {}
        }
    }
}
