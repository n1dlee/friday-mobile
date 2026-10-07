package com.friday.ai.data.backup

import com.friday.ai.data.local.entity.ChatMessageEntity
import com.friday.ai.data.local.entity.ErrandEntity
import com.friday.ai.data.local.entity.InteractionEntity
import com.friday.ai.data.local.entity.MemoryEntity
import com.friday.ai.data.local.entity.ModeEntity
import com.friday.ai.data.local.entity.ModeScheduleEntity
import com.friday.ai.data.local.entity.SessionSummaryEntity
import com.friday.ai.data.local.entity.UserPreferenceEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Everything Friday knows, in one file that can move to another phone.
 *
 * Friday lives on one phone; this is how she changes phones. The file holds
 * the API key and the voice profile, so it is always encrypted with a
 * passphrase the owner types: it can sit in Downloads or a cloud drive and
 * be useless to anyone else.
 *
 * Layout (all integers big-endian):
 * ```
 * "FRDY" | format u8 | iterations i32 | salt 16 | iv 12 | AES-256-GCM(gzip(JSON))
 * ```
 * The header is the GCM associated data, so changing the iteration count or
 * the format byte breaks the tag just like changing the contents does.
 *
 * Kept free of Android so the format and the crypto can be tested directly.
 * Field names of the entities are part of the format: renaming one needs a
 * new [Content.format] and a migration in [open].
 */
class SettingsArchive(
    private val iterations: Int = ITERATIONS,
    private val random: SecureRandom = SecureRandom()
) {

    @Serializable
    data class Content(
        val format: Int = FORMAT,
        val appVersion: String,
        val createdAt: Long,
        val preferences: List<UserPreferenceEntity> = emptyList(),
        val memories: List<MemoryEntity> = emptyList(),
        val errands: List<ErrandEntity> = emptyList(),
        val chat: List<ChatMessageEntity> = emptyList(),
        val interactions: List<InteractionEntity> = emptyList(),
        val summaries: List<SessionSummaryEntity> = emptyList(),
        /** Added in 0.12; files from 0.11 simply have none. */
        val modes: List<ModeEntity> = emptyList(),
        val schedules: List<ModeScheduleEntity> = emptyList()
    )

    /** Why a file could not be opened, each with its own message for the owner. */
    sealed class Failure(message: String) : Exception(message) {
        class NotAnArchive : Failure("Not a Friday settings file")
        /** GCM can't tell a wrong passphrase from a damaged file; neither can we. */
        class WrongPassphraseOrDamaged : Failure("Wrong passphrase, or the file is damaged")
        class NewerFormat(val format: Int) : Failure("Made by a newer Friday (format $format)")
    }

    fun seal(content: Content, passphrase: CharArray): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE) { "Passphrase too short" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = header(FORMAT, iterations, salt, iv)
        val cipher = cipher(Cipher.ENCRYPT_MODE, passphrase, salt, iv, iterations).apply { updateAAD(header) }
        return header + cipher.doFinal(gzip(json.encodeToString(Content.serializer(), content)))
    }

    /** @throws Failure when the file can't be read; nothing is applied in that case. */
    fun open(file: ByteArray, passphrase: CharArray): Content {
        val header = Header.parse(file)
        val plain = try {
            cipher(Cipher.DECRYPT_MODE, passphrase, header.salt, header.iv, header.iterations)
                .apply { updateAAD(file.copyOfRange(0, HEADER_BYTES)) }
                .doFinal(file, HEADER_BYTES, file.size - HEADER_BYTES)
        } catch (_: GeneralSecurityException) {
            throw Failure.WrongPassphraseOrDamaged()
        }
        return runCatching { json.decodeFromString(Content.serializer(), gunzip(plain)) }
            .getOrElse { throw Failure.WrongPassphraseOrDamaged() }
    }

    private class Header(val iterations: Int, val salt: ByteArray, val iv: ByteArray) {
        companion object {
            fun parse(file: ByteArray): Header {
                val buffer = ByteBuffer.wrap(file)
                val magic = ByteArray(MAGIC.size).also { if (file.size >= it.size) buffer.get(it) }
                val format = if (file.size >= HEADER_BYTES + TAG_BYTES) buffer.get().toInt() else -1
                val rounds = if (format >= 0) buffer.int else 0
                val problem = when {
                    !magic.contentEquals(MAGIC) || format < 0 -> Failure.NotAnArchive()
                    format > FORMAT -> Failure.NewerFormat(format)
                    // A forged count could make opening take hours, or be too weak to mean anything.
                    rounds !in MIN_ITERATIONS..MAX_ITERATIONS -> Failure.NotAnArchive()
                    else -> null
                }
                if (problem != null) throw problem
                return Header(rounds, ByteArray(SALT_BYTES).also(buffer::get), ByteArray(IV_BYTES).also(buffer::get))
            }
        }
    }

    private fun cipher(mode: Int, passphrase: CharArray, salt: ByteArray, iv: ByteArray, rounds: Int): Cipher {
        val spec = PBEKeySpec(passphrase, salt, rounds, KEY_BITS)
        val key = try {
            SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * Byte.SIZE_BITS, iv))
        }
    }

    private fun header(format: Int, rounds: Int, salt: ByteArray, iv: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(format.toByte()).putInt(rounds).put(salt).put(iv).array()

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }
            .toByteArray()

    private fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { String(it.readBytes()) }

    companion object {
        const val FORMAT = 1
        const val MIN_PASSPHRASE = 6

        /** OWASP's 2023 figure for PBKDF2-HMAC-SHA256; about a second on a current phone. */
        const val ITERATIONS = 210_000
        private const val MIN_ITERATIONS = 1_000
        private const val MAX_ITERATIONS = 10_000_000

        private val MAGIC = "FRDY".toByteArray()
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val KEY_BITS = 256
        private val HEADER_BYTES = MAGIC.size + 1 + Int.SIZE_BYTES + SALT_BYTES + IV_BYTES

        private const val KDF = "PBKDF2WithHmacSHA256"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Tolerant of sections a later version adds. */
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
