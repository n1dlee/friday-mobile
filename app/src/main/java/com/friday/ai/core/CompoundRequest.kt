package com.friday.ai.core

import com.friday.ai.domain.model.CommandResult

/**
 * Notices several requests in one sentence.
 *
 * The phrase patterns read one command per sentence, so "поставь будильник
 * на 7 и включи фонарик" set the alarm and silently dropped the rest.
 *
 * When every part is a command the patterns know, the parts are simply
 * carried out in order — no model involved. That matters: asked "сделай
 * громче и включи селфи-камеру", the model set the volume and then said the
 * camera was open too. When some part is not a known command, the whole
 * sentence goes to the model, which can call a tool for each part.
 *
 * Splitting on "и" alone would also cut "открой камеру и сделай фото" — one
 * camera request said in two verbs — so a sentence counts only when its
 * parts, routed on their own, are different kinds of thing. And "запиши:
 * купить хлеб и молоко" is one note, not a note plus a chat about milk: after
 * a command that takes free text, an "и" belongs to that text unless what
 * follows is itself a command.
 */
object CompoundRequest {

    sealed interface Split {
        /** One request, however many verbs it has. */
        data object Single : Split

        /** Every part is a known command; carry them out in this order. */
        data class Steps(val steps: List<CommandResult>) : Split

        /** Several requests, not all of them known commands: a job for the model. */
        data object Mixed : Split
    }

    /**
     * "и", "а потом", "затем", "and then" … or a bare comma, followed by a word
     * — never by a number, so "в 7 и 30" stays one time.
     */
    private val joiner = Regex(
        """,?\s+(?:а\s+потом|а\s+затем|после\s+этого|потом|затем|и|and\s+then|and|then)\s+(?=\p{L})|,\s+(?=\p{L})""",
        RegexOption.IGNORE_CASE
    )

    /** Commands whose last argument is whatever the user said: an "и" may be part of it. */
    private val takesFreeText = setOf(
        CommandResult.ChatMessage::class, CommandResult.SendMessage::class, CommandResult.PlayMedia::class,
        CommandResult.Mail::class, CommandResult.ReplyMessage::class,
        CommandResult.CreateNote::class, CommandResult.CreateErrand::class, CommandResult.CreateEvent::class,
        CommandResult.WebSearch::class, CommandResult.LookUp::class, CommandResult.FindNearby::class
    )

    fun split(text: String, route: (String) -> CommandResult): Split {
        val parts = parts(text.trim(), route)
        if (parts.size < 2) return Split.Single
        val routed = parts.map(route)
        val kinds = routed.map { it::class }
        if (kinds.distinct().size < 2) return Split.Single
        val laterCommand = kinds.drop(1).any { it != CommandResult.ChatMessage::class }
        val firstTakesText = kinds.first() in takesFreeText
        return when {
            !laterCommand && firstTakesText -> Split.Single
            // After a note or a message, "и позвонить маме" may be the note's
            // text or a second request; only the model can tell which.
            firstTakesText -> Split.Mixed
            routed.none { it is CommandResult.ChatMessage } -> Split.Steps(routed)
            else -> Split.Mixed
        }
    }

    /**
     * The sentence cut at its joiners. A comma alone is a weaker joiner than
     * "и": what follows it is cut off only if it is a command of its own, so
     * "напиши маме, что опоздаю" keeps its message.
     */
    private fun parts(text: String, route: (String) -> CommandResult): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var commaBefore = false
        fun take(end: Int) {
            val piece = text.substring(start, end).trim()
            if (piece.isEmpty()) return
            if (commaBefore && parts.isNotEmpty() && route(piece) is CommandResult.ChatMessage) {
                parts[parts.lastIndex] = parts.last() + ", " + piece
            } else {
                parts += piece
            }
        }
        joiner.findAll(text).forEach { m ->
            take(m.range.first)
            commaBefore = m.value.trim() == ","
            start = m.range.last + 1
        }
        take(text.length)
        return parts
    }
}
