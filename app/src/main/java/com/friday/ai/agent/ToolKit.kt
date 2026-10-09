package com.friday.ai.agent

import com.friday.ai.core.SpokenText

/**
 * Which tools a message is sent with.
 *
 * Groq's free tier allows 8,000 tokens a minute, and every round of an
 * answer carries the tools again. The whole set is about 2,000 tokens; with
 * the instructions, the memory and the conversation, one command that took
 * two or three rounds ran over the minute by itself, and the answer came
 * from the weaker backup model.
 *
 * So a message gets only what it can use:
 *  - **Light** — talk: web search, the calculator, and [ESCALATE].
 *  - **Focused** — the groups the message is about ("поставь будильник" →
 *    alarms and timers; "напиши маме" → people), plus the light tools.
 *  - **Full** — something is clearly to be done, but nothing says what.
 *
 * A wrong guess costs one round, not a refusal: the model calls [ESCALATE]
 * and the same turn is repeated with every tool.
 */
object ToolKit {

    sealed interface Kit {
        data object LIGHT : Kit
        data object FULL : Kit

        /** The tools of [groups], plus the light ones. */
        data class Focused(val groups: Set<Group>) : Kit
    }

    /** Tools that belong together, and the words that call for them. */
    enum class Group(val tools: Set<String>, val stems: List<String>) {
        TIME(
            setOf("set_alarm", "set_timer"),
            listOf("будильник", "разбуд", "проснут", "таймер", "засек", "alarm", "timer", "wake")
        ),
        PEOPLE(
            setOf("call", "send_message", "read_messages", "reply_message", "mail"),
            listOf(
                "позвон", "набер", "набери", "звонок", "напиш", "отправ", "скин", "пошли", "ответ", "сообщени", "смс",
                "эсэмэс", "ватсап", "вотсап", "телеграм", "прочитай", "прочти", "зачитай", "пишет", "написал", "почт",
                "письм", "мейл", "непрочитан", "call", "dial", "text", "message", "reply", "whatsapp", "telegram",
                "mail", "email"
            )
        ),
        MEDIA(
            setOf("play", "media"),
            listOf(
                "музык", "песн", "трек", "альбом", "плейлист", "исполнител", "спотифай", "ютуб", "клип", "пауз",
                "следующ", "предыдущ", "перемотай", "spotify", "youtube", "music", "song", "track", "album",
                "playlist", "play", "pause", "skip", "next", "resume"
            )
        ),
        PHONE(
            setOf("phone_control", "flashlight", "open_settings", "camera", "voice_recorder", "open_app"),
            listOf(
                "громк", "громче", "погромч", "тише", "потиш", "звук", "убав", "прибав", "фонар", "яркост",
                "вайфай", "wi-fi", "блютус",
                "блютуз", "беспокоить", "беззвуч", "вибр", "настройк", "камер", "фото", "селфи", "сфотограф", "сними",
                "диктофон", "приложени", "nfc", "volume", "louder", "quieter", "mute", "flashlight", "torch",
                "brightness", "wifi", "bluetooth", "settings", "camera", "photo", "selfie", "recorder", "app"
            )
        ),
        PLANS(
            setOf("create_event", "move_event", "create_note", "remind_near_place"),
            listOf(
                "календар", "встреч", "событи", "перенес", "заметк", "запиш", "напомн", "напоминани", "calendar",
                "meeting", "event", "note", "remind", "reschedule"
            )
        ),
        INFO(
            setOf("weather", "find_nearby", "briefing"),
            listOf(
                "погод", "дожд", "градус", "рядом", "ближайш", "поблизост", "аптек", "сводк", "пропустил", "weather",
                "rain", "nearby", "nearest", "briefing", "missed"
            )
        ),
        MODES(setOf("run_mode"), listOf("режим", "mode"))
    }

    /** The tool the model calls to get the phone's tools. */
    const val ESCALATE = "use_phone"

    /** What any kit carries: looking things up and arithmetic. */
    val LIGHT_TOOLS = setOf("web_search", "calculate")

    /**
     * Verbs that ask for something to be done, as stems: "включи",
     * "поставь", "напиши", "call", "turn". Matched at a word's start, so
     * "постав" covers "поставь" and "поставить".
     */
    private val actionVerbs = listOf(
        "включ", "выключ", "отключ", "постав", "установ", "заведи", "открой", "откро", "закрой", "запуст",
        "останов", "позвон", "набер", "набери", "напиш", "отправ", "скин", "пошли", "ответ", "прочитай", "прочти",
        "зачитай", "найди", "запиш", "напомн", "разбуд", "засек", "сделай", "убав", "прибав", "перенес", "добав",
        "сфотограф", "сними", "проверь", "переключ", "верни", "громче", "тише", "пауз", "стоп",
        "turn", "set", "call", "text", "send", "open", "close", "start", "stop", "play", "pause", "remind", "wake",
        "take", "find", "read", "reply", "check", "message", "dial", "skip", "mute"
    )

    fun forText(text: String): Kit {
        val words = SpokenText.normalise(text).split(' ').filter { it.isNotBlank() }
        val groups = Group.entries.filter { g -> words.any { w -> g.stems.any { w.startsWith(it) } } }.toSet()
        val acts = words.any { w -> actionVerbs.any { w.startsWith(it) } }
        return when {
            groups.isNotEmpty() -> Kit.Focused(groups)
            // Something is to be done, but nothing says what: everything.
            acts -> Kit.FULL
            else -> Kit.LIGHT
        }
    }

    /** The names of the tools in [kit]; null for every tool. */
    fun names(kit: Kit): Set<String>? = when (kit) {
        Kit.FULL -> null
        Kit.LIGHT -> LIGHT_TOOLS
        is Kit.Focused -> LIGHT_TOOLS + kit.groups.flatMap { it.tools }
    }
}
