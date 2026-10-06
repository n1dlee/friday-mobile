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
class MessengerInbox {

    private class Entry(val chat: Conversation, val reply: Notification.Action?)

    private companion object {
        const val TAG = "MessengerInbox"

        /** Chats older than this are not "new" any more. */
        const val KEEP_MS = 12 * 60 * 60 * 1000L
        const val MAX_CHATS = 20
        const val MAX_MESSAGES = 10
    }

    private val entries = LinkedHashMap<String, Entry>()

    /** Chats with something new, newest last. */
    @Synchronized
    fun chats(now: Long = System.currentTimeMillis()): List<Conversation> {
        entries.values.removeAll { now - it.chat.updatedAt > KEEP_MS }
        return entries.values.map { it.chat }
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
        val messages = style?.messages
            ?.filter { it.person != null && it.text != null } // no person = the user's own reply
            ?.map { m ->
                val sender = m.person?.name?.toString().orEmpty().ifBlank { title }
                InboxMessage(sender, m.text.toString(), m.timestamp)
            }
            ?: listOfNotNull(
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                    ?.let { InboxMessage(title, it, sbn.postTime) }
            )
        if (title.isBlank() || messages.isEmpty()) return null

        val key = sbn.packageName + "|" + title
        val before = entries[key]?.chat
        val seenUntil = before?.messages?.maxOfOrNull { it.time } ?: 0L
        val fresh = messages.filter { it.time > seenUntil }
        val chat = Conversation(
            key = key,
            packageName = sbn.packageName,
            app = app,
            title = title,
            messages = ((before?.messages.orEmpty()) + fresh).takeLast(MAX_MESSAGES),
            canReply = reply != null,
            updatedAt = sbn.postTime,
            isGroup = style?.isGroupConversation == true
        )
        entries.remove(key)
        entries[key] = Entry(chat, reply ?: entries[key]?.reply)
        while (entries.size > MAX_CHATS) entries.remove(entries.keys.first())
        return chat to fresh
    }

    private fun isReply(a: Notification.Action): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY

    /** The user has read it, in the app or aloud: no longer new. */
    @Synchronized
    fun dismiss(key: String) {
        entries.remove(key)
    }

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
