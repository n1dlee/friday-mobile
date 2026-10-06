package com.friday.ai.core.mail

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns Gmail API JSON into something Friday can talk about.
 *
 * Separate from the network client so the awkward parts — nested MIME parts,
 * base64url bodies, HTML-only mail, quoted reply chains — are tested against
 * fixed JSON instead of a live mailbox.
 */
object GmailMessages {

    data class Mail(
        val id: String,
        val threadId: String,
        val fromName: String,
        val fromAddress: String,
        val subject: String,
        val snippet: String,
        /** The Message-ID header, needed to thread a reply. */
        val messageId: String?,
        val references: String?,
        /** Readable text, quotes of earlier mail removed; null when only metadata was fetched. */
        val body: String?,
        /** First recipient — used to find addresses the user has written to before. */
        val toName: String = "",
        val toAddress: String = ""
    )

    data class Listing(val ids: List<String>, val estimate: Int)

    fun parseListing(json: String): Listing {
        val o = JSONObject(json)
        val arr = o.optJSONArray("messages") ?: JSONArray()
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
        return Listing(ids, o.optInt("resultSizeEstimate", ids.size))
    }

    fun parseMessage(json: String): Mail {
        val o = JSONObject(json)
        val payload = o.optJSONObject("payload") ?: JSONObject()
        val headers = headers(payload)
        val (name, address) = parseAddress(headers["from"].orEmpty())
        val (toName, toAddress) = headers["to"]?.let { parseAddress(firstRecipient(it)) } ?: ("" to "")
        val body = if (payload.has("parts") || payload.optJSONObject("body")?.has("data") == true) {
            extractText(payload)?.let(MailText::stripQuoted)
        } else null
        return Mail(
            id = o.getString("id"),
            threadId = o.optString("threadId", o.getString("id")),
            fromName = name,
            fromAddress = address,
            subject = headers["subject"].orEmpty().trim(),
            snippet = MailText.decodeEntities(o.optString("snippet", "")),
            messageId = headers["message-id"],
            references = headers["references"],
            body = body?.takeIf { it.isNotBlank() },
            toName = toName,
            toAddress = toAddress
        )
    }

    private fun headers(payload: JSONObject): Map<String, String> {
        val arr = payload.optJSONArray("headers") ?: return emptyMap()
        return (0 until arr.length()).associate {
            val h = arr.getJSONObject(it)
            h.optString("name").lowercase() to h.optString("value")
        }
    }

    /** `Иван Петров <ivan@x.com>` → (Иван Петров, ivan@x.com). A bare address names itself. */
    fun parseAddress(raw: String): Pair<String, String> {
        val m = Regex("""^\s*"?([^"<]*?)"?\s*<([^>]+)>\s*$""").find(raw)
        if (m != null) {
            val address = m.groupValues[2].trim()
            val name = m.groupValues[1].trim().ifBlank { address.substringBefore('@') }
            return name to address
        }
        val address = raw.trim()
        return address.substringBefore('@') to address
    }

    /** The first address of a To header, ignoring commas inside quoted names. */
    fun firstRecipient(raw: String): String {
        var quoted = false
        raw.forEachIndexed { i, c ->
            if (c == '"') quoted = !quoted
            if (c == ',' && !quoted) return raw.substring(0, i).trim()
        }
        return raw.trim()
    }

    /** Plain text if the mail has it, otherwise its HTML with the markup taken out. */
    fun extractText(part: JSONObject): String? {
        findPart(part, "text/plain")?.let { return decodeData(it) }
        findPart(part, "text/html")?.let { return MailText.htmlToText(decodeData(it)) }
        return null
    }

    private fun findPart(part: JSONObject, mime: String): JSONObject? {
        if (part.optString("mimeType").equals(mime, ignoreCase = true) &&
            part.optJSONObject("body")?.has("data") == true
        ) return part
        val parts = part.optJSONArray("parts") ?: return null
        for (i in 0 until parts.length()) {
            findPart(parts.getJSONObject(i), mime)?.let { return it }
        }
        return null
    }

    private fun decodeData(part: JSONObject): String {
        val data = part.getJSONObject("body").getString("data")
        return String(Base64.getUrlDecoder().decode(data.trimEnd('=')), Charsets.UTF_8)
    }
}
