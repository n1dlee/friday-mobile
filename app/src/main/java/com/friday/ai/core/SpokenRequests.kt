// Long lines on purpose: regexes, each kept on one line so it can be read whole.
@file:Suppress("MaxLineLength")

package com.friday.ai.core

import com.friday.ai.core.people.Channel
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.MediaKind

/**
 * "Напиши маме в ватсап, что я опоздаю", "смс Ивану: буду в пять".
 *
 * Only phrasings that say it is a message — "смс", "сообщение", a messenger's
 * name — are read here. "Напиши маме, что опоздаю" could as well be "write
 * an essay that…", and goes to the model, which has the same tool.
 */
object MessageRequest {

    private val I = RegexOption.IGNORE_CASE

    private val messageWord = Regex("""(?:^|\s)(?:смс|sms|эсэмэс|сообщени\p{L}*|message|text)(?=\s|$)""", I)
    private val channelPhrase = Regex("""\s+(?:в|во|по|через|on|via|in)\s+(?:ватсап\p{L}*|вотсап\p{L}*|вацап\p{L}*|whatsapp|телеграм\p{L}*|телег\p{L}*|telegram)""", I)

    private const val VERB = """(?:напиши|отправь|скинь|пошли|send|text|message)"""
    // ", что" before a bare ",": otherwise "что" ends up in the message.
    private const val SEPARATOR = """\s*(?::|,?\s+(?:что|чтобы|текст(?:ом)?|с\s+текстом|saying|that)\s+|,)\s*"""
    private val withBody = Regex("""^$VERB\s+(\p{L}+(?:\s+\p{L}+)?)$SEPARATOR(.+)$""", I)
    private val withoutBody = Regex("""^$VERB\s+(\p{L}+(?:\s+\p{L}+)?)[.!]*$""", I)

    fun parse(text: String): CommandResult.SendMessage? {
        val t = text.trim()
        val via = Channel.named(t)
        if (via == null && !messageWord.containsMatchIn(t)) return null
        // The channel and the word "смс" say how, not to whom: taken out so
        // what is left reads "напиши маме: …".
        val core = t.replace(channelPhrase, "")
            .replace(Regex("""^($VERB)\s+(?:(?:смс|sms|эсэмэс|сообщение|message|a\s+message)\s+)""", I), "$1 ")
            .replace(Regex("""^(?:смс|sms)\s+""", I), "напиши ")
            .trim()
        return withBody.find(core)?.let { m ->
            CommandResult.SendMessage(m.groupValues[1].trim(), m.groupValues[2].trim(), via)
        } ?: withoutBody.find(core)?.let { m -> CommandResult.SendMessage(m.groupValues[1].trim(), null, via) }
    }
}

/**
 * "Прочитай сообщения", "что пишет мама", "ответь, что еду".
 *
 * Replies naming someone ("ответь маме: …") are read by the mail patterns,
 * which already handle names and separators, and turned into a chat reply by
 * the router. Here are only the ones without a name — an answer to whoever
 * wrote last, typically right after Friday read a message out.
 */
object ChatRequest {

    private val I = RegexOption.IGNORE_CASE

    private val read = listOf(
        Regex("""^(?:прочитай|прочти|зачитай|покажи)\s+(?:мне\s+)?(?:новые\s+|последние\s+|непрочитанные\s+)?(?:сообщени\p{L}*|смс|эсэмэс)(?:\s+от\s+(.+?))?[.?!]*$""", I),
        Regex("""^что\s+(?:мне\s+)?(?:пишет|написал\p{L}*)\s+(.+?)[.?!]*$""", I),
        Regex("""^(?:что\s+(?:мне\s+)?(?:пишут|написали)|(?:есть|какие)\s+(?:новые\s+)?сообщени\p{L}*)[.?!]*$""", I),
        Regex("""^read\s+(?:me\s+)?(?:my\s+)?(?:new\s+|latest\s+)?(?:messages|texts)(?:\s+from\s+(.+?))?[.?!]*$""", I),
        Regex("""^what\s+did\s+(.+?)\s+(?:write|say|text)[.?!]*$""", I),
        Regex("""^(?:any\s+new\s+messages)[.?!]*$""", I),
        // "Есть непрочитанные сообщения?", "какие сообщения я не прочёл?", "кто мне писал?"
        Regex("""^(?:есть\s+)?(?:(?:у\s+меня|ещё|еще)\s+)*(?:есть\s+)?непрочитанн\p{L}*(?:\s+сообщени\p{L}*)?[.?!]*$""", I),
        Regex("""^(?:какие|чьи)\s+сообщени\p{L}*\s+я\s+(?:ещё\s+|еще\s+)?не\s+(?:(?:про)?чита|проч[её]л)\p{L}*[.?!]*$""", I),
        Regex("""^кто\s+(?:мне\s+)?(?:писал|написал)\p{L}*[.?!]*$""", I),
        Regex("""^(?:do\s+i\s+have\s+)?(?:any\s+)?unread\s+messages[.?!]*$|^who\s+(?:texted|wrote|messaged)\s+me[.?!]*$""", I)
    )

    private val replyToLast = Regex("""^(?:ответь|reply)\s*(?::\s*|,?\s+(?:что|чтобы|that|saying)\s+|,\s*)(.+)$""", I)

