package com.friday.ai.core

/**
 * Decides whether a chunk of recognised speech is the user addressing Friday.
 *
 * Two constraints shape this:
 *
 *  1. The offline recogniser runs a **Russian** acoustic model, so the English
 *     word "Friday" never comes back spelled that way — it arrives as Cyrillic
 *     approximations ("фрайдей", "прайди", "фрайди"). Both the English name and
 *     the Russian one therefore have to be matched through Cyrillic spellings.
 *
 *  2. "Пятница" is also just the Russian word for the weekday. Matching it
 *     anywhere in a sentence would wake the assistant during "встретимся в
 *     пятницу". So a wake word only counts at the *start* of what was heard,
 *     or right after an attention word ("окей пятница").
 */
object WakePhrases {

    /** Spellings the Russian recogniser produces for the English "Friday". */
    private val ENGLISH_NAME = listOf(
        "фрайдей", "фрайди", "фрайде", "фрайд", "фрэйди", "фрейди",
        "прайдей", "прайди", "friday"
    )

    /** The Russian name. */
    private val RUSSIAN_NAME = listOf(
        "пятница", "пятницы", "пятнице", "пятницу", "пятнеца", "пятниц"
    )

    /** Optional politeness before the name: "окей пятница", "hey friday". */
    private val ATTENTION_PREFIXES = listOf(
        "окей", "оке", "ок", "привет", "слушай", "эй", "hey", "ok", "okay", "hi"
    )


    /** Every accepted name, longest first so "фрайдей" wins over "фрай". */
    private val ALL_NAMES: List<String> =
        (ENGLISH_NAME + RUSSIAN_NAME).sortedByDescending { it.length }

    /**
     * Words handed to Vosk as a decoding grammar.
     *
     * Only Cyrillic entries: a grammar word that isn't in the acoustic model's
     * vocabulary can break the recogniser, and the model here is Russian — it
     * has no Latin "friday". Recognition of the English name still works
     * because it comes back spelled in Cyrillic anyway.
     */
    fun grammarVocabulary(): List<String> =
        (ALL_NAMES + ATTENTION_PREFIXES).filter { entry ->
            entry.all { it in 'а'..'я' || it == 'ё' || it == ' ' }
        }.distinct()

    /**
     * True when [heard] is the user calling Friday.
     *
     * @param heard text from the recogniser, already lower-cased or not.
     */
    fun isWakeCall(heard: String): Boolean = nameIndex(normalize(heard)) != null

    /** Which of the decoder's [words] is the name, or null if they are not a wake call. */
    fun nameIndexIn(words: List<String>): Int? = nameIndex(words.map { it.lowercase().trim() })

    /**
     * Index of the word that is the name, or null if this isn't a wake call.
     *
     * The name must open the utterance, or follow a single attention word.
     * It is tempting to relax this — a hesitation before the name really does
     * get the call rejected — but it cannot be done at this layer. Grammar
     * decoding collapses everything outside the grammar to `[unk]`, so
     * "ээ, пятница" and "…в пятницу" both arrive as `[unk] пятница`. There is
     * no information left to tell a call apart from talk about the weekday,
     * and waking up in the middle of someone's sentence is the worse failure.
     *
     * What actually fixed the missed calls was upstream, in [VoiceGate]:
     * feeding the decoder the start of the word and clearing its state only
     * between utterances, so a real call arrives with the name in front.
     */
    private fun nameIndex(words: List<String>): Int? {
        if (words.isEmpty()) return null
        if (isName(words[0])) return 0
        if (words.size >= 2 && words[0] in ATTENTION_PREFIXES && isName(words[1])) return 1
        return null
    }

    // startsWith rather than equality: the recogniser often glues a case
    // ending on ("пятницу") or trails off mid-word.
    private fun isName(word: String): Boolean = ALL_NAMES.any { word.startsWith(it) }

    /**
     * Which language the user addressed Friday in, or null if it wasn't a
     * wake call. Lets the reply match the language the user chose.
     */
    fun detectLanguage(heard: String): Language? {
        val words = normalize(heard)
        val candidate = nameIndex(words)?.let { words[it] } ?: return null
        return when {
            RUSSIAN_NAME.any { candidate.startsWith(it) } -> Language.RUSSIAN
            else -> Language.ENGLISH
        }
    }

    enum class Language { RUSSIAN, ENGLISH }

    private val leadingCall = Regex(
        "^\\s*(?:(?:окей|оке|ок|привет|слушай|эй|hey|ok|okay|hi)[\\s,!.]+)?" +
            "(?:пятниц\\p{L}*|пятнец\\p{L}*|фрайд\\p{L}*|фрэйди|фрейди|friday)[\\s,!.:;—–-]*",
        RegexOption.IGNORE_CASE
    )

    /**
     * [transcript] without the call in front: "Пятница, включи музыку" →
     * "включи музыку". When the command was said in the same breath as the
     * name, the recording starts with the name too.
     */
    fun stripCall(transcript: String): String = transcript.replaceFirst(leadingCall, "").trim()

    private fun normalize(heard: String): List<String> =
        heard.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
}
