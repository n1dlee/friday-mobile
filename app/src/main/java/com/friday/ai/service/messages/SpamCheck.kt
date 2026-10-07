package com.friday.ai.service.messages

import com.friday.ai.core.ContactsReader
import com.friday.ai.core.messages.Conversation
import com.friday.ai.core.messages.PromoFilter

/**
 * [PromoFilter] with the phone book behind it. The names are re-read now
 * and then, not for every notification.
 */
class SpamCheck(
    private val contacts: ContactsReader,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private companion object {
        const val REFRESH_MS = 30 * 60 * 1000L
    }

    private var names: Set<String> = emptySet()
    private var loadedAt = Long.MIN_VALUE / 2

    @Synchronized
    fun isSpam(chat: Conversation): Boolean {
        val now = clock()
        if (now - loadedAt > REFRESH_MS) {
            names = runCatching { contacts.loadContacts().map { it.name.trim().lowercase() }.toSet() }
                .getOrDefault(names)
            loadedAt = now
        }
        return PromoFilter.isPromotional(chat, names)
    }
}
