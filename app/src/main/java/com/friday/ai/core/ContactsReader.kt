package com.friday.ai.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.friday.ai.core.people.CallEntry
import com.friday.ai.core.people.Channel
import com.friday.ai.core.people.Person
import com.friday.ai.core.people.PhoneNumber
import com.friday.ai.core.people.PhoneNumbers
import android.provider.ContactsContract.CommonDataKinds.Phone

/**
 * Reads the device phone book. Thin Android wrapper — all the matching logic
 * lives in [ContactMatcher] so it can be tested without a device.
 */
class ContactsReader(private val context: Context) {

    private companion object {
        const val TAG = "ContactsReader"
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** All contacts that have a phone number. Empty if permission is missing. */
    fun loadContacts(): List<Contact> {
        if (!hasPermission()) {
            Log.w(TAG, "READ_CONTACTS not granted")
            return emptyList()
        }

        val contacts = mutableMapOf<String, Contact>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIdx < 0 || numberIdx < 0) return emptyList()

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx)?.trim().orEmpty()
                    val number = cursor.getString(numberIdx)?.trim().orEmpty()
                    if (name.isBlank() || number.isBlank()) continue
                    // One number per person is enough for "call X"; keep the first.
                    contacts.putIfAbsent(name.lowercase(), Contact(name, number))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read contacts: ${e.message}")
        }
        return contacts.values.toList()
    }

    /**
     * Contacts that have an e-mail address, as [Contact]s whose `number` is
     * the address — so the same name matching that finds "папе" for a call
     * finds him for a mail.
     */
    fun loadEmailContacts(): List<Contact> {
        if (!hasPermission()) return emptyList()
        val rows = try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Email.ADDRESS
                ),
                null, null, null
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0)?.trim().orEmpty() to cursor.getString(1)?.trim().orEmpty())
                    }
                }
            }.orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read e-mail contacts: ${e.message}")
            emptyList()
        }
        return rows
            .filter { (name, address) -> name.isNotBlank() && address.contains('@') }
            .distinctBy { (name, _) -> name.lowercase() }
            .map { (name, address) -> Contact(name, address) }
    }

    /**
     * Everyone with a phone number: all their numbers, read in [homeRegion]
     * so each one's country is known, whether they are starred, and which
     * messengers have registered them.
     *
     * WhatsApp and Telegram add their own entries to the phone book for each
     * contact who uses them; that is how "she has WhatsApp" is known rather
     * than assumed.
     */
    fun loadPeople(homeRegion: String?): List<Person> {
        if (!hasPermission()) return emptyList()
        val messengers = messengerUsers()
        val byContact = linkedMapOf<Long, Person>()
        try {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.STARRED),
                null, null, "${Phone.IS_SUPER_PRIMARY} DESC"
            )?.use { c ->
                val col = PhoneColumns(c)
                while (c.moveToNext()) {
                    val row = col.read(c, homeRegion) ?: continue
                    byContact[row.id] = byContact[row.id]?.withNumber(row.number)
                        ?: Person(row.name, listOf(row.number), row.starred, messengers[row.id].orEmpty(), row.id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read people: ${e.message}")
        }
        return byContact.values.toList()
    }

    private class PhoneRow(val id: Long, val name: String, val number: PhoneNumber, val starred: Boolean)

    private class PhoneColumns(c: android.database.Cursor) {
        private val id = c.getColumnIndexOrThrow(Phone.CONTACT_ID)
        private val name = c.getColumnIndexOrThrow(Phone.DISPLAY_NAME)
        private val number = c.getColumnIndexOrThrow(Phone.NUMBER)
        private val type = c.getColumnIndexOrThrow(Phone.TYPE)
        private val starred = c.getColumnIndexOrThrow(Phone.STARRED)

        fun read(c: android.database.Cursor, homeRegion: String?): PhoneRow? {
            val display = c.getString(name)?.trim().orEmpty()
            val raw = c.getString(number)?.trim().orEmpty()
            if (display.isBlank() || raw.isBlank()) return null
            val mobile = c.getInt(type) == Phone.TYPE_MOBILE
            val phone = PhoneNumbers.inspect(raw, homeRegion, mobile)
            return PhoneRow(c.getLong(id), display, phone, starred = c.getInt(starred) == 1)
        }
    }

    /** The same number saved twice (local and international form) is one number. */
    private fun Person.withNumber(n: PhoneNumber): Person =
        if (phones.any { it.e164 != null && it.e164 == n.e164 }) this else copy(phones = phones + n)

    /**
     * The "voice call" entries WhatsApp and Telegram add to a contact.
     * Opening one is what tapping "WhatsApp call" in the contact card does.
     */
    fun callEntries(contactId: Long): List<CallEntry> {
        if (!hasPermission()) return emptyList()
        return try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data._ID, ContactsContract.Data.MIMETYPE,
                    ContactsContract.Data.DATA1, ContactsContract.Data.DATA3
                ),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} LIKE ?",
                arrayOf(contactId.toString(), "%call%"),
                null
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(ContactsContract.Data._ID)
                val mime = c.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
                val texts = listOf(ContactsContract.Data.DATA1, ContactsContract.Data.DATA3)
                    .map(c::getColumnIndexOrThrow)
                buildList {
                    while (c.moveToNext()) {
                        val about = texts.mapNotNull(c::getString).joinToString(" ")
                        add(CallEntry(c.getLong(id), c.getString(mime).orEmpty(), about))
                    }
                }
            }.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read call entries: ${e.message}")
            emptyList()
        }
    }

    /** Contact id → the messengers that list that contact as one of their users. */
    private fun messengerUsers(): Map<Long, Set<Channel>> {
        val result = mutableMapOf<Long, MutableSet<Channel>>()
        val byAccount = Channel.entries.flatMap { ch -> ch.packages.map { it to ch } }.toMap()
        try {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
                "${ContactsContract.RawContacts.DELETED} = 0", null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val channel = byAccount[c.getString(1) ?: continue] ?: continue
                    result.getOrPut(c.getLong(0)) { mutableSetOf() } += channel
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read messenger accounts: ${e.message}")
        }
        return result
    }

    /** Resolves a spoken name like "папе" to a contact, or null if unsure. */
    fun resolve(spokenName: String): Contact? =
        ContactMatcher.findBest(spokenName, loadContacts())
}
