package com.friday.ai.core

/**
 * The words people put in front of a request in conversation: "Хорошо, но
 * во сколько ждать дождь?", "Ладно, а теперь открой ютуб". Left in, the
 * request was split at the comma, "хорошо" matched nothing, and the whole
 * phrase went to the model instead of the command it was.
 */
object Filler {

    private val leading = Regex(
        """^(?:хорошо|ладно|окей|ок|отлично|понятно|ясно|спасибо|ok|okay|alright|thanks|great)""" +
            """[,.!]?\s+(?:(?:а|но|и|тогда|so|and|but|then)\s+)?(?:(?:теперь|now)\s+)?""",
        RegexOption.IGNORE_CASE
    )

    /** [text] without its lead-in; unchanged when nothing would be left. */
    fun strip(text: String): String {
        val rest = leading.replaceFirst(text.trim(), "").trim()
        return if (rest.isEmpty()) text.trim() else rest.replaceFirstChar { it.uppercase() }
    }
}
