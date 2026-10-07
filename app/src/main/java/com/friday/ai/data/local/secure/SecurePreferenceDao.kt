package com.friday.ai.data.local.secure

import android.util.Log
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import java.util.Base64

/** Encrypts and decrypts small secrets; the real one keeps its key in Android Keystore. */
interface SecretCipher {
    /** The result carries everything decryption needs besides the key (e.g. the IV). */
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
}

/**
 * Preferences, with API keys encrypted at rest.
 *
 * The Groq and Lazuri keys used to sit in the preferences table as plain
 * text: readable from a backup, from a rooted phone, or by anything that
 * copies the database. This wrapper stores them sealed with a key that never
 * leaves Android Keystore, and is the only `UserPreferenceDao` the app is
 * given — so the eleven places that read the Groq key, and any written
 * later, get the protection without knowing about it.
 *
 * A key stored in plain text by an older version is sealed the first time it
 * is read ([migrate] does it at start-up) and the plain copy is overwritten.
 *
 * A value that can't be decrypted — a database restored onto another phone,
 * whose Keystore never held the key — reads as absent: the user is asked
 * for the key again rather than Friday failing on every request.
 */
class SecurePreferenceDao(
    private val delegate: UserPreferenceDao,
    private val cipher: SecretCipher,
    private val secretKeys: Set<String> = SECRET_KEYS
) : UserPreferenceDao {

    companion object {
        private const val TAG = "SecurePreferences"

        /** Marks a sealed value; anything else under a secret key is a legacy plain value. */
        const val SEALED_PREFIX = "enc:v1:"

        /** The NFC secret signs the owner's mode tags: a secret like the keys. */
        val SECRET_KEYS = setOf("groq_api_key", "lazuri_api_key", "nfc_secret")
    }

    override suspend fun get(key: String): String? {
        val stored = delegate.get(key)
        return if (key in secretKeys) open(key, stored) else stored
    }

    override suspend fun set(preference: UserPreferenceEntity) {
        delegate.set(if (preference.key in secretKeys) seal(preference) else preference)
    }

    override suspend fun withPrefix(prefix: String): List<UserPreferenceEntity> =
        delegate.withPrefix(prefix).map { row ->
            if (row.key in secretKeys) row.copy(value = open(row.key, row.value).orEmpty()) else row
        }

    override suspend fun delete(key: String) = delegate.delete(key)

    override suspend fun deleteWithPrefix(prefix: String) = delegate.deleteWithPrefix(prefix)

    /**
     * Seals every secret still stored in plain text. Safe to call on every
     * start; returns how many were sealed, so the caller knows whether the
     * database file still holds an old plain copy in its free pages.
     */
    suspend fun migrate(): Int = secretKeys.count { key ->
        val stored = delegate.get(key)
        val legacy = !stored.isNullOrEmpty() && !stored.startsWith(SEALED_PREFIX)
        if (legacy) get(key)
        legacy
    }

    /**
     * [row] as it should be written straight into the table: sealed if it is
     * a secret. For settings import, which writes all rows in one transaction
     * without going through [set].
     */
    fun forStorage(row: UserPreferenceEntity): UserPreferenceEntity =
        if (row.key in secretKeys && !row.value.startsWith(SEALED_PREFIX)) seal(row) else row

    private suspend fun open(key: String, stored: String?): String? = when {
        stored.isNullOrEmpty() -> stored
        stored.startsWith(SEALED_PREFIX) -> try {
            String(cipher.decrypt(Base64.getDecoder().decode(stored.removePrefix(SEALED_PREFIX))), Charsets.UTF_8)
        } catch (e: Exception) {
            // Wrong device, wiped Keystore, corrupted value: all mean "not set".
            Log.w(TAG, "Could not open $key: ${e.javaClass.simpleName}")
            null
        }
        else -> {
            // Written by an older version in plain text: seal it now.
            delegate.set(seal(UserPreferenceEntity(key, stored)))
            stored
        }
    }

    private fun seal(preference: UserPreferenceEntity): UserPreferenceEntity {
        if (preference.value.isEmpty()) return preference
        val sealed = cipher.encrypt(preference.value.toByteArray(Charsets.UTF_8))
        return preference.copy(value = SEALED_PREFIX + Base64.getEncoder().encodeToString(sealed))
    }
}
