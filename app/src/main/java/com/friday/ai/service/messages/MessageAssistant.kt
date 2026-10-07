package com.friday.ai.service.messages

import android.content.Context
import com.friday.ai.core.mail.Confirmation
import com.friday.ai.core.mail.MailCommands
import com.friday.ai.core.messages.Conversation
import com.friday.ai.core.messages.Conversations
import com.friday.ai.core.messages.ReplyTarget
import com.friday.ai.core.messages.Unread
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
 * Reads chats out and answers them: "есть непрочитанные?", "что пишет мама",
 * "ответь маме, что буду через десять минут".
 *
 * Nothing is read out when it arrives: messages wait in memory until the
 * owner asks. Then Friday says who wrote and asks whose to read first; a
 * name in answer reads that chat — retold in a sentence or two when it is
 * long — and the rest are named again.
 *
 * A reply to the chat Friday has just read out goes at once ("ответь
 * хорошо"): which chat is meant is beyond doubt, and the owner hears exactly
 * what was sent. Any other reply is read back before it goes — a misheard
 * word in a message sent in the user's name cannot be taken back. A reply to
 * someone with no recent chat is an e-mail reply, as "ответь Ивану" always was.
 */
class MessageAssistant(
    private val context: Context,
    private val inbox: MessengerInbox,
    private val mail: MailAssistant,
    private val summarize: suspend (Conversation, Boolean) -> String? = { _, _ -> null },
    private val clock: () -> Long = System::currentTimeMillis
) {

    private class Pending(val chat: Conversation, val text: String, val at: Long)

    private companion object {
        /** An unanswered "Ответить?" expires: a stray "да" later must not send it. */
        const val PENDING_TTL_MS = 2 * 60 * 1000L
    }

    @Volatile
    private var pending: Pending? = null

    /** When "чьё прочитать первым?" was last asked; a name said soon after picks a chat. */
    @Volatile
    private var askedAt: Long? = null

    @Volatile
    private var followUp = false

    suspend fun read(who: String?, russian: Boolean): String {
        val chats = inbox.chats(clock())
        if (who != null && !Conversations.isPronoun(who)) {
            val chat = Conversations.find(who, chats)
                ?: return if (russian) "От «$who» новых сообщений нет." else "Nothing new from $who."
            return tell(chat, russian)
        }
        return when (chats.size) {
            0 -> if (russian) "Непрочитанных сообщений нет." else "No unread messages."
            1 -> tell(chats.single(), russian)
            else -> {
                askedAt = clock()
                Unread.whoWrote(chats, russian)
            }
        }
    }

    /** Whether the last answer to [answerPending] expects the owner to say more (a reply, a name). */
    fun takeFollowUp(): Boolean = followUp.also { followUp = false }

    /** Reads one chat and marks it read; names the ones still waiting. */
    private suspend fun tell(chat: Conversation, russian: Boolean): String {
        val summary = if (Unread.isLong(chat)) summarize(chat, russian) else null
        inbox.markRead(chat.key, clock())
        val told = Unread.tell(chat, summary, russian)
        val rest = inbox.chats(clock())
        askedAt = if (rest.isEmpty()) null else clock()
        return if (rest.isEmpty()) told else told + " " + Unread.stillUnread(rest, russian)
    }

    /**
     * @param loose everything after "ответь" when no pause or "что" split a
     *   name from the message — [who] is then only a guess
     */
    suspend fun reply(who: String?, text: String, russian: Boolean, loose: String? = null): String {
        val now = clock()
        val choice = ReplyTarget.choose(who, text, loose, inbox.answerable(now), inbox.justRead(now))
        val chat = choice?.chat
        return when {
            chat == null && ReplyTarget.isMailFor(who, loose) ->
                mail.handle(MailCommands.Request.Reply(who.orEmpty(), text), russian).text
            chat == null -> if (russian) "Не знаю, кому ответить: новых сообщений нет." else "Nobody to reply to."
            !chat.canReply ->
                if (russian) "${chat.app} не даёт ответить из уведомления — откройте чат."
                else "${chat.app} doesn't allow replies from its notification — open the chat."
            choice.text.isBlank() -> if (russian) "Что ответить?" else "What should I reply?"
            choice.sendNow -> send(chat, choice.text, russian)
            else -> {
                pending = Pending(chat, choice.text, now)
                Conversations.confirmReply(chat, choice.text, russian)
            }
        }
    }

    /**
     * A yes or no to a pending reply, or a name in answer to "чьё прочитать
     * первым?"; null when nothing is pending or [text] is neither.
     */
    suspend fun answerPending(text: String, russian: Boolean): String? =
        answerReply(text, russian) ?: answerWhich(text, russian)

    private suspend fun answerWhich(text: String, russian: Boolean): String? {
        if (askedAt?.let { clock() - it < PENDING_TTL_MS } != true) return null
        val unread = inbox.chats(clock()).takeIf { it.isNotEmpty() } ?: return null
        val chat = Unread.picked(text, unread) ?: when (Confirmation.classify(text)) {
            Confirmation.Answer.YES -> Conversations.find(null, unread)
            Confirmation.Answer.NO -> {
                askedAt = null
                return if (russian) "Хорошо, позже." else "Okay, later."
            }
            Confirmation.Answer.OTHER -> null
        } ?: return null
        followUp = true
        return tell(chat, russian)
    }

    private fun answerReply(text: String, russian: Boolean): String? {
        val p = pending?.takeIf { clock() - it.at < PENDING_TTL_MS } ?: return null
        return when (Confirmation.classify(text)) {
            Confirmation.Answer.OTHER -> null
            Confirmation.Answer.NO -> {
                pending = null
                if (russian) "Не отправляю." else "Not sent."
            }
            Confirmation.Answer.YES -> {
                pending = null
                send(p.chat, p.text, russian, echo = false)
            }
        }
    }

    /** [echo]: say what went and where — needed when nothing was read back first. */
    private fun send(chat: Conversation, text: String, russian: Boolean, echo: Boolean = true): String {
        val sent = inbox.reply(context, chat, text)
        if (sent) inbox.markRead(chat.key, clock())
        return when {
            sent && !echo -> if (russian) "Отправила." else "Sent."
            sent && russian -> "Отправила «${chat.title}»: «$text»."
            sent -> "Sent to ${chat.title}: \"$text\"."
            russian -> "Уведомление уже закрыто — ответить из него нельзя. Откройте ${chat.app}."
            else -> "The notification is gone, so I can't reply from it. Open ${chat.app}."
        }
    }
}
