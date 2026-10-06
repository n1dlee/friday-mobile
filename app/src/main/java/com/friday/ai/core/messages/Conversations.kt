package com.friday.ai.core.messages

import com.friday.ai.core.ContactMatcher

/** One incoming message, as the messenger's notification shows it. */
data class InboxMessage(val sender: String, val text: String, val time: Long)

/**
 * A chat that has notified recently — a person or a group, in one app.
 *
 * @param title who the chat is with: the person, or the group's name
 * @param canReply the notification carries a reply field, so Friday can
 *   answer without opening the app
 */
data class Conversation(
    val key: String,
    val packageName: String,
    val app: String,
    val title: String,
    val messages: List<InboxMessage>,
    val canReply: Boolean,
    val updatedAt: Long,
    val isGroup: Boolean = false
)

/**
 * Finding and retelling chats. Pure, so the choices — which chat "маме"
 * means, what is worth reading out — are tested without a phone.
 */
object Conversations {

    /** Words that mean "whoever wrote last": "ответь ей", "что он пишет". */
    private val PRONOUNS = setOf("ему", "ей", "им", "him", "her", "them", "он", "она", "они")

    /** Longer messages are cut when read aloud; the rest is on the screen. */
    private const val SPOKEN_MESSAGE_CHARS = 160

    /** At most this many messages per chat are read, newest last. */
    private const val SPOKEN_MESSAGES = 3

    fun isPronoun(who: String?): Boolean = who?.trim()?.lowercase() in PRONOUNS

    /**
     * The chat [who] means: by its title, the way contacts are matched
     * ("маме" finds "Мамуля"), or the latest chat for a pronoun or nobody.
     */
    fun find(who: String?, chats: List<Conversation>): Conversation? {
        val newestFirst = chats.sortedByDescending { it.updatedAt }
        if (who.isNullOrBlank() || isPronoun(who)) return newestFirst.firstOrNull()
        return ContactMatcher.ranked(who, newestFirst) { it.title }.firstOrNull()?.first
    }

    /** "Мама в WhatsApp: «Ты где?» «Позвони». Иван в Telegram: «Ок»." */
    fun retell(chats: List<Conversation>, russian: Boolean): String {
        if (chats.isEmpty()) return if (russian) "Новых сообщений нет." else "No new messages."
        return chats.sortedByDescending { it.updatedAt }.joinToString(" ") { chat ->
            val said = chat.messages.takeLast(SPOKEN_MESSAGES).joinToString(" ") { m ->
                val who = if (chat.isGroup) "${m.sender}: " else ""
                "«$who${m.text.take(SPOKEN_MESSAGE_CHARS)}»"
            }
            val where = if (russian) "в ${chat.app}" else "on ${chat.app}"
            "${chat.title} $where: $said."
        }
    }

    /** What is said when a message arrives. */
    fun announcement(chat: Conversation, message: InboxMessage, russian: Boolean): String {
        val who = if (chat.isGroup) "${message.sender} ${if (russian) "в" else "in"} «${chat.title}»" else chat.title
        val where = if (russian) "в ${chat.app}" else "on ${chat.app}"
        return "$who $where: «${message.text.take(SPOKEN_MESSAGE_CHARS)}»"
    }

    /** What is said when the phone rings. */
    fun incomingCall(caller: String, app: String?, russian: Boolean): String = when {
        app == null && russian -> "Звонит $caller"
        app == null -> "$caller is calling"
        russian -> "$caller звонит в $app"
        else -> "$caller is calling on $app"
    }

    fun confirmReply(chat: Conversation, text: String, russian: Boolean): String =
        if (russian) "Ответить «${chat.title}» в ${chat.app}: «$text»?"
        else "Reply to ${chat.title} on ${chat.app}: \"$text\"?"
}
