package com.friday.ai.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * On-device speech using Android's built-in TTS engine.
 *
 * This is the safety net, not the first choice: Edge's neural voices sound
 * better, but they live on someone else's server and already went down once
 * (Microsoft started rejecting the handshake with 403). When that happens
 * Friday still has to be able to talk, offline and with no third party
 * involved.
 *
 * Voice quality here is whatever TTS engine the phone has selected. Installing
 * a neural engine (e.g. SherpaTTS from F-Droid) and picking it in system
 * settings upgrades this path without any change to the app.
 */
class SystemTtsSpeaker(context: Context) {

    private companion object {
        const val TAG = "SystemTtsSpeaker"
        const val INIT_TIMEOUT_MS = 5_000L
    }

    private val ready = CompletableDeferred<Boolean>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        val ok = status == TextToSpeech.SUCCESS
        if (!ok) Log.e(TAG, "TTS engine unavailable (status=$status)")
        ready.complete(ok)
    }

    /** Speaks [text] and suspends until playback finishes. Returns false if it couldn't. */
    suspend fun speak(text: String): Boolean {
        if (text.isBlank()) return true

        val available = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false
        if (!available) {
            Log.e(TAG, "Not speaking — engine never became ready")
            return false
        }

        applyLanguageFor(text)

        return suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            var settled = false
            fun settle(success: Boolean) {
                if (settled) return
                settled = true
                if (cont.isActive) cont.resume(success)
            }

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = settle(true)

                @Deprecated("Superseded by the (String, int) overload")
                override fun onError(utteranceId: String?) = settle(false)

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(TAG, "Playback error code=$errorCode")
                    settle(false)
                }
            })

            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result != TextToSpeech.SUCCESS) {
                Log.e(TAG, "speak() rejected the request")
                settle(false)
            }

            cont.invokeOnCancellation { runCatching { tts.stop() } }
        }
    }

    fun stop() {
        runCatching { tts.stop() }
    }

    fun destroy() {
        runCatching {
            tts.stop()
            tts.shutdown()
        }
    }

    /** Picks a voice language so Russian isn't read with an English accent. */
    private fun applyLanguageFor(text: String) {
        val cyrillic = text.count { it in 'Ѐ'..'ӿ' }
        val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        val locale = if (cyrillic > latin) Locale("ru", "RU") else Locale.US

        val status = runCatching { tts.setLanguage(locale) }.getOrNull()
        if (status == TextToSpeech.LANG_MISSING_DATA || status == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "No voice data for $locale; falling back to the engine default")
            runCatching { tts.setLanguage(Locale.US) }
        }
    }
}
