package com.friday.ai.core

import kotlinx.coroutines.delay

/**
 * Handing an action to another app, and making sure it happened.
 *
 * Starting a call in WhatsApp or music in Spotify means sending that app an
 * intent and hoping. A cold start can swallow the first one: the app comes
 * up on its home screen and drops the request. The user then repeats the
 * command, the app (now running) obeys, and it looks as though Friday needs
 * everything said twice.
 *
 * So a hand-off is: send → wait for the effect the system can show (a
 * playing media session, a call in progress) → if nothing, send once more,
 * now that the app is up → report what was actually seen. Never more than
 * one retry: a second call or a doubled track is worse than an honest
 * "didn't start".
 *
 * Only for effects the system exposes. Actions whose result is a screen for
 * the user to finish (a message draft, the camera) are done when the screen
 * opens and don't come through here.
 */
object HandOff {

    enum class Result {
        /** Seen after the first send. */
        DONE,

        /** Seen only after the retry: the first send was swallowed. */
        DONE_ON_RETRY,

        /** Not seen at all; say so rather than claim it. */
        NOT_SEEN
    }

    const val POLL_MS = 250L

    /**
     * @param send delivers the intent; called once, and once more if nothing happened
     * @param happened true once the effect is visible (polled)
     * @param retry false for actions where a second send could do harm twice
     */
    suspend fun run(
        send: () -> Unit,
        happened: () -> Boolean,
        waitMs: Long,
        retryWaitMs: Long = waitMs,
        retry: Boolean = true
    ): Result {
        send()
        if (poll(waitMs, happened)) return Result.DONE
        if (!retry) return Result.NOT_SEEN
        send()
        return if (poll(retryWaitMs, happened)) Result.DONE_ON_RETRY else Result.NOT_SEEN
    }

    /** True as soon as [happened] is; false after [totalMs]. */
    suspend fun poll(totalMs: Long, happened: () -> Boolean): Boolean {
        var waited = 0L
        while (waited < totalMs) {
            if (happened()) return true
            delay(POLL_MS)
            waited += POLL_MS
        }
        return happened()
    }
}
