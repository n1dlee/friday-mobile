package com.friday.ai.core.modes

import com.friday.ai.domain.model.CommandResult

/**
 * How modes are talked about.
 *
 * "Создай режим грусти. Это режим, где включается Spotify с грустными
 * песнями." is a creation: the name is what follows "режим" up to the first
 * pause or "это / где / в котором", the rest is the description. "Режим
 * грусти", "включи режим грусти" run it; "выключи режим грусти", "выйди из
 * режима грусти" undo it.
 *
 * Names are compared by [ModeNames].
 */
object ModePhrases {

    private const val MAX_NAME_WORDS = 4

    /** The description's group in [createEn]: after the two alternative name groups. */
    private const val EN_DESCRIPTION = 3

    private val wake = Regex("^(?:пятница|friday)[,!.\\s]+")

    private val create = Regex(
        "^(?:пожалуйста\\s+)?(?:создай|сделай|заведи|добавь|запомни|настрой|придумай)\\s+(?:мне\\s+|новый\\s+)*" +
            "режим\\s+(.+)$",
        RegexOption.DOT_MATCHES_ALL
    )
    private val createEn = Regex(
        "^(?:please\\s+)?(?:create|make|add|set up)\\s+(?:me\\s+)?(?:a\\s+|an\\s+|new\\s+)*" +
            "(?:mode\\s+(?:called\\s+)?(.+?)|(.+?)\\s+mode)" +
            "(?:\\s*[.:;,—–-]\\s*|\\s+(?=(?:where|when|that|which|it)\\s)|$)(.*)$",
        RegexOption.DOT_MATCHES_ALL
    )

    /** Where the name ends and the description begins. */
    private val separator = Regex(
        "\\s*(?:[.:;!?—–]|\\s-\\s|,)\\s*|\\s+(?=(?:это|где|в котором|при котором|когда|чтобы|в нем)\\s)"
    )

    private val descriptionLead = Regex(
        "^(?:это\\s+(?:такой\\s+)?(?:режим|когда)?,?\\s*|где\\s+|в котором\\s+|при котором\\s+|когда\\s+|в нем\\s+|" +
            "чтобы\\s+|where\\s+|when\\s+|that\\s+|which\\s+|it\\s+)+"
    )
    private val nameLead = Regex("^(?:под названием|который называется|называется|с названием|called|named)\\s+")

    private val exit = listOf(
        Regex("^(?:выключи|отключи|останови|заверши|отмени|сними|верни все из|убери)\\s+режим\\p{L}*\\s+(.+)$"),
        Regex("^(?:выйди|выйти|выход|выходим)\\s+из\\s+режим\\p{L}*\\s+(.+)$"),
        Regex("^(?:stop|deactivate|turn off|exit|end|disable|leave)\\s+(?:the\\s+)?(?:mode\\s+(.+)|(.+?)\\s+mode)$")
    )
    private val describe = listOf(
        Regex("^(?:что\\s+(?:делает|включает|будет в|в)|расскажи\\s+(?:про|о)|покажи)\\s+режим\\p{L}*\\s+(.+)$"),
        Regex("^what does (?:the\\s+)?(.+?)\\s+mode do$")
    )
    private val delete = listOf(
        Regex("^(?:удали|сотри|забудь|delete|remove|forget)\\s+(?:режим\\p{L}*|(?:the\\s+)?mode)\\s+(.+)$"),
        Regex("^(?:delete|remove|forget)\\s+(?:the\\s+)?(.+?)\\s+mode$")
    )
    private val addTo = listOf(
        Regex("^(?:добавь|допиши|включи)\\s+в\\s+режим\\p{L}*\\s+(.+)$"),
        Regex("^add\\s+to\\s+(?:the\\s+)?mode\\s+(.+)$")
    )
    private val removeFrom = listOf(
        Regex("^(?:убери|удали|выкинь|исключи)\\s+(.+?)\\s+из\\s+режим\\p{L}*\\s+(.+)$"),
        Regex("^(?:remove|take)\\s+(.+?)\\s+(?:out\\s+)?(?:of|from)\\s+(?:the\\s+)?(.+?)\\s+mode$")
    )

