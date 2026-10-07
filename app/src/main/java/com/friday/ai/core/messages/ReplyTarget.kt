package com.friday.ai.core.messages

import com.friday.ai.core.ContactMatcher

/**
 * Who "ответь …" is for, what to send, and whether to send it at once.
 *
 * Right after Friday reads or announces a message, "ответь хорошо" is
 * plainly an answer to that chat: it is sent straight away, the way the
 * owner asked. Anything less certain — another chat, or nothing read just
 * now — is read back first ("Ответить «Мама» в WhatsApp: «…»?").
 */
object ReplyTarget {

    data class Choice(val chat: Conversation, val text: String, val sendNow: Boolean)

    /** A word must match a chat this well to be taken as its name ("маме" → Мама). */
    private const val NAME_SCORE = 70

    private val leadingThat = Regex("^(?:что|чтобы|that|saying)\\s+", RegexOption.IGNORE_CASE)

    /**
     * @param who      the person the phrase seemed to name ("ответь маме: …"), if any
     * @param loose    everything after "ответь" when nothing separated a name from
     *                 the message, so [who] is only a guess ("ответь хорошо спасибо")
     * @param known    every chat Friday can still answer, read or not
     * @param justRead the chat Friday read or announced in the last few minutes
     */
    fun choose(
        who: String?,
        text: String,
        loose: String?,
        known: List<Conversation>,
        justRead: Conversation?
    ): Choice? {
        if (who == null || Conversations.isPronoun(who)) {
            return named(text, known)?.let { (chat, rest) -> Choice(chat, clean(rest), chat.key == justRead?.key) }
                ?: unnamed(text, known, justRead)
        }
        return Conversations.find(who, known)?.let { Choice(it, clean(text), sendNow = it.key == justRead?.key) }
            // "хорошо" in "ответь хорошо спасибо" was never a name: the whole thing is the answer.
            ?: loose?.takeIf { wordsNotName(who, it) }?.let { unnamed(it, known, justRead) }
    }

    /** Whether a reply that matched no chat should go to e-mail instead. */
    fun isMailFor(who: String?, loose: String?): Boolean =
        who != null && !Conversations.isPronoun(who) && !wordsNotName(who, loose)

    private fun unnamed(text: String, known: List<Conversation>, justRead: Conversation?): Choice? =
        justRead?.let { Choice(it, clean(text), sendNow = true) }
            ?: Conversations.find(null, known)?.let { Choice(it, clean(text), sendNow = false) }

    /**
     * Speech recognition writes names with a capital and ordinary words
     * without: "ответь Ивану буду в пять" names someone, "ответь хорошо
     * спасибо" does not.
     */
    private fun wordsNotName(who: String, loose: String?): Boolean =
        loose != null && who.firstOrNull()?.isLowerCase() == true

    /** "маме хорошо" → (Мама, "хорошо"); null when the first word names no chat. */
    private fun named(text: String, known: List<Conversation>): Pair<Conversation, String>? {
        val words = text.trim().split(Regex("\\s+"))
        val first = words.first().trim(',', ':', '.', '—', '-')
        if (first.isEmpty()) return null
        val best = ContactMatcher.ranked(first, known) { it.title }.firstOrNull()
            ?.takeIf { it.second >= NAME_SCORE } ?: return null
        return best.first to words.drop(1).joinToString(" ").trimStart(',', ':', '—', '-', ' ')
    }

    /** What goes into the chat: no "что" in front, no full stop dictated at the end. */
    fun clean(text: String): String =
        text.trim().replace(leadingThat, "").trim().removeSuffix(".").trim()
}
