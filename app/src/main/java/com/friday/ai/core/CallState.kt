package com.friday.ai.core

import android.content.Context
import android.media.AudioManager

/**
 * Whether a call is in progress — phone or VoIP.
 *
 * Read from the audio mode, which needs no permission: a cellular call puts
 * it in `MODE_IN_CALL`, a WhatsApp or Telegram call in
 * `MODE_IN_COMMUNICATION`, and a ringing phone in `MODE_RINGTONE`.
 *
 * Used to confirm that a call Friday started really started, and to keep
 * Friday quiet during one: no speaking over it, no microphone opened for a
 * follow-up.
 */
object CallState {

    fun inCall(context: Context): Boolean {
        val mode = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }
}
