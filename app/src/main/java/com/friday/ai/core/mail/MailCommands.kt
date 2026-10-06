// Long lines on purpose: a table of regexes, each kept on one line so it can be read whole.
@file:Suppress("MaxLineLength")

package com.friday.ai.core.mail

/**
 * Recognises mail requests in what the user said.
 *
 * Pure text work: deciding whether "ответь Ивану: буду в пять" is a mail
 * reply needs no network. Whether Иван actually wrote to you is settled later
 * — and if he did not, the sentence goes back to being ordinary conversation.
 */
object MailCommands {

    sealed interface Request {
        data object CheckUnread : Request

        /** [from] is the spoken sender ("Ивана", "Google"), or null for the newest. */
        data class Read(val from: String?) : Request

        data class Reply(val to: String, val body: String) : Request

        data class Compose(val to: String, val body: String) : Request
    }

    private val I = setOf(RegexOption.IGNORE_CASE)

    // Separators people (and Whisper) put between a name and what to say.
    private const val SEP = """\s*(?::|,|\s+что\s+|\s+чтобы\s+|\s+с\s+текстом\s+|\s+saying\s+|\s+that\s+)\s*"""
    private const val MAIL_NOUN = """(?:письмо|имейл|е-?мейл|email|e-mail|мейл)"""

    private val check = listOf(
        // Not "открой почту": that one opens the mail app, as it always has.
        Regex("""(?:проверь|посмотри)\s+(?:мою\s+|мне\s+)?(?:почту|ящик|имейл|email|мейл)""", I),
        Regex("""(?:нов\p{L}*|непрочитанн\p{L}*)\s+(?:письм\p{L}*|имейл\p{L}*|мейл\p{L}*)""", I),
        Regex("""что\s+(?:нового\s+)?(?:в|на)\s+(?:почте|ящике)""", I),
        Regex("""(?:check|any)\b.*\b(?:e-?mails?|mail|inbox)""", I),
        Regex("""unread\s+e-?mails?""", I)
    )

    private val read = listOf(
        Regex("""^(?:прочитай|прочти|зачитай|открой)\s+(?:мне\s+)?(?:последнее\s+|новое\s+|свежее\s+)?$MAIL_NOUN(?:\s+от\s+(.+?))?[.!?]*$""", I),
        Regex("""^read\s+(?:me\s+)?(?:the\s+|my\s+)?(?:last\s+|latest\s+|newest\s+)?e-?mail(?:\s+from\s+(.+?))?[.!?]*$""", I)
    )

    private val compose = listOf(
        Regex("""^(?:напиши|отправь)\s+$MAIL_NOUN\s+(.+?)$SEP(.+)$""", I),
        Regex("""^напиши\s+(.+?)\s+на\s+почту(?:$SEP|\s+)(.+)$""", I),
        Regex("""^(?:send\s+(?:an\s+)?e-?mail\s+to|e-?mail)\s+(.+?)$SEP(.+)$""", I),
        // No separator heard: take the first word as the name. The read-back
        // before sending shows the split, so a wrong one costs one "нет".
        Regex("""^(?:напиши|отправь)\s+$MAIL_NOUN\s+(\p{L}+)\s+(.+)$""", I)
    )

    private val reply = listOf(
        Regex("""^ответь\s+(?:на\s+письмо\s+(?:от\s+)?)?(.+?)$SEP(.+)$""", I),
        Regex("""^reply\s+to\s+(.+?)$SEP(.+)$""", I),
        Regex("""^ответь\s+(?:на\s+письмо\s+(?:от\s+)?)?(\p{L}+)\s+(.+)$""", I)
    )

    /**
     * Words that follow "ответь" without being a person: "ответь мне",
     * "ответь честно", "ответь на вопрос". Kept short on purpose — an unknown
     * name is checked against the inbox anyway and falls back to chat.
     */
    private val notAPerson = setOf(
        "мне", "нам", "на", "честно", "коротко", "кратко", "подробно", "быстро",
        "пожалуйста", "нормально", "правду", "сейчас", "me", "briefly", "honestly"
    )

    /** Longer than this, the "name" has swallowed part of the message. */
    private const val MAX_NAME_WORDS = 3

    fun parse(text: String): Request? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return readRequest(t)
            ?: addressed(compose, t)?.let { (to, body) -> Request.Compose(to, body) }
            ?: addressed(reply, t)?.let { (to, body) -> Request.Reply(to, body) }
            ?: Request.CheckUnread.takeIf { check.any { it.containsMatchIn(t) } }
    }

    private fun readRequest(t: String): Request.Read? =
        read.firstNotNullOfOrNull { it.find(t) }
            ?.let { m -> Request.Read(m.groupValues.getOrNull(1)?.let(::cleanName)?.ifBlank { null }) }

    private fun addressed(patterns: List<Regex>, t: String): Pair<String, String>? =
        patterns.firstNotNullOfOrNull { it.find(t) }?.let { m -> person(m.groupValues[1], m.groupValues[2]) }

    private fun person(rawName: String, rawBody: String): Pair<String, String>? {
        val name = cleanName(rawName)
        val body = rawBody.trim().trimEnd('.', ' ')
        val words = name.split(' ').filter { it.isNotBlank() }
        val plausible = words.isNotEmpty() && body.isNotBlank() &&
            words.size <= MAX_NAME_WORDS && words.first().lowercase() !in notAPerson
        return if (plausible) name to body else null
    }

    private fun cleanName(raw: String): String =
        raw.trim().trim('.', ',', ':', '!', '?', '«', '»', '"').trim()
            .removePrefix("от ").trim()
}
