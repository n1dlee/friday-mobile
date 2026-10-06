package com.friday.ai.service

import java.security.MessageDigest

/**
 * Pure protocol logic for Microsoft Edge's "read aloud" TTS websocket, kept
 * free of Android dependencies so it can be unit-tested.
 *
 * This exists because a malformed handshake here silently broke TTS in
 * production: Microsoft answers 403 unless the client convincingly looks like
 * the Edge browser, and nothing in the app caught it.
 */
object EdgeTtsProtocol {

    const val WSS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    private const val CHROMIUM_MAJOR = "143"
    const val SEC_MS_GEC_VERSION = "1-143.0.3650.75"

    /**
     * Must impersonate a current Edge build. Verified empirically 2026-07-30:
     * a truncated UA gets 403 *even with a valid Sec-MS-GEC signature*, while
     * this full UA succeeds regardless of the GEC version. If TTS starts
     * 403-ing again, bump this and [SEC_MS_GEC_VERSION] first.
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 " +
            "Edg/$CHROMIUM_MAJOR.0.0.0"

    const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    private const val WIN_EPOCH_SECONDS = 11_644_473_600L
    private const val AUDIO_DELIMITER = "Path:audio\r\n"

    // Female, because Friday is: every phrase she says about herself is in
    // the feminine ("не расслышала", "отправила"), and a male voice saying
    // them sounded wrong. The English voice is Irish, like F.R.I.D.A.Y.'s in
    // the films. Both names checked against Edge's published voice list.
    const val VOICE_RU = "ru-RU-SvetlanaNeural"
    const val VOICE_EN = "en-IE-EmilyNeural"

    /**
     * SHA-256 of (Windows "ticks" floored to a 5-minute window + the trusted
     * client token), uppercase hex. Integer math throughout: the tick value
     * exceeds the exactly-representable range of a Double.
     */
    fun calcSecMsGec(nowMillis: Long): String {
        var seconds = (nowMillis / 1000L) + WIN_EPOCH_SECONDS
        seconds -= seconds % 300L
        val ticks = seconds * 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((ticks.toString() + TRUSTED_TOKEN).toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    fun buildUrl(connectionId: String, nowMillis: Long): String =
        "$WSS_URL?TrustedClientToken=$TRUSTED_TOKEN" +
            "&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=${calcSecMsGec(nowMillis)}" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

    fun buildConfigMessage(requestId: String, timestamp: Long): String =
        "Path: speech.config\r\n" +
            "X-RequestId: $requestId\r\n" +
            "X-Timestamp: $timestamp\r\n" +
            "Content-Type: application/json\r\n\r\n" +
            """{"context":{"synthesis":{"audio":{"metadataOptions":""" +
            """{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},""" +
            """"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""

    fun buildSsmlMessage(requestId: String, timestamp: Long, voice: String, text: String): String =
        "Path: ssml\r\n" +
            "X-RequestId: $requestId\r\n" +
            "X-Timestamp: $timestamp\r\n" +
            "Content-Type: application/ssml+xml\r\n\r\n" +
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voice'>" +
            "<prosody pitch='+0Hz' rate='+8%' volume='+0%'>${escapeXml(text)}</prosody>" +
            "</voice></speak>"

    fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /**
     * Each binary frame is `<2-byte big-endian header length><headers><audio>`.
     * Headers always end with `Path:audio\r\n`; locating that is the most
     * robust way to find where the mp3 payload starts. Returns -1 if the frame
     * carries no audio.
     */
    fun audioPayloadStart(data: ByteArray): Int {
        val delim = AUDIO_DELIMITER.toByteArray()
        if (data.size >= delim.size) {
            outer@ for (i in 0..data.size - delim.size) {
                for (j in delim.indices) {
                    if (data[i + j] != delim[j]) continue@outer
                }
                return i + delim.size
            }
        }
        if (data.size > 2) {
            val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            if (headerLen in 1 until data.size) return headerLen + 2
        }
        return -1
    }

    /**
     * File name for a cached utterance. The voice is part of the key, so a
     * change of voice can never replay audio recorded in the old one.
     */
    fun cacheKey(voice: String, text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest("$voice|$text".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + ".mp3"
    }

    /** Picks a voice by script, so a Russian reply isn't read with an English accent. */
    fun detectVoice(text: String): String {
        val cyrillic = text.count { it in 'Ѐ'..'ӿ' }
        val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        return if (cyrillic > latin) VOICE_RU else VOICE_EN
    }
}
