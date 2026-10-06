package com.friday.ai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Edge TTS handshake. These are regression tests for a real
 * outage: TTS silently 403-ed in production because the User-Agent didn't
 * convincingly impersonate Edge, and nothing caught it.
 */
class EdgeTtsProtocolTest {

    // Reference values computed independently (Python, reference edge-tts
    // algorithm) so this test fails if the Kotlin implementation drifts.
    private val referenceHash =
        "B0EDD22C7C09868E2F24C10264A8A3EB877773A7B6040B68AFA4FBCBABEA0238"
    private val alignedMillis = 1_735_689_600_000L // 2025-01-01T00:00:00Z

    @Test
    fun `sec-ms-gec matches the reference implementation`() {
        assertEquals(referenceHash, EdgeTtsProtocol.calcSecMsGec(alignedMillis))
    }

    @Test
    fun `sec-ms-gec is stable within a five minute window`() {
        // Anything inside the same 5-minute bucket must hash identically —
        // this is what lets the token be cached/reused by the server.
        assertEquals(referenceHash, EdgeTtsProtocol.calcSecMsGec(alignedMillis + 185_000))
        assertEquals(referenceHash, EdgeTtsProtocol.calcSecMsGec(alignedMillis + 299_999))
    }

    @Test
    fun `sec-ms-gec changes in the next five minute window`() {
        assertNotEquals(referenceHash, EdgeTtsProtocol.calcSecMsGec(alignedMillis + 300_000))
    }

    @Test
    fun `sec-ms-gec is uppercase hex of sha256 length`() {
        val hash = EdgeTtsProtocol.calcSecMsGec(alignedMillis)
        assertEquals(64, hash.length)
        assertTrue("expected uppercase hex, got $hash", hash.all { it in '0'..'9' || it in 'A'..'F' })
    }

    @Test
    fun `user agent impersonates a real Edge browser`() {
        // The exact failure that broke TTS: a truncated UA ending at
        // AppleWebKit/537.36 gets rejected with 403 by Microsoft.
        val ua = EdgeTtsProtocol.USER_AGENT
        assertTrue("UA must identify as Edge", ua.contains("Edg/"))
        assertTrue("UA must identify as Chrome", ua.contains("Chrome/"))
        assertTrue("UA must include Safari token", ua.contains("Safari/537.36"))
        assertTrue("UA must include Gecko token", ua.contains("(KHTML, like Gecko)"))
    }

    @Test
    fun `sec-ms-gec version is an edge version string`() {
        assertTrue(EdgeTtsProtocol.SEC_MS_GEC_VERSION.startsWith("1-"))
    }

    @Test
    fun `url carries both anti-abuse parameters`() {
        val url = EdgeTtsProtocol.buildUrl("abc123", alignedMillis)
        assertTrue(url.contains("TrustedClientToken=${EdgeTtsProtocol.TRUSTED_TOKEN}"))
        assertTrue(url.contains("ConnectionId=abc123"))
        assertTrue(url.contains("Sec-MS-GEC=$referenceHash"))
        assertTrue(url.contains("Sec-MS-GEC-Version=${EdgeTtsProtocol.SEC_MS_GEC_VERSION}"))
    }

    @Test
    fun `russian text selects the russian voice`() {
        assertEquals(EdgeTtsProtocol.VOICE_RU, EdgeTtsProtocol.detectVoice("Привет, как дела?"))
    }

    @Test
    fun `english text selects the english voice`() {
        assertEquals(EdgeTtsProtocol.VOICE_EN, EdgeTtsProtocol.detectVoice("Hello, how are you?"))
    }

    @Test
    fun `punctuation and digits do not sway voice detection`() {
        // Previously the latin counter used the range 'A'..'z', which also
        // swept up [ \ ] ^ _ ` and could outvote real Cyrillic letters.
        assertEquals(EdgeTtsProtocol.VOICE_RU, EdgeTtsProtocol.detectVoice("Готово: 100% [___]"))
    }

    @Test
    fun `ssml escapes characters that would break the xml`() {
        val ssml = EdgeTtsProtocol.buildSsmlMessage("rid", 1L, EdgeTtsProtocol.VOICE_EN, "5 < 6 & \"x\"")
        assertTrue(ssml.contains("5 &lt; 6 &amp; &quot;x&quot;"))
        assertTrue(ssml.contains("<voice name='${EdgeTtsProtocol.VOICE_EN}'>"))
    }

    @Test
    fun `audio payload starts after the path audio delimiter`() {
        val header = "X-RequestId:1\r\nContent-Type:audio/mpeg\r\nPath:audio\r\n"
        val frame = header.toByteArray() + byteArrayOf(1, 2, 3, 4)
        val start = EdgeTtsProtocol.audioPayloadStart(frame)
        assertEquals(header.length, start)
        assertEquals(listOf<Byte>(1, 2, 3, 4), frame.drop(start))
    }

    @Test
    fun `frames without audio are reported as having no payload`() {
        assertEquals(-1, EdgeTtsProtocol.audioPayloadStart(ByteArray(0)))
        assertEquals(-1, EdgeTtsProtocol.audioPayloadStart(byteArrayOf(0, 0)))
    }
}
