package com.friday.ai.core

import com.friday.ai.core.CalendarWriter.CalendarEvent

/**
 * Works out which calendar event a spoken instruction refers to.
 *
 * "Перенеси на 11" usually means the thing coming up next; "перенеси встречу
 * с врачом" names it. Titles are compared through [ContactMatcher.canonical]
 * so a title typed in Latin still matches speech recognised in Cyrillic —
 * the same script mismatch that broke calling.
 */
object EventMatcher {

    /**
     * @param titleHint what the user called the event, or null if they didn't
     * @param events upcoming events, soonest first
     * @return the event to act on, or null when nothing is a credible match
     */
    fun findBest(titleHint: String?, events: List<CalendarEvent>): CalendarEvent? {
        val candidates = events.filter { it.title.isNotBlank() }
        if (candidates.isEmpty()) return events.firstOrNull()

        val hint = titleHint?.trim()?.takeIf { it.isNotBlank() }
            // No name given: the soonest event is the sensible default.
            ?: return events.firstOrNull()

        val hintCanonical = ContactMatcher.canonical(hint)
        val hintWords = wordsOf(hint)

        return candidates
            .mapNotNull { event ->
                val s = score(hintCanonical, hintWords, event)
                if (s > 0) event to s else null
            }
            // Ties go to whichever happens first — that's the one being moved.
            .sortedWith(compareByDescending<Pair<CalendarEvent, Int>> { it.second }
                .thenBy { it.first.startMillis })
            .firstOrNull()
            ?.first
    }

    private fun score(hintCanonical: String, hintWords: Set<String>, event: CalendarEvent): Int {
        val titleCanonical = ContactMatcher.canonical(event.title)
        if (titleCanonical == hintCanonical) return 100
        if (titleCanonical.contains(hintCanonical) || hintCanonical.contains(titleCanonical)) return 80

        val titleWords = wordsOf(event.title)
        val shared = titleWords.count { titleWord ->
            hintWords.any { hintWord ->
                ContactMatcher.stem(titleWord) == ContactMatcher.stem(hintWord)
            }
        }
        return if (shared > 0) 40 + shared * 10 else 0
    }

    private fun wordsOf(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 }
            .toSet()
}
