package com.friday.ai.core.modes

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What an NFC tag for a mode holds: `{"v":1,"id":"<mode uuid>","mac":"…"}`.
 *
 * The mode's id, not its name, so renaming doesn't break the tag. The MAC
 * is an HMAC of the version and id with a secret made on this phone (and
 * carried by settings export), so nobody can write a tag that runs one of
 * the owner's modes by guessing an id. It doesn't stop copying a real tag;
 * that's why a tag only ever toggles a mode, and modes that call or message
 * someone never start from a tag (the same rule as schedules).
 */
object ModeTags {

    const val MIME = "application/vnd.friday.mode"
    private const val VERSION = 1
    private const val MAC_BYTES = 16
    private const val ALGORITHM = "HmacSHA256"

    @Serializable
    private data class Payload(val v: Int, val id: String, val mac: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun write(modeId: String, secret: ByteArray): ByteArray =
        json.encodeToString(Payload.serializer(), Payload(VERSION, modeId, mac(VERSION, modeId, secret))).toByteArray()

    sealed interface Read {
        data class Mode(val id: String) : Read
        /** Not ours, unreadable, or a version this Friday doesn't know. */
        data object NotATag : Read
        /** Well formed, but not signed by this phone's secret. */
        data object Forged : Read
    }

    fun read(bytes: ByteArray, secret: ByteArray): Read {
        val p = runCatching { json.decodeFromString(Payload.serializer(), String(bytes)) }.getOrNull()
            ?: return Read.NotATag
        if (p.v != VERSION) return Read.NotATag
        val expected = mac(p.v, p.id, secret)
        // Constant-time: how much of a guess matched mustn't be learnable from timing.
        val genuine = java.security.MessageDigest.isEqual(expected.toByteArray(), p.mac.toByteArray())
        return if (genuine) Read.Mode(p.id) else Read.Forged
    }

    private fun mac(v: Int, id: String, secret: ByteArray): String {
        val m = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret, ALGORITHM)) }
        val sum = m.doFinal("$v|$id".toByteArray()).copyOf(MAC_BYTES)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sum)
    }
}
