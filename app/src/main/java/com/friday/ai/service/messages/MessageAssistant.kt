package com.friday.ai.service.messages

import android.content.Context
import com.friday.ai.core.mail.Confirmation
import com.friday.ai.core.mail.MailCommands
import com.friday.ai.core.messages.Conversation
import com.friday.ai.core.messages.Conversations
import com.friday.ai.service.mail.MailAssistant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Something Friday should say unprompted — a call, a message.
 *
 * @param listenAfter keep the microphone open afterwards, so "ответь, что
 *   еду" can follow a message without the wake word
 */
data class Announcement(val text: String, val listenAfter: Boolean)

/**
 * Carries announcements from the notification listener, which hears about
 * calls and messages, to the voice service, which can speak. Dropped when
 * the voice service is not running — nobody is there to hear them.
 */
class Announcer {
    private val events = MutableSharedFlow<Announcement>(extraBufferCapacity = 8)
    val announcements: SharedFlow<Announcement> = events.asSharedFlow()

    fun say(announcement: Announcement) {
        events.tryEmit(announcement)
    }
}

/**
 * Reads chats out and answers them: "прочитай сообщения", "что пишет мама",
 * "ответь маме, что буду через десять минут".
 *
 * A reply is read back before it goes — a misheard word in a message sent
 * in the user's name cannot be taken back. A reply to someone with no recent
 * chat is an e-mail reply, as "ответь Ивану" always was.
 */
class MessageAssistant(
    private val context: Context,
    private val inbox: MessengerInbox,
    private val mail: MailAssistant,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private class Pending(val chat: Conversation, val text: String, val at: Long)

    private companion object {
        /** An unanswered "Ответить?" expires: a stray "да" later must not send it. */
        const val PENDING_TTL_MS = 2 * 60 * 1000L
    }

    @Volatile
    private var pending: Pending? = null

    fun read(who: String?, russian: Boolean): String {
        val chats = inbox.chats()
        if (who != null && !Conversations.isPronoun(who)) {
            val chat = Conversations.find(who, chats)
                ?: return if (russian) "От «$who» новых сообщений нет." else "Nothing new from $who."
            inbox.dismiss(chat.key)
            return Conversations.retell(listOf(chat), russian)
        }
        val text = Conversations.retell(chats, russian)
        chats.forEach { inbox.dismiss(it.key) }
        return text
    }

    suspend fun reply(who: String?, text: String, russian: Boolean): String {
        val chat = Conversations.find(who, inbox.chats())
        return when {
            chat == null && who != null && !Conversations.isPronoun(who) ->
                mail.handle(MailCommands.Request.Reply(who, text), russian).text
            chat == null -> if (russian) "Не знаю, кому ответить: новых сообщений нет." else "Nobody to reply to."
            !chat.canReply ->
                if (russian) "${chat.app} не даёт ответить из уведомления — откройте чат."
                else "${chat.app} doesn't allow replies from its notification — open the chat."
            else -> {
                pending = Pending(chat, text, clock())
                Conversations.confirmReply(chat, text, russian)
            }
        }
    }

    /** A yes or no to a pending reply; null when nothing is pending or [text] is neither. */
    fun answerPending(text: String, russian: Boolean): String? {
        val p = pending?.takeIf { clock() - it.at < PENDING_TTL_MS } ?: return null
        return when (Confirmation.classify(text)) {
            Confirmation.Answer.OTHER -> null
            Confirmation.Answer.NO -> {
                pending = null
                if (russian) "Не отправляю." else "Not sent."
            }
            Confirmation.Answer.YES -> {
                pending = null
                val sent = inbox.reply(context, p.chat, p.text)
                if (sent) inbox.dismiss(p.chat.key)
                when {
                    sent && russian -> "Отправила."
                    sent -> "Sent."
                    russian -> "Уведомление уже закрыто — ответить из него нельзя. Откройте ${p.chat.app}."
                    else -> "The notification is gone, so I can't reply from it. Open ${p.chat.app}."
                }
            }
        }
    }
}
