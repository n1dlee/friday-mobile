package com.friday.ai.core

/**
 * What came of starting a player that wasn't running, and how to say it.
 *
 * "Включи музыку в Spotify" with Spotify closed used to open Spotify and
 * stop there: there was no media session to press play on, so nothing
 * played, and the second attempt (Spotify now running) worked — which looked
 * like Friday not understanding the first time. Now the player is started
 * with a play request, then checked: only music actually heard counts as
 * "playing", as with alarms only an alarm actually set counts as "set".
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

    fun reply(outcome: Outcome, player: String, russian: Boolean): String = when (outcome) {
        Outcome.PLAYING -> if (russian) "Играет $player" else "Playing on $player"
        Outcome.OPENED_NOT_PLAYING ->
            if (russian) "$player открыт, но сам не начал играть — нажмите ▶"
            else "$player is open but didn't start — press play"
        Outcome.OPENED_UNVERIFIED ->
            if (russian) "Открыла $player — не вижу, играет ли он"
            else "Opened $player — I can't tell whether it's playing"
        Outcome.NOT_INSTALLED -> if (russian) "$player не установлен" else "$player isn't installed"
    }
}
