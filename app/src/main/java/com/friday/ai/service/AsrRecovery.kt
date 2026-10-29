package com.friday.ai.service

import com.friday.ai.core.VoiceTurn
import com.friday.ai.core.audio.WhisperArtifacts
import com.friday.ai.core.people.NameHints
import com.friday.ai.data.remote.GroqApiException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Selects a transcript from one capture; a rejected voice never becomes a fallback success. */
internal object AsrRecovery {
    suspend fun transcribe(
        localWords: String?,
        prompt: String?,
        hasKey: Boolean,
        ownerCheck: (suspend () -> Boolean)?,
        request: suspend () -> String
    ): VoiceTurn.Outcome = coroutineScope {
        val owner = ownerCheck?.let { async(Dispatchers.Default) { it() } }
        val result = if (!hasKey) recover(localWords, VoiceTurn.Failure.NO_KEY) else {
            try {
                select(request(), localWords, prompt)
            } catch (e: CancellationException) {
                throw e
            } catch (_: SocketTimeoutException) {
                recover(localWords, VoiceTurn.Failure.TIMEOUT)
            } catch (_: IOException) {
                recover(localWords, VoiceTurn.Failure.NETWORK)
            } catch (e: GroqApiException) {
                recover(localWords, failure(e.code))
            }
        }
        val accepted = owner?.await() != false
        currentCoroutineContext().ensureActive()
        if (accepted) result else VoiceTurn.Outcome.Stranger
    }

    private fun select(raw: String, localWords: String?, prompt: String?): VoiceTurn.Outcome {
        val clean = WhisperArtifacts.clean(raw)
        return when {
            clean.isNotBlank() && !NameHints.isEcho(clean, prompt) -> VoiceTurn.Outcome.Heard(clean)
            !localWords.isNullOrBlank() -> VoiceTurn.Outcome.Heard(localWords, VoiceTurn.Origin.LOCAL)
            else -> VoiceTurn.Outcome.Nothing
        }
    }

    private fun recover(words: String?, reason: VoiceTurn.Failure): VoiceTurn.Outcome =
        if (reason in recoverable && !words.isNullOrBlank()) {
            VoiceTurn.Outcome.Heard(words, VoiceTurn.Origin.LOCAL, reason)
        } else {
            VoiceTurn.Outcome.Unavailable(reason)
        }

    private fun failure(code: Int): VoiceTurn.Failure = when (code) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> VoiceTurn.Failure.AUTH
        HTTP_TIMEOUT, HTTP_RATE_LIMITED, in HTTP_SERVER_ERROR..HTTP_LAST_SERVER_ERROR -> VoiceTurn.Failure.SERVICE
        else -> VoiceTurn.Failure.CONFIG
    }

    private val recoverable = setOf(
        VoiceTurn.Failure.NETWORK, VoiceTurn.Failure.TIMEOUT, VoiceTurn.Failure.SERVICE, VoiceTurn.Failure.NO_KEY
    )
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_TIMEOUT = 408
    private const val HTTP_RATE_LIMITED = 429
    private const val HTTP_SERVER_ERROR = 500
    private const val HTTP_LAST_SERVER_ERROR = 599
}
