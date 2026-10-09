package com.friday.ai.core.links

/**
 * Whether a quick link's phrase was said, the way people actually say it.
 *
 * Word for word was too strict: with "давай посмотрим фильм" saved, "я хочу
 * посмотреть фильмы" went to the model, which asked which streaming service
 * to open. So words are compared by their stems ("посмотреть" =
 * "посмотрим", "фильмы" = "фильм"), the lead-in words of a request
 * ("давай", "хочу", "ну", "пожалуйста", the name) are optional, and the
 * phrase's own words must come in order, at most two other words apart.
 */
internal object PhraseMatch {

    /** Words that start a request without saying what it is. */
    private val OPTIONAL = setOf(
        "давай", "давайте", "хочу", "хотим", "хотел", "хотела", "я", "мы", "мне", "нам", "ну", "пожалуйста",
        "пятница", "фрайдей", "может", "можно", "ка", "а", "и", "вот", "сейчас", "let", "lets", "s", "i", "want",
        "to", "please", "friday", "now", "just"
    )

    /** Russian endings, longest first; English words lose only a plural "s". */
    private val ENDINGS = listOf(
        "иями", "ями", "ами", "ого", "его", "ому", "ему", "ыми", "ими", "ешь", "ете", "ить", "еть", "ать", "ять",
        "ишь", "ите", "ют", "ут", "ят", "ат", "ет", "ит", "ем", "им", "ом", "ам", "ям", "ах", "ях", "ов", "ев",
        "ей", "ой", "ий", "ый", "ая", "яя", "ое", "ее", "ые", "ие", "ую", "юю", "ся", "сь",
        "а", "я", "о", "е", "ы", "и", "у", "ю", "ь", "й"
    )

    private const val MIN_STEM = 3

    /** Other words allowed between two of the phrase's own. */
    private const val MAX_GAP = 2

    /** A one-word phrase ("кино") counts only in a short request, not anywhere in a conversation. */
    private const val ONE_WORD_REQUEST = 2

    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    /** What matters in [text]: its words, lead-ins dropped, as stems. */
    fun core(text: String): List<String> =
        text.lowercase().replace('ё', 'е').split(nonWord)
            .filter { it.isNotEmpty() && it !in OPTIONAL }
            .map(::stem)

    fun stem(word: String): String {
        val ending = ENDINGS.firstOrNull { word.endsWith(it) && word.length - it.length >= MIN_STEM }
        return when {
            ending != null -> word.dropLast(ending.length)
            word.length > MIN_STEM && word.last() == 's' && word.all { it in 'a'..'z' } -> word.dropLast(1)
            else -> word
        }
    }

    /** How many of [phrase]'s words were said, in order; 0 when it wasn't said. */
    fun score(phrase: List<String>, said: List<String>): Int {
        if (phrase.isEmpty() || (phrase.size == 1 && said.size > ONE_WORD_REQUEST)) return 0
        var at = -1
        for (word in phrase) {
            val next = (at + 1 until said.size).firstOrNull { said[it] == word } ?: return 0
            if (at >= 0 && next - at - 1 > MAX_GAP) return 0
            at = next
        }
        return phrase.size
    }
}
