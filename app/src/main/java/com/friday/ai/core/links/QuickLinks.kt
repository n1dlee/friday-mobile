package com.friday.ai.core.links

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A site the owner opens by a phrase of their own: "давай посмотрим фильм"
 * → their film site, in a new Chrome tab.
 *
 * @param phrases what the owner says, as typed in Settings
 * @param silent open without a word, and leave no trace in the chat or the
 *   memory — the owner's business is theirs
 */
@Serializable
data class QuickLink(
    val id: String,
    val name: String,
    val phrases: List<String>,
    val url: String,
    val silent: Boolean = false
)

/**
 * Matching what was said to the owner's links, and keeping what they type
 * sensible. Pure, so it is tested without a phone.
 */
object QuickLinks {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(QuickLink.serializer())
    private val separators = Regex("""[,;\n]+""")
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    /**
     * The link whose phrase was said — as the whole utterance or inside it,
     * as whole words ("давай посмотрим фильм" in "ну давай посмотрим фильм").
     * The longest phrase wins, so a specific one beats a general one.
     */
    fun match(said: String, links: List<QuickLink>): QuickLink? {
        val text = " ${normalise(said)} "
        if (text.isBlank()) return null
        return links
            .flatMap { link -> link.phrases.map { normalise(it) }.filter { it.isNotEmpty() }.map { it to link } }
            .filter { (phrase, _) -> text.contains(" $phrase ") }
            .maxByOrNull { (phrase, _) -> phrase.length }
            ?.second
    }

    /** "фильм, давай посмотрим фильм" → the two phrases. */
    fun phrasesOf(typed: String): List<String> =
        typed.split(separators).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** A web address as typed, made openable ("example.com" → "https://example.com"); null if it isn't one. */
    fun webAddress(typed: String): String? {
        val raw = typed.trim()
        if (raw.isEmpty() || raw.any { it.isWhitespace() }) return null
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(raw)) raw else "https://$raw"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        return withScheme.takeIf { (scheme == "https" || scheme == "http") && !host.isNullOrBlank() && '.' in host }
    }

    fun encode(links: List<QuickLink>): String = json.encodeToString(serializer, links)

    fun decode(stored: String?): List<QuickLink> =
        stored?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()

    private fun normalise(text: String): String =
        text.lowercase().replace('ё', 'е').split(nonWord).filter { it.isNotEmpty() }.joinToString(" ")
}
