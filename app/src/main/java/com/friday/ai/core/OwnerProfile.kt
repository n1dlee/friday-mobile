package com.friday.ai.core

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Who the phone belongs to, read from the device's own contact card.
 *
 * Android keeps an owner profile separate from the contact list — it is what
 * the Contacts app shows at the top as "Me", populated from the account the
 * phone was set up with. Reading it means Friday can answer "как меня зовут?"
 * from the device instead of waiting to be told, which is the whole point of
 * running on the user's own phone.
 *
 * Nothing here leaves the device, and it degrades to null rather than
 * throwing: plenty of phones have no profile row at all.
 */
object OwnerProfile {

    private const val TAG = "OwnerProfile"

    data class Owner(val displayName: String?, val givenName: String?)

    fun read(context: Context): Owner? {
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) return null

        return try {
            context.contentResolver.query(
                ContactsContract.Profile.CONTENT_URI,
                arrayOf(ContactsContract.Profile.DISPLAY_NAME_PRIMARY),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val display = cursor.getString(0)?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@use null
                Owner(displayName = display, givenName = display.split(' ').firstOrNull())
            }
        } catch (e: Exception) {
            // A missing or locked profile is normal, not an error worth surfacing.
            Log.d(TAG, "No owner profile available: ${e.message}")
            null
        }
    }
}
