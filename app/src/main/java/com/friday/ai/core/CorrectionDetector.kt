package com.friday.ai.core

/**
 * Spots the moment you tell Friday it got something wrong.
 *
 * Corrections are the highest-signal thing a user ever says — "нет, меня зовут
 * Тимур", "я же говорил, я не пью кофе" — and until now they were treated
 * as ordinary chat and forgotten. Catching them lets the correction outrank
 * whatever was learned before.
 *
 * Pure and conservative: a false positive rewrites memory with something the
 * user never meant, so a phrase only counts when it's clearly aimed at the
 * assistant's last answer.
 */
object CorrectionDetector {

    /** Openers that signal the previous answer was wrong. */
    private val CORRECTION_OPENERS = listOf(
        "нет,", "нет ", "не так", "неправильно", "неверно", "ошиб",
        "я же сказал", "я же говорил", "я говорил", "я сказал",
        "на самом деле", "вообще-то", "меня зовут не", "это не",
        "no,", "no ", "that's wrong", "thats wrong", "incorrect", "not right",
        "actually", "i said", "i told you", "wrong"
    )

    /** Phrases that introduce what the truth actually is. */
    private val REPLACEMENT_MARKERS = listOf(
        "я имел в виду", "имелось в виду", "правильно будет", "должно быть",
        "меня зовут", "мне нравится", "я предпочитаю", "я не",
        "i meant", "it should be", "my name is", "i prefer", "i don't", "i do not"
    )

    /** Short acknowledgements that aren't corrections even though they start with "нет". */
    private val NOT_CORRECTIONS = setOf(
        "нет", "no", "нет спасибо", "no thanks", "не надо", "не нужно"
    )

    data class Correction(
        /** The user's full corrective utterance. */
        val text: String,
        /** The part stating what is actually true, when it can be isolated. */
        val correctedFact: String?
    )

    /**
     * @param userText what the user just said
     * @param assistantSaidSomething whether Friday had actually answered — a
     *        correction needs something to correct.
     */
    fun detect(userText: String, assistantSaidSomething: Boolean): Correction? {
        if (!assistantSaidSomething) return null

        val trimmed = userText.trim()
        val lower = trimmed.lowercase()
        if (lower.isBlank()) return null

        // "нет" on its own is a refusal, not a correction.
        val bare = lower.trim('.', '!', '?', ',', ' ')
        if (bare in NOT_CORRECTIONS) return null

        // A correction has to actually say something beyond the objection.
        val words = lower.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (words.size < 3) return null

        val looksCorrective = CORRECTION_OPENERS.any { lower.startsWith(it) || lower.contains(" $it") } ||
            REPLACEMENT_MARKERS.any { lower.contains(it) }
        if (!looksCorrective) return null

        return Correction(text = trimmed, correctedFact = extractFact(trimmed))
    }

    /**
     * Pulls out the part after the objection, which is the bit worth
     * remembering: "нет, меня зовут Тимур" → "меня зовут Тимур".
     */
    private fun extractFact(text: String): String? {
        val lower = text.lowercase()

        REPLACEMENT_MARKERS
            .mapNotNull { marker ->
                val idx = lower.indexOf(marker)
                if (idx >= 0) text.substring(idx) else null
            }
            .maxByOrNull { it.length }
            ?.let { return it.trim().trimStart(',', ' ').ifBlank { null } }

        // Otherwise take whatever follows the first comma: "нет, я живу в Ташкенте".
        val comma = text.indexOf(',')
        if (comma in 0 until text.length - 1) {
            return text.substring(comma + 1).trim().ifBlank { null }
        }
        return null
    }

    /** How the correction is stored so it outranks earlier, wrong facts. */
    fun asMemoryNote(correction: Correction): String =
        "CORRECTION (trust this over earlier facts): " +
            (correction.correctedFact ?: correction.text)
}
