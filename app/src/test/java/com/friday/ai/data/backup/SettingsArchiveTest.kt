package com.friday.ai.data.backup

import com.friday.ai.data.backup.SettingsArchive.Failure
import com.friday.ai.data.local.dao.BackupDao
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.ChatMessageEntity
import com.friday.ai.data.local.entity.ErrandEntity
import com.friday.ai.data.local.entity.InteractionEntity
import com.friday.ai.data.local.entity.MemoryEntity
import com.friday.ai.data.local.entity.SessionSummaryEntity
import com.friday.ai.data.local.entity.UserPreferenceEntity
import com.friday.ai.data.local.secure.SecretCipher
import com.friday.ai.data.local.secure.SecurePreferenceDao
import java.nio.ByteBuffer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsArchiveTest {

    // Few iterations: the format is what's under test, not PBKDF2's cost.
    private val archive = SettingsArchive(iterations = 1_000)
    private val passphrase = "correct horse".toCharArray()
    private val key = "gsk_example_not_a_real_key_1234"

    private val content = SettingsArchive.Content(
        appVersion = "0.11.0",
        createdAt = 1_700_000_000_000,
        preferences = listOf(
            UserPreferenceEntity("groq_api_key", key),
            UserPreferenceEntity("voice_profile", "2;0.61;0.83;8;0.1,0.2"),
            UserPreferenceEntity(
                "learned:выруби свет",
                """{"tool":"flashlight","args":{"state":"off"}}"""
            )
        ),
        memories = listOf(
            MemoryEntity(id = 3, category = "people", key = "мама", value = "живёт в Ташкенте", source = "chat")
        ),
        errands = listOf(ErrandEntity(id = 1, what = "купить молоко")),
        chat = listOf(
            ChatMessageEntity(id = "m1", content = "Привет", role = "user", sessionId = "s1", timestamp = 5)
        ),
        interactions = listOf(
            InteractionEntity(id = 9, userInput = "время", assistantResponse = "12:00", commandType = "TIME")
        ),
        summaries = listOf(
            SessionSummaryEntity(sessionId = "s1", summary = "Поздоровались", messageCount = 1, updatedAt = 6)
        )
    )

    @Test
    fun `everything survives the round trip`() {
        val opened = archive.open(archive.seal(content, passphrase), passphrase)
        assertEquals(content, opened)
    }

    @Test
    fun `the file holds no secret or text in the clear`() {
        val file = String(archive.seal(content, passphrase), Charsets.ISO_8859_1)
        listOf("gsk_", "groq_api_key", "voice_profile", "Ташкент", "молоко").forEach {
            assertFalse("found \"$it\" in the file", file.contains(it))
        }
    }

    @Test
    fun `two exports of the same content differ`() {
        assertFalse(archive.seal(content, passphrase).contentEquals(archive.seal(content, passphrase)))
    }

    @Test
    fun `wrong passphrase is refused`() {
        val file = archive.seal(content, passphrase)
        expect<Failure.WrongPassphraseOrDamaged> { archive.open(file, "wrong horse".toCharArray()) }
    }

    @Test
    fun `a changed byte anywhere is refused`() {
        val file = archive.seal(content, passphrase)
        // Contents, the tag at the end, and the header (authenticated as associated data).
        listOf(file.size / 2, file.size - 1, 5, 9, 25).forEach { i ->
            val tampered = file.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            try {
                archive.open(tampered, passphrase)
                fail("byte $i changed, still opened")
            } catch (_: Failure) {
                // expected: either not an archive (bad header field) or failed authentication
            }
        }
    }

    @Test
    fun `a truncated file is refused`() {
        val file = archive.seal(content, passphrase)
        expect<Failure.WrongPassphraseOrDamaged> { archive.open(file.copyOf(file.size - 10), passphrase) }
        expect<Failure.NotAnArchive> { archive.open(file.copyOf(20), passphrase) }
    }

    @Test
    fun `something that isn't an archive is named as such`() {
        expect<Failure.NotAnArchive> { archive.open("hello, this is a photo".toByteArray(), passphrase) }
    }

    @Test
    fun `a file from a newer Friday is refused, not misread`() {
        val file = archive.seal(content, passphrase).also { it[4] = (SettingsArchive.FORMAT + 1).toByte() }
        val failure = expect<Failure.NewerFormat> { archive.open(file, passphrase) }
        assertEquals(SettingsArchive.FORMAT + 1, failure.format)
    }

    @Test
    fun `a forged iteration count can't make opening hang`() {
        val file = archive.seal(content, passphrase)
        ByteBuffer.wrap(file).putInt(5, Int.MAX_VALUE)
        expect<Failure.NotAnArchive> { archive.open(file, passphrase) }
    }

    @Test
    fun `fields added by a later version are ignored`() {
        // A newer 1.x may add sections; an older Friday still opens the file.
        val json = """{"format":1,"appVersion":"0.12.0","createdAt":1,"protocols":[{"id":"x"}],"preferences":[]}"""
        val opened = SettingsArchive.json.decodeFromString(SettingsArchive.Content.serializer(), json)
        assertEquals("0.12.0", opened.appVersion)
        assertTrue(opened.memories.isEmpty())
    }

    @Test
    fun `a too short passphrase isn't accepted for export`() {
        try {
            archive.seal(content, "123".toCharArray())
            fail("short passphrase accepted")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // --- SettingsTransfer: Keystore on one side, passphrase on the other ---

    @Test
    fun `moving to a new phone keeps the key usable there`() = runTest {
        val oldPhone = Phone(cipherKey = 0x11)
        oldPhone.prefs.set(UserPreferenceEntity("groq_api_key", key))
        oldPhone.prefs.set(UserPreferenceEntity("wake_threshold", "0.6"))
        oldPhone.prefs.set(UserPreferenceEntity("voice_session_id", "only-here"))

        val file = oldPhone.transfer.export(passphrase)

        val newPhone = Phone(cipherKey = 0x22)
        val read = newPhone.transfer.read(file, passphrase)
        newPhone.transfer.apply(read)

        assertEquals(key, newPhone.prefs.get("groq_api_key"))
        assertEquals("0.6", newPhone.prefs.get("wake_threshold"))
        // Sealed with the new phone's Keystore, never stored in plain text.
        val stored = newPhone.backup.prefs.single { it.key == "groq_api_key" }.value
        assertTrue(stored.startsWith(SecurePreferenceDao.SEALED_PREFIX))
        assertFalse(stored.contains("gsk_"))
        // Belonged to the old phone only.
        assertNull(newPhone.prefs.get("voice_session_id"))
    }

    @Test
    fun `the summary says what the file holds before anything is replaced`() = runTest {
        val phone = Phone(cipherKey = 0x11)
        val summary = phone.transfer.summary(content)
        assertTrue(summary.hasApiKey)
        assertTrue(summary.hasVoiceProfile)
        assertEquals(1, summary.learnedCommands)
        assertEquals(1, summary.memories)
        assertEquals(1, summary.chatMessages)
        assertEquals(1, summary.errands)
        assertEquals("0.11.0", summary.appVersion)
    }

    @Test
    fun `import replaces what was there`() = runTest {
        val phone = Phone(cipherKey = 0x11)
        phone.prefs.set(UserPreferenceEntity("maps_provider", "yandex"))
        phone.backup.memoriesTable += MemoryEntity(id = 1, category = "x", key = "old", value = "old", source = "chat")
        phone.transfer.apply(content)
        assertNull(phone.prefs.get("maps_provider"))
        assertEquals(listOf("мама"), phone.backup.memories().map { it.key })
    }

    private inline fun <reified T : Failure> expect(block: () -> Unit): T {
        try {
            block()
        } catch (e: Failure) {
            if (e is T) return e
            fail("expected ${T::class.simpleName}, got ${e::class.simpleName}")
        }
        fail("expected ${T::class.simpleName}, nothing thrown")
        throw AssertionError()
    }

    /** A phone: its own Keystore key, its own tables. */
    private inner class Phone(cipherKey: Int) {
        val backup = FakeBackupDao()
        val prefs = SecurePreferenceDao(backup.asPreferenceDao(), XorCipher(cipherKey))
        val transfer = SettingsTransfer(backup, prefs, appVersion = "0.11.0", archive = archive, clock = { 42 })
    }
}

/** Keystore stand-in: a key byte checked on decrypt, like GCM's tag. */
private class XorCipher(private val key: Int) : SecretCipher {
    override fun encrypt(plain: ByteArray) = byteArrayOf(key.toByte()) + plain.map { (it.toInt() xor key).toByte() }
    override fun decrypt(sealed: ByteArray): ByteArray {
        if (sealed[0] != key.toByte()) throw javax.crypto.AEADBadTagException("other key")
        return sealed.drop(1).map { (it.toInt() xor key).toByte() }.toByteArray()
    }
}

private class FakeBackupDao : BackupDao() {
    val prefs = mutableListOf<UserPreferenceEntity>()
    val memoriesTable = mutableListOf<MemoryEntity>()
    private val errandsTable = mutableListOf<ErrandEntity>()
    private val chatTable = mutableListOf<ChatMessageEntity>()
    private val interactionsTable = mutableListOf<InteractionEntity>()
    private val summariesTable = mutableListOf<SessionSummaryEntity>()

    override suspend fun preferences() = prefs.toList()
    override suspend fun memories() = memoriesTable.toList()
    override suspend fun errands() = errandsTable.toList()
    override suspend fun chat() = chatTable.toList()
    override suspend fun interactions() = interactionsTable.toList()
    override suspend fun summaries() = summariesTable.toList()

    override suspend fun clearPreferences() = prefs.clear()
    override suspend fun clearMemories() = memoriesTable.clear()
    override suspend fun clearErrands() = errandsTable.clear()
    override suspend fun clearChat() = chatTable.clear()
    override suspend fun clearInteractions() = interactionsTable.clear()
    override suspend fun clearSummaries() = summariesTable.clear()

    override suspend fun insertPreferences(rows: List<UserPreferenceEntity>) { prefs += rows }
    override suspend fun insertMemories(rows: List<MemoryEntity>) { memoriesTable += rows }
    override suspend fun insertErrands(rows: List<ErrandEntity>) { errandsTable += rows }
    override suspend fun insertChat(rows: List<ChatMessageEntity>) { chatTable += rows }
    override suspend fun insertInteractions(rows: List<InteractionEntity>) { interactionsTable += rows }
    override suspend fun insertSummaries(rows: List<SessionSummaryEntity>) { summariesTable += rows }

    /** The preferences table seen through the ordinary DAO. */
    fun asPreferenceDao() = object : UserPreferenceDao {
        override suspend fun get(key: String) = prefs.firstOrNull { it.key == key }?.value
        override suspend fun set(preference: UserPreferenceEntity) {
            prefs.removeAll { it.key == preference.key }
            prefs += preference
        }
        override suspend fun withPrefix(prefix: String) = prefs.filter { it.key.startsWith(prefix) }
        override suspend fun delete(key: String) { prefs.removeAll { it.key == key } }
        override suspend fun deleteWithPrefix(prefix: String) { prefs.removeAll { it.key.startsWith(prefix) } }
    }
}
