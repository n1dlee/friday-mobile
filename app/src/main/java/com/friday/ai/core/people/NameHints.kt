package com.friday.ai.core.people

/**
 * The names Whisper is told to expect.
 *
 * Whisper knows Russian names; "Аброр", "Абдулазиз" or "Фирдавс" it hears
 * as "Аврора", "Абдул Азиз", "Фирдаус", and then no contact matches. Given
 * the owner's own names as a prompt, it spells them the way the phone book
 * does. Names saved in Latin letters ("Firdavs") are given in Cyrillic too,
 * since the owner speaks Russian and the transcript comes back Cyrillic.
 */
object NameHints {

    /** Whisper reads at most 224 tokens of prompt; Cyrillic names take two or three each. */
    private const val MAX_CHARS = 450

    private const val MIN_NAME = 3

    /** An answer made only of prompt names, this many or more, is Whisper repeating the prompt. */
    private const val ECHO_WORDS = 3

    private val latinDigraphs = listOf(
        "sh" to "ш", "ch" to "ч", "kh" to "х", "zh" to "ж", "ts" to "ц",
        "yo" to "ё", "yu" to "ю", "ya" to "я", "ye" to "е", "o'" to "у", "g'" to "г"
    )

    private val latinLetters = mapOf(
        'a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф", 'g' to "г", 'h' to "х",
        'i' to "и", 'j' to "дж", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п",
        'q' to "к", 'r' to "р", 's' to "с", 't' to "т", 'u' to "у", 'v' to "в", 'w' to "в", 'x' to "х",
        'y' to "й", 'z' to "з"
    )

    private val wordSplit = Regex("""[^\p{L}'ʻ’]+""")

    /**
     * "Аброр, Абдулазиз, Фирдавс." — first names, the most likely first
     * ([names] in order of importance), short enough for Whisper; null when
     * there are none.
     */
    fun prompt(names: List<String>): String? {
        var length = 0
        val kept = names.asSequence()
            .mapNotNull { raw -> raw.trim().split(wordSplit).firstOrNull { it.length >= MIN_NAME } }
            .map { cyrillic(it).replaceFirstChar { c -> c.uppercase() } }
            .filter { it.length >= MIN_NAME }
            .distinct()
            .takeWhile { name ->
                length += name.length + 2
                length <= MAX_CHARS
            }
            .toList()
        return kept.takeIf { it.isNotEmpty() }?.joinToString(", ", postfix = ".")
    }

    /** A Latin-spelled name the way it is said in Russian: "Firdavs" → "Фирдавс", "Jamshid" → "Джамшид". */
    fun cyrillic(name: String): String {
        if (name.none { it in 'a'..'z' || it in 'A'..'Z' }) return name
        var s = name.lowercase().replace('ʻ', '\'').replace('’', '\'')
        latinDigraphs.forEach { (from, to) -> s = s.replace(from, to) }
        return buildString {
            for (ch in s) if (ch != '\'') append(latinLetters[ch] ?: ch.toString())
        }
    }

    /** Whisper, given silence, sometimes answers with its prompt: that is not something said. */
    fun isEcho(text: String, prompt: String?): Boolean {
        if (prompt == null) return false
        val names = prompt.lowercase().split(wordSplit).filter { it.isNotBlank() }.toSet()
        val words = text.lowercase().split(wordSplit).filter { it.isNotBlank() }
        return words.size >= ECHO_WORDS && words.all { it in names }
    }
}