    /** "Ответь хорошо", "ответь. Хорошо." — nothing between the verb and the message. */
    private val replyBare = Regex("""^(?:ответь|reply)[\s.!—–-]+(.+)$""", I)
    private val separator = Regex("""[:,]|\s(?:что|чтобы|that|saying)\s""", I)

    /**
     * A reply with no separator, tried after the mail patterns (which own
     * "ответь Ивану буду в пять"); [com.friday.ai.core.messages.ReplyTarget]
     * settles who it is for.
     */
    fun parseBareReply(text: String): CommandResult.ReplyMessage? =
        looseReply(text)?.let { CommandResult.ReplyMessage(null, it, loose = it) }

    /** Everything after "ответь" when no pause or "что" marks off a name; null otherwise. */
    fun looseReply(text: String): String? =
        replyBare.find(text.trim())?.groupValues?.get(1)?.trim()?.takeUnless { separator.containsMatchIn(it) }

    fun parse(text: String): CommandResult? {
        val t = text.trim()
        replyToLast.find(t)?.let { return CommandResult.ReplyMessage(null, it.groupValues[1].trim()) }
        val m = read.firstNotNullOfOrNull { it.find(t) } ?: return null
        return CommandResult.ReadMessages(m.groupValues.getOrNull(1)?.trim()?.ifBlank { null })
    }
}

/**
 * "Позвони маме", "набери Ивана по ватсапу", "позвони папе по обычной связи".
 *
 * How to call is only taken from words that say it; otherwise the choice is
 * left to [com.friday.ai.core.people.ChannelChooser].
 */
object CallRequest {

    private val I = RegexOption.IGNORE_CASE
    private val call = Regex("""^(?:позвони|позвонить|звонок|набери|call|dial|ring)\s+(.+?)[.!]*$""", I)
    private val viaApp = Regex(
        """\s+(?:по|в|во|через|on|via|in|over)\s+(ватсап\p{L}*|вотсап\p{L}*|вацап\p{L}*|whatsapp|телеграм\p{L}*|телег\p{L}*|telegram)""", I
    )
    private val viaLine = Regex(
        """\s+(?:по\s+(?:телефону|сотовой|обычной\s+связи|номеру)|обычным\s+звонком|on\s+the\s+phone|by\s+phone|normally)""", I
    )

    fun parse(text: String): CommandResult.PhoneCall? {
        val target = call.find(text.trim())?.groupValues?.get(1) ?: return null
        val app = viaApp.find(target)?.let { Channel.named(it.groupValues[1]) }
        val line = viaLine.containsMatchIn(target)
        val who = target.replace(viaApp, "").replace(viaLine, "").trim()
        if (who.isBlank()) return null
        return CommandResult.PhoneCall(who, app ?: Channel.SMS.takeIf { line })
    }
}

/**
 * "Включи Believer в спотифае", "найди на ютубе обзор айфона", "включи песню
 * Shape of You": something to search for and play.
 *
 * A bare "включи музыку" or "включи спотифай" is not a search — it means
 * resume whatever is loaded, and stays with the playback controls.
 */
object MediaRequest {

    private val I = RegexOption.IGNORE_CASE
    private const val VERB = """(?:включи|поставь|запусти|найди|покажи|открой|play|put\s+on|find|search|show)"""
    private const val YOUTUBE = """(?:ютуб\p{L}*|ютюб\p{L}*|youtube)"""

    private val youtubeFirst = Regex("""^$VERB\s+(?:на|в|on|in)\s+$YOUTUBE\s+(.+)$""", I)
    private val youtubeLast = Regex("""^$VERB\s+(.+?)\s+(?:на|в|on|in)\s+$YOUTUBE[.!]*$""", I)
    private val inApp = Regex("""^$VERB\s+(.+?)\s+(?:на|в|во|on|in)\s+(.+?)[.!]*$""", I)
    private val namedSong = Regex("""^(?:включи|поставь|play|put\s+on)\s+(?:песню|трек|альбом|song|track|album)\s+(.+?)[.!]*$""", I)

    /** "Музыку", "что-нибудь": nothing to search for. */
    private val nothingInParticular = Regex("""^(?:музык\p{L}*|что-?нибудь|что-?то|music|something|anything)$""", I)

    fun parse(text: String): CommandResult.PlayMedia? {
        val t = text.trim()
        return (youtubeFirst.find(t) ?: youtubeLast.find(t))?.let { video(it.groupValues[1]) }
            // Only a music app's name makes this a search ("в машине" does not).
            ?: inApp.find(t)?.takeIf { MusicApps.match(it.groupValues[2]) != null }
                ?.let { music(it.groupValues[1], it.groupValues[2]) }
            ?: namedSong.find(t)?.let { music(it.groupValues[1], null) }
    }

    private fun video(query: String) = query.trim().takeUnless { it.isBlank() }
        ?.let { CommandResult.PlayMedia(it, MediaKind.VIDEO, "youtube") }

    private fun music(query: String, app: String?) = query.trim()
        .takeUnless { it.isBlank() || nothingInParticular.matches(it) }
        ?.let { CommandResult.PlayMedia(it, MediaKind.MUSIC, app?.trim()) }
}
