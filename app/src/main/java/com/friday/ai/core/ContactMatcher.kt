package com.friday.ai.core

/** A phone-book entry reduced to what matching needs. */
data class Contact(val name: String, val number: String)

/**
 * Resolves a spoken name to a phone-book entry.
 *
 * Two things make this non-trivial in practice:
 *  - Russian declension: you say "позвони *папе*" but the contact is saved
 *    as "Папа". Comparing the raw strings never matches.
 *  - Relationship words: you say "папа" but the contact may be saved as
 *    "Отец", "Dad", or "Батя".
 *
 * Kept free of Android APIs so the matching rules can be unit-tested.
 */
object ContactMatcher {

    /**
     * Words that mean the same person. Any term in a group is tried when the
     * user says any other term from that group.
     */
    private val RELATIONSHIP_GROUPS: List<Set<String>> = listOf(
        // "отец" and "мать" decline irregularly — the oblique stem differs
        // from the nominative one ("отцу" -> отц, "матери" -> матер), so the
        // spoken forms are listed explicitly rather than derived.
        // Pet names ("мамуля", "mamito", "папочка") need no listing: they
        // start with the same stem and are caught by the prefix rule below.
        setOf(
            "папа", "пап", "отец", "отца", "отцу", "отцом", "батя",
            "dad", "daddy", "father", "papa", "pops"
        ),
        setOf(
            "мама", "мам", "мать", "матери", "матерью", "маман",
            "mom", "mum", "mommy", "mother", "mama", "madre"
        ),
        setOf("брат", "брата", "брату", "братан", "brother", "bro"),
        setOf("сестра", "сестре", "сестру", "сестрёнка", "сестренка", "sister", "sis"),
        setOf("жена", "жене", "супруга", "wife"),
        setOf("муж", "мужу", "супруг", "husband"),
        setOf("сын", "сына", "сыну", "son"),
        setOf("дочь", "дочери", "дочка", "дочке", "daughter"),
        setOf("бабушка", "бабушке", "баба", "grandma", "grandmother"),
        setOf("дедушка", "дедушке", "дед", "деду", "grandpa", "grandfather"),
        setOf("друг", "другу", "friend")
    )

    /**
     * What is left of a stem after a pet-name suffix, in the transliterated
     * form [stem] produces: мам|уля, мам|очка, mam|ito, влад|ик, mom|my.
     */
    private val DIMINUTIVE_TAILS = setOf(
        "ul", "ulk", "och", "ochk", "ichk", "ushk", "ik", "it", "is", "onk", "m", "d", ""
    )

    /** Trailing letters that case endings are built from, in either script. */
    private const val CASE_ENDINGS = "аеёиоуыэюяьйaeiouy"

    /**
     * Cyrillic to Latin, using the *simplest* single-letter choice where
     * several are common ("х" → h, not kh). The Latin side gets the same
     * collapsing in [canonical], so both spellings meet in the middle.
     */
    private val CYRILLIC_TO_LATIN: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
        'е' to "e", 'ё' to "e", 'ж' to "j", 'з' to "z", 'и' to "i",
        'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
        'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch",
        'ш' to "sh", 'щ' to "sh", 'ъ' to "", 'ы' to "y", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    /**
     * Multi-letter Latin spellings collapsed to the same choice the
     * transliterator makes, so "Khasan"/"Хасан" and "Zhanna"/"Жанна" match.
     */
    private val LATIN_DIGRAPHS: List<Pair<String, String>> = listOf(
        "sch" to "sh", "kh" to "h", "zh" to "j", "ts" to "c", "ck" to "k"
    )

    /**
     * Brings a name to one comparable form regardless of the script it was
     * written in.
     *
     * This matters because a phone book is usually Latin ("Kashif", "Father")
     * while speech recognised in Russian comes back Cyrillic ("Кашифу").
     * Without this the two never match and every call fails.
     */
    fun canonical(word: String): String {
        val lowered = word.lowercase().trim()
        val transliterated = buildString {
            for (ch in lowered) {
                append(CYRILLIC_TO_LATIN[ch] ?: ch.toString())
            }
        }
        return LATIN_DIGRAPHS.fold(transliterated) { acc, (from, to) -> acc.replace(from, to) }
    }

    /**
     * Reduces a word to a script-independent stem, so different cases of the
     * same name collapse together: "папе"/"папа"/"папу" and "papa" all become
     * the same thing.
     */
    fun stem(word: String): String {
        var t = canonical(word)
        while (t.length > 2 && t.last() in CASE_ENDINGS) {
            t = t.dropLast(1)
        }
        return t
    }

    /**
     * Picks the contact that best matches [query], or null if nothing is a
     * credible match. Never guesses wildly — a wrong number is worse than
     * admitting the contact wasn't found.
     */
    fun findBest(query: String, contacts: List<Contact>): Contact? =
        ranked(query, contacts) { it.name }.firstOrNull()?.first

