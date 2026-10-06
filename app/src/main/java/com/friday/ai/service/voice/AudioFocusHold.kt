package com.friday.ai.service.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Quiets other audio while Friday listens and talks, the way Google
 * Assistant and Siri do.
 *
 * Without it music kept playing through the whole exchange: the microphone
 * recorded the song along with "останови музыку", the voice check compared
 * that mix with the owner's voice and could turn it down, and silence never
 * came to end the recording. A transient focus request makes players pause
 * (or duck) and pick up again when it is released.
 */
class AudioFocusHold(context: Context) {

    private companion object {
        const val TAG = "AudioFocusHold"
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var held: AudioFocusRequest? = null

    /** Taken once per conversation; taking it again while held does nothing. */
    fun take() {
        if (held != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // Losing it (a call, the user starting music) needs no reaction:
            // Friday's own speech is not gated on focus.
            .setOnAudioFocusChangeListener { }
            .build()
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) held = request else Log.i(TAG, "Focus refused (a call?)")
    }

    /** Gives the audio back; whatever was playing resumes unless it was told to stop. */
    fun release() {
        held?.let { audio.abandonAudioFocusRequest(it) }
        held = null
    }
}
