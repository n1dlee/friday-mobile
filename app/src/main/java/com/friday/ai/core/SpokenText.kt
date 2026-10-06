package com.friday.ai.core

/** Normalisation shared by every matcher that compares speech against a word list. */
object SpokenText {

    private val separators = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Lower-cases, folds ё into е, and reduces every run of punctuation —
     * hyphens included — to a single space.
     *
     * Hyphens go too because the recogniser is inconsistent about them:
     * "вай-фай" and "вай фай" both turn up. Keywords and input pass through the
     * same function, so "wi-fi" in a keyword list still matches either spelling.
     */
    fun normalise(s: String): String =
        s.lowercase().replace('ё', 'е')
            .split(separators)
            .filter { it.isNotBlank() }
            .joinToString(" ")
}