    /**
     * Every credible match for [query], best first, with its score — so a
     * caller can tell a clear winner from a tie.
     */
    fun <T> ranked(query: String, items: List<T>, name: (T) -> String): List<Pair<T, Int>> {
        val cleaned = query.lowercase().trim().trim('.', ',', '!', '?')
        if (cleaned.isBlank() || items.isEmpty()) return emptyList()

        val queryStems = expandToStems(cleaned)
        if (queryStems.isEmpty()) return emptyList()

        return items
            .mapNotNull { item ->
                val score = score(queryStems, cleaned, name(item))
                if (score > 0) item to score else null
            }
            .sortedByDescending { it.second }
    }

    /** The query's own stems plus stems of any relationship synonyms. */
    private fun expandToStems(query: String): Set<String> {
        val words = query.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        val stems = words.map { stem(it) }.toMutableSet()

        for (word in words) {
            val wordStem = stem(word)
            for (group in RELATIONSHIP_GROUPS) {
                val belongs = group.any { stem(it) == wordStem }
                if (belongs) stems += group.map { stem(it) }
            }
        }
        return stems.filter { it.isNotBlank() }.toSet()
    }

    /** Shorter stems collide with unrelated names when matched as prefixes. */
    private const val MIN_PREFIX = 3

    // How sure a match is; only the order matters.
    private const val EXACT = 100
    private const val WHOLE_NAME = 90
    private const val PET_FORM = 80
    private const val ONE_WORD = 70
    private const val PREFIX = 50

    /**
     * Spelled a letter or two differently: "Фирдаус" for Фирдавс, "Аврор"
     * for Аброр — how speech recognition gets names it doesn't know. Ranked
     * below every real match, and a caller about to act on it alone should
     * ask first ("Мадина" is one letter from "Марина").
     */
    const val FUZZY = 60

    /** Names shorter than this are too short to be told apart from a typo. */
    private const val FUZZY_MIN = 5
    private const val FUZZY_LONG = 8

    /** Higher is better; 0 means "not a match". */
    private fun score(queryStems: Set<String>, rawQuery: String, name: String): Int {
        val lowered = name.lowercase().trim()
        // Latin "x" is "кс" in Alex but "х" in the Uzbek Shoxrux; both are tried.
        val readings =
            if ('x' in lowered) listOf(lowered.replace("x", "h"), lowered.replace("x", "ks")) else listOf(lowered)
        return readings.maxOf { scoreAs(queryStems, rawQuery, it) }
    }

    private fun scoreAs(queryStems: Set<String>, rawQuery: String, contactName: String): Int {
        val contactStems = contactName.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.map { stem(it) }
        val prefixes = queryStems.filter { it.length >= MIN_PREFIX }

        // A pet form of what was said: "Мамуля", "Mamito", "Владик" — ranked
        // above a bare prefix so "маме" prefers "Мамуля" to "Мамед".
        val petForm = prefixes.any { q ->
            contactStems.any { it.startsWith(q) && it.removePrefix(q) in DIMINUTIVE_TAILS }
        }
        // A bare prefix catches nicknames and truncated forms ("Влад"/"Владимир").
        val prefix = prefixes.any { q -> contactStems.any { it.startsWith(q) || q.startsWith(it) } }

        return when {
            contactName.isBlank() -> 0
            // Compared canonically so script differences don't block an exact hit.
            canonical(contactName) == canonical(rawQuery) -> EXACT
            // The whole contact name is one of the query's stems, e.g. "Папа".
            contactStems.size == 1 && contactStems.single() in queryStems -> WHOLE_NAME
            // Split or joined differently: "Абдул Азиз" for Абдулазиз.
            joined(contactName).let { it.length >= FUZZY_MIN && it == joined(rawQuery) } -> WHOLE_NAME
            petForm -> PET_FORM
            // One word of a multi-word contact matches, e.g. "Иван Петров".
            contactStems.any { it in queryStems } -> ONE_WORD
            queryStems.any { q -> contactStems.any { c -> close(q, c) } } -> FUZZY
            prefix -> PREFIX
            else -> 0
        }
    }

    private fun joined(name: String): String = stem(name.filter { it.isLetter() })

    /** One letter off for an ordinary name, two for a long one. */
    private fun close(a: String, b: String): Boolean {
        val shorter = minOf(a.length, b.length)
        if (shorter < FUZZY_MIN) return false
        val allowed = if (shorter >= FUZZY_LONG) 2 else 1
        return kotlin.math.abs(a.length - b.length) <= allowed && distance(a, b) <= allowed
    }

    /** Levenshtein distance. */
    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