    private val list = Regex(
        "^(?:какие\\s+(?:у\\s+меня\\s+)?(?:есть\\s+)?режимы|мои\\s+режимы|список\\s+режимов|" +
            "(?:list|show)\\s+(?:my\\s+)?modes|" +
            "what modes(?: do i have)?)$"
    )
    private val run = listOf(
        Regex("^(?:(?:включи|запусти|активируй|вруби|давай|поставь|переключись на|перейди в)\\s+)?режим\\s+(.+)$"),
        Regex("^(?:(?:start|activate|turn on|enable|run|switch to)\\s+)?(?:the\\s+)?(.+?)\\s+mode$")
    )

    /**
     * The mode request in [text], or null if it isn't one. A run or exit with
     * a name is only a candidate: "включи режим полета" is a system setting,
     * so the caller checks the name against the saved modes.
     */
    fun parse(text: String): CommandResult.Mode? {
        val t = clean(text)
        return parseCreate(t)
            ?: parseEdit(t)
            ?: CommandResult.Mode.ListAll.takeIf { list.containsMatchIn(t) }
            ?: firstName(t, describe)?.let { CommandResult.Mode.Describe(it) }
            ?: firstName(t, delete)?.let { CommandResult.Mode.Delete(it) }
            ?: firstName(t, exit)?.let { CommandResult.Mode.Exit(it) }
            ?: firstName(t, run)?.let { CommandResult.Mode.Run(it) }
    }

    /** "Добавь в режим …", "убери … из режима …". */
    private fun parseEdit(t: String): CommandResult.Mode? =
        addTo.firstNotNullOfOrNull { it.find(t) }?.let { CommandResult.Mode.AddTo(it.groupValues[1]) }
            ?: removeFrom.firstNotNullOfOrNull { it.find(t) }?.let { m ->
                name(m.groupValues[2])?.let { CommandResult.Mode.RemoveFrom(m.groupValues[1], it) }
            }

    private fun parseCreate(t: String): CommandResult.Mode.Create? =
        create.find(t)?.let { m -> split(m.groupValues[1]) }
            ?: createEn.find(t)?.let { m ->
                name(m.groupValues[1].ifEmpty { m.groupValues[2] })
                    ?.let { CommandResult.Mode.Create(it, description(m.groupValues[EN_DESCRIPTION])) }
            }

    /** "грусти. это режим где …" → name "грусти", description "включается spotify …". */
    private fun split(rest: String): CommandResult.Mode.Create? {
        val cut = separator.find(rest)
        val (rawName, rawDescription) = if (cut != null) {
            rest.substring(0, cut.range.first) to rest.substring(cut.range.last + 1)
        } else {
            // No pause: a short phrase is all name, a long one is "name + what to do".
            val words = rest.split(' ').filter { it.isNotBlank() }
            if (words.size <= MAX_NAME_WORDS - 1) rest to "" else words.first() to words.drop(1).joinToString(" ")
        }
        val name = name(rawName) ?: return null
        return CommandResult.Mode.Create(name, description(rawDescription))
    }

    private fun name(raw: String): String? {
        val n = raw.replace(nameLead, "").trim(' ', '«', '»', '"', '\'', '“', '”', '.', ',', '!', '?')
        val words = n.split(' ').filter { it.isNotBlank() }
        return n.takeIf { words.isNotEmpty() && words.size <= MAX_NAME_WORDS }
    }

    private fun description(raw: String): String? =
        raw.trim().replace(descriptionLead, "").trim(' ', '.', ',', '!', ';', ':').takeIf { it.length > 2 }

    private fun firstName(t: String, patterns: List<Regex>): String? = patterns.firstNotNullOfOrNull { p ->
        p.find(t)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.let(::name)
    }

    private fun clean(text: String): String =
        text.trim().lowercase().replace('ё', 'е').replace(wake, "").trim().trimEnd('.', '!', '?', ' ')
}
