package com.friday.ai.core.people

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.friday.ai.core.ContactMatcher
import com.friday.ai.core.ContactsReader
import com.friday.ai.core.DeviceContext
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity

/** Finds people in the phone book, digits and spoken names alike. */
class PeopleDirectory(private val contacts: ContactsReader, private val device: DeviceContext) {

    private companion object {
        /** Fewer digits than this is a name with a number in it, not a phone number. */
        const val MIN_NUMBER_DIGITS = 5
    }

    val hasPermission: Boolean get() = contacts.hasPermission()

    fun find(target: String): PeopleResolver.Result {
        val digits = target.count { it.isDigit() } >= MIN_NUMBER_DIGITS &&
            target.all { it.isDigit() || it in "+-() " }
        val home = device.homeRegion()
        return if (digits) {
            PeopleResolver.Result.Found(Person(target, listOf(PhoneNumbers.inspect(target, home))))
        } else {
            PeopleResolver.resolve(target, contacts.loadPeople(home))
        }
    }

    /** "Кого именно: Мамуля или Мамед?" — or why nobody was found. */
    fun notFound(target: String, result: PeopleResolver.Result, russian: Boolean): String = when {
        result is PeopleResolver.Result.Ambiguous ->
            result.candidates.joinToString(if (russian) " или " else " or ") { it.name }
                .let { if (russian) "Кого именно: $it?" else "Which one: $it?" }
        !hasPermission -> if (russian) "Нужен доступ к контактам" else "I need access to your contacts"
        russian -> "Не нашла «$target» в контактах"
        else -> "No contact called \"$target\""
    }
}

/**
 * Sends a message the way the user would have chosen: by SMS, WhatsApp or
 * Telegram, decided by [ChannelChooser] from the person's numbers, the
 * user's country and what is installed.
 *
 * It opens the conversation with the text filled in; the user taps Send.
 * Nothing goes out unseen — a wrong guess costs a tap, not a message to the
 * wrong person.
 */
class Messenger(
    private val context: Context,
    private val people: PeopleDirectory,
    private val device: DeviceContext,
    private val prefDao: UserPreferenceDao
) {

    private companion object {
        const val TAG = "Messenger"
        const val PREF_FAVOURITE = "favourite_messenger"
        fun usualKey(person: Person) = "messenger_for:" + ContactMatcher.canonical(person.name)
    }

    suspend fun message(target: String, text: String?, requested: Channel?, russian: Boolean): String {
        val found = people.find(target)
        val person = (found as? PeopleResolver.Result.Found)?.person
            ?: return people.notFound(target, found, russian)
        val choice = choose(person, requested)
            ?: return if (russian) "У «${person.name}» нет номера" else "${person.name} has no number"

        // Asked for by name: that is how this person is reached from now on.
        if (requested != null && requested == choice.channel) {
            prefDao.set(UserPreferenceEntity(usualKey(person), requested.name))
        }
        return try {
            reply(person, choice, text, pasted = open(choice, text), russian)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open ${choice.channel}: ${e.message}")
            if (russian) "Не смогла открыть ${choice.channel.label}" else "Couldn't open ${choice.channel.label}"
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

    /** Opens the conversation. Returns false when the text had to go to the clipboard instead. */
    private fun open(choice: ChannelChooser.Choice, text: String?): Boolean {
        val full = choice.number.e164 ?: choice.number.raw
        val digits = full.filter { it.isDigit() }
        val encoded = text?.let { Uri.encode(it) }
        val intent = when (choice.channel) {
            Channel.SMS -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(full)))
                .apply { if (text != null) putExtra("sms_body", text) }
            Channel.WHATSAPP -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://api.whatsapp.com/send?phone=$digits" + (encoded?.let { "&text=$it" } ?: ""))
            ).setPackage(installed(choice.channel))
            // Telegram opens a chat by number but does not reliably take the
            // text with it, so the text waits on the clipboard.
            Channel.TELEGRAM -> Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?phone=$digits"))
                .setPackage(installed(choice.channel))
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (choice.channel == Channel.TELEGRAM && text != null) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Friday", text))
            return false
        }
        return true
    }

    private fun installed(channel: Channel): String? = channel.packages.firstOrNull { device.isInstalled(it) }

    private fun reply(
        person: Person,
        choice: ChannelChooser.Choice,
        text: String?,
        pasted: Boolean,
        russian: Boolean
    ): String {
        val why = ChannelChooser.explain(choice, russian).let { if (it.isBlank()) "" else " ($it)" }
        val via = choice.channel.label
        return when {
            text == null && russian -> "Открыла $via с «${person.name}»$why."
            text == null -> "Opened $via with ${person.name}$why."
            !pasted && russian -> "Открыла чат с «${person.name}» в $via$why. Текст скопирован — вставьте и отправьте."
            !pasted -> "Opened the ${person.name} chat in $via$why. The text is copied — paste and send."
            russian -> "Сообщение для «${person.name}» готово в $via$why — нажмите «Отправить»."
            else -> "The message to ${person.name} is ready in $via$why — tap Send."
        }
    }
}
