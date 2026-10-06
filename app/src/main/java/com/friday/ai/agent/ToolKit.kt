package com.friday.ai.agent

import com.friday.ai.core.SpokenText

/**
 * Which tools a message is sent with.
 *
 * The full set describes twenty-odd phone actions and costs about 2k tokens
 * on every request — a quarter of Groq's per-minute allowance spent so the
 * model could tell a joke. A message that does not look like a request to do
 * something gets the light kit instead: web search, the calculator, and
 * [ESCALATE], with which the model asks for the phone tools when the guess
 * here was wrong. A wrong guess therefore costs one extra round, not a
 * refusal.
 */
object ToolKit {

    enum class Kit { LIGHT, FULL }

    /** The tool the model calls to get the phone's tools. */
    const val ESCALATE = "use_phone"

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

    /** Things on the phone: mentioning one is a strong hint something is to be done with it. */
    private val phoneNouns = listOf(
        "будильник", "таймер", "музык", "песн", "трек", "звук", "громкост", "фонар", "камер", "фото", "видео",
        "смс", "сообщени", "почт", "письм", "ватсап", "вотсап", "телеграм", "календар", "встреч", "напоминани",
        "заметк", "настройк", "блютус", "блютуз", "вайфай", "wi-fi", "nfc", "приложени", "spotify", "спотифай",
        "ютуб", "youtube", "alarm", "timer", "music", "song", "volume", "flashlight", "camera", "whatsapp",
        "telegram", "calendar", "settings", "bluetooth", "app"
    )

    fun forText(text: String): Kit {
        val words = SpokenText.normalise(text).split(' ').filter { it.isNotBlank() }
        val acts = words.any { w -> actionVerbs.any { w.startsWith(it) } }
        val mentionsPhone = words.any { w -> phoneNouns.any { w.startsWith(it) } }
        return if (acts || mentionsPhone) Kit.FULL else Kit.LIGHT
    }
}
