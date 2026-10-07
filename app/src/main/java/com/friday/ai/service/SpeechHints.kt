package com.friday.ai.service

import com.friday.ai.core.ContactsReader
import com.friday.ai.core.people.NameHints
import com.friday.ai.service.messages.MessengerInbox

/**
 * The Whisper prompt for this phone: the people who just wrote, then the
 * phone book. The phone book is re-read now and then, not for every phrase.
 */
class SpeechHints(
    private val contacts: ContactsReader,
    private val inbox: MessengerInbox,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private companion object {
        const val REFRESH_MS = 30 * 60 * 1000L
    }

    private var book: List<String> = emptyList()
    private var loadedAt = Long.MIN_VALUE / 2

    @Synchronized
    fun prompt(): String? {
        val now = clock()
        if (now - loadedAt > REFRESH_MS) {
            book = runCatching { contacts.loadContacts().map { it.name } }.getOrDefault(book)
            loadedAt = now
        }
        val chats = inbox.answerable(now).sortedByDescending { it.updatedAt }.filterNot { it.isGroup }.map { it.title }
        return NameHints.prompt(chats + book)
    }
}
