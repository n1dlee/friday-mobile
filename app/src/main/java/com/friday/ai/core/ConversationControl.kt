package com.friday.ai.core

/**
 * Decides when a spoken conversation is over.
 *
 * The voice loop keeps listening after each reply so you can talk naturally
 * without repeating the wake word. That only works if ending the conversation
 * is reliable — otherwise the overlay keeps popping back up and gets in the
 * way, which is exactly what an exact-match stop word list caused: "bye bye",
 * "ok bye" and "спасибо, пока" all slipped through.
 */
object ConversationControl {

    /** Single words that end a conversation when the utterance is short. */
    private val FAREWELL_WORDS = setOf(
        // Russian
        "пока", "покеда", "стоп", "хватит", "довольно", "всё", "все",
        "спасибо", "отбой", "закончили", "выйди", "отстань",
        // English
        "bye", "goodbye", "stop", "enough", "thanks", "thank", "quit",
        "exit", "cancel", "dismiss", "nevermind"
    )

    /** Multi-word endings that are unambiguous regardless of length. */
    private val FAREWELL_PHRASES = listOf(
        "that's all", "thats all", "that is all", "nothing else", "never mind",
        "это всё", "это все", "больше ничего", "на этом всё", "на этом все",
        "спасибо всё", "спасибо все", "до свидания", "всего доброго"
    )

    /**
     * Beyond this many words we assume it's a real request that merely
     * contains a polite word, e.g. "thanks, now open YouTube".
     */
    private const val MAX_WORDS_FOR_SHORT_FAREWELL = 4

    /** True when [text] is the user (or the assistant) closing the conversation. */
    fun isFarewell(text: String): Boolean {
        val normalized = text.lowercase().trim()
        if (normalized.isEmpty()) return false

        if (FAREWELL_PHRASES.any { normalized.contains(it) }) return true

        val words = normalized
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }

        if (words.isEmpty() || words.size > MAX_WORDS_FOR_SHORT_FAREWELL) return false

        return words.any { it in FAREWELL_WORDS }
    }
}
