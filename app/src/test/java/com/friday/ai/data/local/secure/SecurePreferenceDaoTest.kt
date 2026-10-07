package com.friday.ai.data.local.secure

import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import javax.crypto.AEADBadTagException
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The table as Room would hold it. */
private class TableDao : UserPreferenceDao {
    val rows = linkedMapOf<String, String>()
    override suspend fun get(key: String) = rows[key]
    override suspend fun set(preference: UserPreferenceEntity) { rows[preference.key] = preference.value }
    override suspend fun withPrefix(prefix: String) =
        rows.filterKeys { it.startsWith(prefix) }.map { (k, v) -> UserPreferenceEntity(k, v) }
    override suspend fun delete(key: String) { rows.remove(key) }
    override suspend fun deleteWithPrefix(prefix: String) { rows.keys.removeAll { it.startsWith(prefix) } }
}

/**
 * Stand-in for Keystore AES-GCM: a random 4-byte "IV", a byte identifying the
 * key (GCM's tag check, in miniature), and a reversible scramble.
 */
private class FakeCipher(private val key: Int = 0x5A) : SecretCipher {
    override fun encrypt(plain: ByteArray) =
        Random.nextBytes(4) + key.toByte() + plain.map { (it.toInt() xor key).toByte() }

    override fun decrypt(sealed: ByteArray): ByteArray {
        // Like GCM: a value sealed under another key fails authentication.
        if (sealed[4] != key.toByte()) throw AEADBadTagException("sealed under another key")
        return sealed.drop(5).map { (it.toInt() xor key).toByte() }.toByteArray()
    }
}

class SecurePreferenceDaoTest {

    private val table = TableDao()
    private val prefs = SecurePreferenceDao(table, FakeCipher())
    private val key = "gsk_example_not_a_real_key_1234"

    @Test
    fun `the key never reaches the table in plain text`() = runTest {
        prefs.set(UserPreferenceEntity("groq_api_key", key))
        val stored = table.rows.getValue("groq_api_key")
        assertTrue(stored.startsWith(SecurePreferenceDao.SEALED_PREFIX))
        assertFalse(stored.contains("gsk_"))
        assertEquals(key, prefs.get("groq_api_key"))
    }

    @Test
    fun `sealing the same key twice gives different bytes`() = runTest {
        prefs.set(UserPreferenceEntity("groq_api_key", key))
        val first = table.rows.getValue("groq_api_key")
        prefs.set(UserPreferenceEntity("groq_api_key", key))
        assertTrue(first != table.rows.getValue("groq_api_key"))
    }

    @Test
    fun `ordinary preferences pass through untouched`() = runTest {
        prefs.set(UserPreferenceEntity("groq_model", "openai/gpt-oss-120b"))
        assertEquals("openai/gpt-oss-120b", table.rows["groq_model"])
        assertEquals("openai/gpt-oss-120b", prefs.get("groq_model"))
    }

    @Test
    fun `a key stored in plain text by an older version is sealed on first read`() = runTest {
        table.rows["groq_api_key"] = key
        assertEquals(key, prefs.get("groq_api_key"))
        assertTrue(table.rows.getValue("groq_api_key").startsWith(SecurePreferenceDao.SEALED_PREFIX))
    }

    @Test
    fun `start-up migration seals every plain secret once`() = runTest {
        table.rows["groq_api_key"] = key
        table.rows["lazuri_api_key"] = "lazuri-secret"
        table.rows["home_city"] = "Tashkent"
        assertEquals(2, prefs.migrate())
        assertEquals(0, prefs.migrate())
        assertEquals("Tashkent", table.rows["home_city"])
        assertEquals("lazuri-secret", prefs.get("lazuri_api_key"))
    }

    @Test
    fun `a value this phone can't open reads as not set, without crashing`() = runTest {
        // A database restored onto another phone: its Keystore never held this key.
        SecurePreferenceDao(table, FakeCipher(key = 0x11)).set(UserPreferenceEntity("groq_api_key", key))
        assertNull(prefs.get("groq_api_key"))
    }

    @Test
    fun `clearing a key stores nothing to seal`() = runTest {
        prefs.set(UserPreferenceEntity("groq_api_key", ""))
        assertEquals("", table.rows["groq_api_key"])
        assertEquals("", prefs.get("groq_api_key"))
        assertEquals(0, prefs.migrate())
    }

    @Test
    fun `secrets listed by prefix come back opened`() = runTest {
        val custom = SecurePreferenceDao(table, FakeCipher(), secretKeys = setOf("vault:token"))
        custom.set(UserPreferenceEntity("vault:token", "s3cret"))
        custom.set(UserPreferenceEntity("vault:note", "plain"))
        assertEquals(
            mapOf("vault:token" to "s3cret", "vault:note" to "plain"),
            custom.withPrefix("vault:").associate { it.key to it.value }
        )
    }
}
