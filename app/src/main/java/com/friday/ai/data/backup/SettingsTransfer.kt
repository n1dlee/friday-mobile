package com.friday.ai.data.backup

import com.friday.ai.data.local.dao.BackupDao
import com.friday.ai.data.local.secure.SecurePreferenceDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moves Friday to another phone: everything out into one encrypted file,
 * and back in.
 *
 * Export reads secrets in the clear (they are sealed with this phone's
 * Keystore, which the next phone doesn't have) and the archive encrypts them
 * again with the owner's passphrase. Import does the reverse and seals them
 * with the new phone's Keystore before they touch the table.
 *
 * Import is two steps so the owner sees what they're about to get before
 * anything on this phone is replaced: [read] opens and checks the file,
 * [apply] replaces the database in one transaction.
 */
class SettingsTransfer(
    private val backup: BackupDao,
    private val prefs: SecurePreferenceDao,
    private val appVersion: String,
    private val archive: SettingsArchive = SettingsArchive(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** What a file holds, shown before it replaces anything. */
    data class Summary(
        val appVersion: String,
        val createdAt: Long,
        val memories: Int,
        val chatMessages: Int,
        val errands: Int,
        val learnedCommands: Int,
        val hasApiKey: Boolean,
        val hasVoiceProfile: Boolean
    )

    suspend fun export(passphrase: CharArray): ByteArray {
        val content = SettingsArchive.Content(
            appVersion = appVersion,
            createdAt = clock(),
            // Opened: a sealed value is only readable on this phone.
            preferences = prefs.withPrefix("")
                .filter { it.key !in DEVICE_ONLY && it.value.isNotEmpty() },
            memories = backup.memories(),
            errands = backup.errands(),
            chat = backup.chat(),
            interactions = backup.interactions(),
            summaries = backup.summaries(),
            modes = backup.modes()
        )
        return withContext(Dispatchers.Default) { archive.seal(content, passphrase) }
    }

    /** @throws SettingsArchive.Failure when the file can't be used; nothing has changed then. */
    suspend fun read(file: ByteArray, passphrase: CharArray): SettingsArchive.Content =
        withContext(Dispatchers.Default) { archive.open(file, passphrase) }

    fun summary(content: SettingsArchive.Content): Summary {
        val keys = content.preferences.associate { it.key to it.value }
        return Summary(
            appVersion = content.appVersion,
            createdAt = content.createdAt,
            memories = content.memories.size,
            chatMessages = content.chat.size,
            errands = content.errands.count { !it.done },
            learnedCommands = keys.keys.count { it.startsWith(LEARNED_PREFIX) },
            hasApiKey = !keys[API_KEY].isNullOrBlank(),
            hasVoiceProfile = !keys[VOICE_PROFILE].isNullOrBlank()
        )
    }

    /** Replaces everything on this phone with [content], in one transaction. */
    suspend fun apply(content: SettingsArchive.Content) {
        val preferences = content.preferences
            .filter { it.key !in DEVICE_ONLY }
            .map(prefs::forStorage)
        backup.replaceAll(
            preferences = preferences,
            memories = content.memories,
            errands = content.errands,
            chat = content.chat,
            interactions = content.interactions,
            summaries = content.summaries,
            modes = content.modes
        )
    }

    companion object {
        /** Meaningful only on the phone that wrote them. */
        val DEVICE_ONLY = setOf("voice_session_id", "voice_session_last_activity", "lazuri_device_id")

        private const val API_KEY = "groq_api_key"
        private const val VOICE_PROFILE = "voice_profile"
        private const val LEARNED_PREFIX = "learned:"
    }
}
