package com.friday.ai.service.mail

import com.friday.ai.core.Contact
import com.friday.ai.core.ContactMatcher
import com.friday.ai.core.mail.GmailMessages
import com.friday.ai.data.remote.GmailApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Finds senders and correspondents by the name the user said. */
internal class MailPeople(private val api: GmailApi) {

    private companion object {
        /** How far back to look for a sender or correspondent by name. */
        const val RECENT = 20
    }

    /**
     * The newest inbox mail whose sender matches what was said. Matching goes
     * through [ContactMatcher], so "от Ивана" finds "Ivan Petrov" the same way
     * "позвони Ивану" finds him in the phone book.
     */
    suspend fun findSender(token: String, spoken: String): GmailMessages.Mail? {
        val recent = metadataOf(token, api.list(token, "in:inbox", RECENT).ids)
        val byId = recent.associateBy { it.id }
        val match = ContactMatcher.findBest(spoken, recent.map { Contact(it.fromName, it.id) })
        return match?.let { byId[it.number] }
    }

    /** Someone the user has mailed with recently, as a name and an address. */
    suspend fun findCorrespondent(token: String, spoken: String): Contact? {
        val received = metadataOf(token, api.list(token, "in:inbox", RECENT).ids)
            .map { Contact(it.fromName, it.fromAddress) }
        val sent = metadataOf(token, api.list(token, "in:sent", RECENT).ids)
            .filter { it.toAddress.isNotBlank() }
            .map { Contact(it.toName, it.toAddress) }
        return ContactMatcher.findBest(spoken, sent + received)
    }

    /** Fetched side by side: twenty in a row would leave the user waiting seconds. */
    suspend fun metadataOf(token: String, ids: List<String>): List<GmailMessages.Mail> =
        coroutineScope { ids.map { async { api.metadata(token, it) } }.awaitAll() }
}
