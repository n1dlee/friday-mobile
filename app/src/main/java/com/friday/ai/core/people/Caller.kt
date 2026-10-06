package com.friday.ai.core.people

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.friday.ai.core.AppLauncher
import com.friday.ai.core.ContactMatcher
import com.friday.ai.core.ContactsReader
import com.friday.ai.core.DeviceContext
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity

/**
 * A "voice call" entry a messenger added to a contact.
 *
 * @param about the entry's text — WhatsApp puts the number there ("Voice call
 *   +998 90 …"), which tells apart the entries of a person with two numbers.
 */
data class CallEntry(val id: Long, val mimeType: String, val about: String) {

    /** Whose entry this is, read from its type: "…vnd.com.whatsapp.voip.call". */
    val channel: Channel?
        get() = when {
            "com.whatsapp" in mimeType -> Channel.WHATSAPP
            "org.telegram" in mimeType -> Channel.TELEGRAM
            else -> null
        }

    val isVideo: Boolean get() = "video" in mimeType

    companion object {
        /**
         * The voice-call entry of [channel] for [number]; any of that app's
         * voice-call entries if none names the number.
         */
        fun pick(entries: List<CallEntry>, channel: Channel, number: PhoneNumber): CallEntry? {
            val voice = entries.filter { it.channel == channel && !it.isVideo }
            val digits = (number.e164 ?: number.raw).filter { it.isDigit() }
            return voice.firstOrNull { e -> digits.isNotEmpty() && digits in e.about.filter { it.isDigit() } }
                ?: voice.firstOrNull()
        }
    }
}

/**
 * Calls someone the way the user would: an ordinary call to a number at
 * home, a WhatsApp or Telegram call to a number abroad — decided by
 * [ChannelChooser], the same reasoning as for messages.
 *
 * A messenger call is started by opening the voice-call entry the messenger
 * added to the contact, exactly what "WhatsApp call" in the contact card
 * does. When there is no such entry (contact sync is off in WhatsApp, or the
 * person isn't on it), the chat is opened and the user is told to tap the
 * receiver — never a silent fall back to an international call.
 */
class Caller(
    private val context: Context,
    private val people: PeopleDirectory,
    private val contacts: ContactsReader,
    private val device: DeviceContext,
    private val apps: AppLauncher,
    private val prefDao: UserPreferenceDao
) {

    private companion object {
        const val TAG = "Caller"
        const val PREF_FAVOURITE = "favourite_messenger"
        fun usualKey(person: Person) = "call_via:" + ContactMatcher.canonical(person.name)
    }

    suspend fun call(target: String, requested: Channel?, russian: Boolean): String {
        val found = people.find(target)
        val person = (found as? PeopleResolver.Result.Found)?.person
            ?: return people.notFound(target, found, russian)
        // "По телефону" is an order, unlike "смс" in a message: the user
        // knows it is international and wants the ordinary line anyway.
        val choice = if (requested == Channel.SMS) {
            val n = person.phones.firstOrNull { it.mobile } ?: person.phones.first()
            ChannelChooser.Choice(Channel.SMS, n, listOf(ChannelChooser.Reason.Asked))
        } else {
            choose(person, requested)
        }
        if (requested != null && requested == choice?.channel) {
            prefDao.set(UserPreferenceEntity(usualKey(person), requested.name))
        }
        return when (choice?.channel) {
            null -> if (russian) "У «${person.name}» нет номера" else "${person.name} has no number"
            Channel.SMS -> apps.dial(choice.number.e164 ?: choice.number.raw, person.name)
            else -> viaMessenger(person, choice, russian)
        }
    }

    private suspend fun choose(person: Person, requested: Channel?): ChannelChooser.Choice? {
        suspend fun channel(key: String) = prefDao.get(key)?.let { runCatching { Channel.valueOf(it) }.getOrNull() }
        return ChannelChooser.choose(
            ChannelChooser.Situation(
                person = person,
                requested = requested,
                myRegion = device.homeRegion(),
                installed = device.apps().messengers,
                usual = channel(usualKey(person)),
                favourite = channel(PREF_FAVOURITE)
            )
        )
    }

    private fun viaMessenger(person: Person, choice: ChannelChooser.Choice, russian: Boolean): String {
        val app = choice.channel.label
        val why = ChannelChooser.explain(choice, russian, call = true).let { if (it.isBlank()) "" else " ($it)" }
        val entry = person.id?.let { CallEntry.pick(contacts.callEntries(it), choice.channel, choice.number) }
        val pkg = choice.channel.packages.firstOrNull { device.isInstalled(it) }
        return try {
            if (entry != null) {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(
                            ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, entry.id),
                            entry.mimeType
                        )
                        .setPackage(if ("w4b" in entry.mimeType) "com.whatsapp.w4b" else pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                if (russian) "Звоню «${person.name}» в $app$why" else "Calling ${person.name} on $app$why"
            } else {
                openChat(choice, pkg)
                if (russian) "Открыла чат с «${person.name}» в $app$why — нажмите трубку. " +
                    "Звонить сразу смогу, когда в $app включена синхронизация контактов"
                else "Opened the ${person.name} chat in $app$why — tap the call button. " +
                    "I can call directly once contact sync is on in $app"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Messenger call failed: ${e.message}")
            if (russian) "Не смогла позвонить через $app" else "Couldn't call through $app"
        }
    }

    private fun openChat(choice: ChannelChooser.Choice, pkg: String?) {
        val digits = (choice.number.e164 ?: choice.number.raw).filter { it.isDigit() }
        val uri = if (choice.channel == Channel.WHATSAPP) "https://api.whatsapp.com/send?phone=$digits"
        else "tg://resolve?phone=$digits"
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
