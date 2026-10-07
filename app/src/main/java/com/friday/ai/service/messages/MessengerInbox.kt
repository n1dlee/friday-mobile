package com.friday.ai.service.messages

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.friday.ai.core.messages.Conversation
import com.friday.ai.core.messages.InboxMessage

/**
 * Recent chats from messenger notifications — WhatsApp, Telegram, SMS, any
 * app that shows a conversation — with the means to answer them.
 *
 * Answering goes through the reply field of the notification itself, the
 * way Android Auto and watches do it: the message is sent by the messenger
 * without the app being opened. That field only lives as long as the
 * notification, which is why this is kept in memory and never written down:
 * a stored copy could not reply anyway, and chats are not Friday's to keep.
 */
class MessengerInbox(private val isSpam: (Conversation) -> Boolean = { false }) {

    /** [seen]: read aloud or in the app — no longer new, but still answerable. */
    private class Entry(val chat: Conversation, val reply: Notification.Action?, var seen: Boolean = false)

    private companion object {
        const val TAG = "MessengerInbox"

        /** Chats older than this are not "new" any more. */
        const val KEEP_MS = 12 * 60 * 60 * 1000L
        const val MAX_CHATS = 20
        const val MAX_MESSAGES = 10

        /** How long after Friday reads a chat out "ответь …" still means that chat. */
        const val JUST_READ_MS = 5 * 60 * 1000L
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var lastRead: Pair<String, Long>? = null

    /** Chats with something new, newest last. */
    @Synchronized
    fun chats(now: Long = System.currentTimeMillis()): List<Conversation> =
        live(now).filterNot { it.seen }.map { it.chat }

    /** Every chat that can still be answered, read or not. */
    @Synchronized
    fun answerable(now: Long = System.currentTimeMillis()): List<Conversation> = live(now).map { it.chat }

    /** The chat Friday read or announced in the last few minutes, if it is still there. */
    @Synchronized
    fun justRead(now: Long = System.currentTimeMillis()): Conversation? {
        val (key, at) = lastRead ?: return null
        return if (now - at > JUST_READ_MS) null else entries[key]?.chat
    }

    /** Read out or answered: no longer new, and the chat "ответь …" now means. */
    @Synchronized
    fun markRead(key: String, now: Long = System.currentTimeMillis()) {
        entries[key]?.seen = true
        lastRead = key to now
    }

    private fun live(now: Long): Collection<Entry> {
        entries.values.removeAll { now - it.chat.updatedAt > KEEP_MS }
        return entries.values
    }

    /**
     * Takes in a notification. Returns the messages in it that were not
     * seen before — what is worth announcing — or null if it is not a chat.
     */
    @Synchronized
    fun record(sbn: StatusBarNotification, app: String): Pair<Conversation, List<InboxMessage>>? {
        val n = sbn.notification
        // An action with a text field is a reply; one marked as such wins.
        val reply = n.actions?.filter { it.remoteInputs?.isNotEmpty() == true }
            ?.maxByOrNull { if (isReply(it)) 1 else 0 }
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style == null && reply == null && n.category != Notification.CATEGORY_MESSAGE) return null

        val extras = n.extras
        val title = (style?.conversationTitle ?: extras.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()?.trim().orEmpty()
        val messages = messagesIn(style, sbn, title)
        if (title.isBlank() || messages.isEmpty()) return null

        val key = sbn.packageName + "|" + title
        val previous = entries[key]
        val seenUntil = previous?.chat?.messages?.maxOfOrNull { it.time } ?: 0L
        val fresh = messages.filter { it.time > seenUntil }
        val chat = Conversation(
            key = key,
            packageName = sbn.packageName,
            app = app,
            title = title,
            messages = kept(previous, fresh),
            canReply = reply != null,
            updatedAt = sbn.postTime,
            isGroup = style?.isGroupConversation == true
        )
        // Shops, banks and codes are not kept at all.
        return chat.takeUnless(isSpam)?.let {
            // An update with nothing new (often the owner's own reply) leaves it read.
            keep(Entry(it, reply ?: previous?.reply, seen = previous?.seen == true && fresh.isEmpty()))
            it to fresh
        }
    }

    private fun keep(entry: Entry) {
        entries.remove(entry.chat.key)
        entries[entry.chat.key] = entry
        while (entries.size > MAX_CHATS) entries.remove(entries.keys.first())
    }

    /** Once read, the old messages are not told again with the new ones. */
    private fun kept(previous: Entry?, fresh: List<InboxMessage>): List<InboxMessage> {
        val before = previous?.chat?.messages.orEmpty()
        val base = if (previous?.seen == true && fresh.isNotEmpty()) emptyList() else before
        return (base + fresh).takeLast(MAX_MESSAGES)
    }

    private fun isReply(a: Notification.Action): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY

    /**
     * Sends [text] through the chat's reply field. False when the field is
     * gone — the notification was cleared, or the app does not offer one.
     */
    fun reply(context: Context, chat: Conversation, text: String): Boolean {
        val action = synchronized(this) { entries[chat.key]?.reply } ?: return false
        val inputs = action.remoteInputs ?: return false
        return try {
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(context, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "Reply field no longer valid: ${e.message}")
            false
        }
    }
}

/** What a chat notification says, the owner's own replies left out. */
private fun messagesIn(
    style: NotificationCompat.MessagingStyle?,
    sbn: StatusBarNotification,
    title: String
): List<InboxMessage> =
    style?.messages
        ?.filter { it.person != null && it.text != null } // no person = the user's own reply
        ?.map { m ->
            val sender = m.person?.name?.toString().orEmpty().ifBlank { title }
            InboxMessage(sender, m.text.toString(), m.timestamp)
        }
        ?: listOfNotNull(
            sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?.let { InboxMessage(title, it, sbn.postTime) }
        )
