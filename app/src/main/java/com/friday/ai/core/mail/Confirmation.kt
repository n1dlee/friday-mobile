package com.friday.ai.core.mail

import com.friday.ai.core.SpokenText

/**
 * Reads a yes or a no out of a short reply.
 *
 * Used before anything leaves the phone in the user's name. A misheard word
 * in a spoken email is cheap to fix before sending and impossible after.
 */
object Confirmation {

    enum class Answer { YES, NO, OTHER }

    private val yes = setOf(
        "да", "давай", "отправь", "отправляй", "отправить", "конечно", "ага", "угу",
        "верно", "точно", "подтверждаю", "окей", "ок", "yes", "yeah", "yep", "sure", "send", "ok", "okay"
    )

    // Checked first: "не отправляй" contains "отправляй".
    private val no = setOf(
        "нет", "не", "отмена", "отмени", "отменить", "стоп", "погоди", "подожди",
        "no", "nope", "cancel", "stop", "don't", "dont"
    )

    /**
     * More words than this and it is a new request that happens to begin with
     * "да" — "да, и добавь что опоздаю" is an edit, not a go-ahead.
     */
    private const val MAX_WORDS = 3

    fun classify(text: String): Answer {
        val words = SpokenText.normalise(text.replace("'", "")).split(' ').filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> Answer.OTHER
            words.first() in no || words.any { it == "нет" || it == "отмена" } -> Answer.NO
            words.size <= MAX_WORDS && words.first() in yes -> Answer.YES
            else -> Answer.OTHER
        }
    }
}
