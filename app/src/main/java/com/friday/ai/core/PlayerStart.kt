package com.friday.ai.core

/**
 * What came of starting music, and how to say it.
 *
 * "Включи музыку в Spotify" with Spotify closed used to open Spotify and
 * stop there: there was no media session to press play on, so nothing
 * played, and the second attempt (Spotify now running) worked — which looked
 * like Friday not understanding the first time. Starting is now a checked
 * hand-off ([HandOff], [PlaybackStarter]), and only music a player's session
 * reports as playing is called "playing" — as only an alarm actually set is
 * called "set".
 */
object PlayerStart {

    enum class Outcome {
        /** The player's session reports playing. */
        PLAYING,

        /** The player came up but stayed paused even after "play". */
        OPENED_NOT_PLAYING,

        /** Opened, but it published no media session to check or control. */
        OPENED_UNVERIFIED,
        NOT_INSTALLED
    }

    /** [query] is what was asked for ("Believer"), or null for "whatever it plays". */
    fun reply(outcome: Outcome, player: String, russian: Boolean, query: String? = null): String {
        val what = query?.let { if (russian) "«$it» " else "\"$it\" " }.orEmpty()
        return when (outcome) {
            Outcome.PLAYING -> if (russian) "Играет ${what}в $player" else "Playing ${what}on $player"
            Outcome.OPENED_NOT_PLAYING ->
                if (russian) "$player открыт, но сам не начал играть — нажмите ▶"
                else "$player is open but didn't start — press play"
            Outcome.OPENED_UNVERIFIED ->
                if (russian) "Открыла $player — не вижу, играет ли он"
                else "Opened $player — I can't tell whether it's playing"
            Outcome.NOT_INSTALLED -> if (russian) "$player не установлен" else "$player isn't installed"
        }
    }
}
