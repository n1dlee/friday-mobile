package com.friday.ai.core

/**
 * What one listening turn produced, and what Friday does about it.
 *
 * Kept apart from the service so the rules — when to retry, when to stay
 * quiet, when to say something — can be tested without a microphone.
 */
object VoiceTurn {

    sealed interface Outcome {
        data class Heard(val text: String) : Outcome

        /** Nothing usable: silence, too short, or the recording failed. */
        data object Nothing : Outcome

        /** Someone spoke, but not the enrolled owner. */
        data object Stranger : Outcome
    }

    enum class Next {
        /** Act on what was heard. */
        PROCESS,

        /** Open the microphone once more, without comment. */
        LISTEN_AGAIN,

        /** Close the panel without a word. */
        END_QUIETLY,

        /** Close with "Не расслышал" on screen. */
        SAY_NOT_HEARD,

        /** Close with "Не узнала голос" on screen. */
        SAY_NOT_RECOGNISED
    }

    /**
     * @param isFollowUp true when Friday is listening on after her own answer,
     *   with no wake word in front of this turn
     * @param attempt 1 for the first try of this turn
     */
    fun next(outcome: Outcome, isFollowUp: Boolean, attempt: Int, maxAttempts: Int): Next = when {
        outcome is Outcome.Heard && outcome.text.isNotBlank() -> Next.PROCESS

        // During a follow-up nobody has addressed Friday, so anything other
        // than the owner speaking simply means the conversation is over. A
        // reply to a stranger here would be Friday butting into a room.
        isFollowUp -> Next.END_QUIETLY

        // The owner just woke her, so they are right there. Silence usually
        // means they started before the microphone opened; another voice
        // usually means someone talked over them. Either way they will try
        // again, so give them the chance once.
        attempt < maxAttempts -> Next.LISTEN_AGAIN

        outcome is Outcome.Stranger -> Next.SAY_NOT_RECOGNISED
        else -> Next.SAY_NOT_HEARD
    }
}
