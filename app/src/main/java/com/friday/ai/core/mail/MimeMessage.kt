package com.friday.ai.core.mail

import java.util.Base64

/**
 * Builds the RFC 2822 message the Gmail API expects in `raw`.
 *
 * Plain text only, UTF-8 throughout. Non-ASCII headers are encoded words and
 * the body is base64, so Cyrillic survives every relay on the way. The From
 * header is left out: Gmail fills in the signed-in account itself, and
 * writing it here could only get it wrong.
 */
object MimeMessage {

    data class Draft(
        val to: String,
        val subject: String,
        val body: String,
        /** Message-ID of the mail being answered, so it threads correctly. */
        val inReplyTo: String? = null,
        val references: String? = null
    )

    /** Base64 line length from RFC 2045. */
    private const val LINE = 76

    /** Characters a header may carry as they are, without encoding. */
    private val PRINTABLE_ASCII = 0x20..0x7E

    fun build(d: Draft): String = buildString {
        append("To: ").append(d.to).append("\r\n")
        append("Subject: ").append(encodeHeader(d.subject)).append("\r\n")
        d.inReplyTo?.let { append("In-Reply-To: ").append(it).append("\r\n") }
        (d.references ?: d.inReplyTo)?.let { append("References: ").append(it).append("\r\n") }
        append("MIME-Version: 1.0\r\n")
        append("Content-Type: text/plain; charset=UTF-8\r\n")
        append("Content-Transfer-Encoding: base64\r\n")
        append("\r\n")
        Base64.getEncoder().encodeToString(d.body.toByteArray(Charsets.UTF_8))
            .chunked(LINE).forEach { append(it).append("\r\n") }
    }

    /** The whole message, base64url-encoded, as Gmail's `raw` field wants it. */
    fun raw(d: Draft): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(build(d).toByteArray(Charsets.UTF_8))

    /** Plain ASCII stays readable; anything else becomes an RFC 2047 encoded word. */
    fun encodeHeader(value: String): String {
        val clean = value.replace("\r", " ").replace("\n", " ")
        if (clean.all { it.code in PRINTABLE_ASCII }) return clean
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(clean.toByteArray(Charsets.UTF_8)) + "?="
    }

    /** "Re: " once, never "Re: Re: Re:". */
    fun replySubject(original: String): String {
        val s = original.trim()
        return if (s.startsWith("re:", ignoreCase = true)) s else "Re: $s".trimEnd()
    }

    /**
     * A subject for a new mail dictated without one: the opening of the body,
     * cut at a word boundary. Honest about where it came from, and the
     * read-back shows it before anything is sent.
     */
    fun subjectFrom(body: String, maxLength: Int = 50): String {
        val firstSentence = body.trim().split(Regex("""(?<=[.!?])\s""")).first().trimEnd('.', '!', '?')
        if (firstSentence.length <= maxLength) return firstSentence
        val cut = firstSentence.take(maxLength)
        return cut.substringBeforeLast(' ', cut).trimEnd(',', ' ') + "…"
    }

    /** Spoken text tidied into a sentence: capital first letter, closing stop. */
    fun tidyBody(spoken: String): String {
        val t = spoken.trim()
        if (t.isEmpty()) return t
        val capped = t.replaceFirstChar { it.uppercase() }
        return if (capped.last() in ".!?…") capped else "$capped."
    }
}
