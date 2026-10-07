package com.friday.ai.core

/**
 * Whether a wake call the decoder reported is to be believed, judged on the
 * timing and confidence of the name itself.
 *
 * A decoder that knows only a few words maps anything onto them; singing
 * in particular used to wake Friday. A spoken "Пятница" takes about half a
 * second and is recognised with confidence; a sung one is drawn out, and a
 * syllable forced onto the name is recognised without it.
 */
object WakeCheck {

    /** One recognised word; times in seconds of audio fed to the decoder. */
    data class Word(val text: String, val start: Double, val end: Double, val conf: Double? = null)

    enum class Verdict { ACCEPT, WAIT, REJECT }

    /** Said slowly, "пятница" still fits in this. Longer is a sung or drawn-out word. */
    const val MAX_NAME_SEC = 1.0

    /** Shorter is a click or a fragment, not a name. */
    private const val MIN_NAME_SEC = 0.15

    /** A final result below this was a guess. */
    private const val MIN_CONF = 0.6

    /** The decoder must have heard this far past the name before its timing is settled. */
    private const val SETTLE_SEC = 0.1

    /**
     * @param words    the words of the result, in order
     * @param position how much audio the decoder has had, in the same seconds as the words
     * @param final    a final result (with real confidences) rather than a partial one
     */
    fun judge(words: List<Word>, position: Double, final: Boolean): Verdict {
        val index = WakePhrases.nameIndexIn(words.map { it.text }) ?: return Verdict.REJECT
        val name = words[index]
        val stillSaying = !final && index == words.lastIndex && position < name.end + SETTLE_SEC
        val length = name.end - name.start
        return when {
            final && name.conf != null && name.conf < MIN_CONF -> Verdict.REJECT
            // Still being said: its length is not known yet — unless it is already too long.
            stillSaying && length <= MAX_NAME_SEC -> Verdict.WAIT
            length > MAX_NAME_SEC || length < MIN_NAME_SEC -> Verdict.REJECT
            else -> Verdict.ACCEPT
        }
    }
}
