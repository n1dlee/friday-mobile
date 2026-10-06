package com.friday.ai.core

/**
 * Splits a streaming LLM response into speakable chunks as tokens arrive.
 *
 * This is what lets Friday start talking after the first sentence instead of
 * waiting for the whole answer to finish generating and synthesizing — the
 * single biggest remaining source of perceived lag in the voice loop.
 *
 * Not a general-purpose sentence tokenizer: it only needs to find pauses that
 * sound natural when spoken, and it must never emit a fragment it might later
 * regret, because audio can't be taken back once played.
 */
class SentenceChunker(
    private val minChunkChars: Int = 20,
    private val maxChunkChars: Int = 180
) {

    private companion object {
        const val TERMINATORS = ".!?…"
        val SOFT_BREAKS = charArrayOf(',', ';', ':', '—', '–')
    }

    private val buffer = StringBuilder()

    /** Feeds one streamed token, returning any chunks that are ready to speak. */
    fun append(token: String): List<String> {
        buffer.append(token)
        val ready = mutableListOf<String>()
        while (true) {
            val cut = nextCut() ?: break
            val chunk = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            if (chunk.isNotEmpty()) ready.add(chunk)
        }
        return ready
    }

    /** Returns whatever is left over once the stream ends, if anything. */
    fun flush(): String? {
        val rest = buffer.toString().trim()
        buffer.setLength(0)
        return rest.ifEmpty { null }
    }


    /**
     * Index to cut at, or null if nothing is safely speakable yet. A cut is
     * only taken once the terminator is followed by whitespace, so a sentence
     * still being generated is never split mid-word.
     */
    private fun nextCut(): Int? {
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            if (c == '\n') {
                if (i + 1 >= minChunkChars) return skipSpace(i + 1)
                i++
                continue
            }
            if (c in TERMINATORS) {
                var end = i + 1
                while (end < buffer.length && buffer[end] in TERMINATORS) end++
                val confirmed = end < buffer.length && buffer[end].isWhitespace()
                if (confirmed && end >= minChunkChars && !isFalseStop(i)) {
                    return skipSpace(end)
                }
                i = end
                continue
            }
            i++
        }
        return overlongCut()
    }

    /**
     * A period isn't a sentence end when it sits inside a number (3.5) or a
     * short abbreviation (т.д., e.g.) — speaking those as separate utterances
     * produces obviously wrong pauses.
     */
    private fun isFalseStop(dotIndex: Int): Boolean {
        if (buffer[dotIndex] != '.') return false
        val prev = buffer.getOrNull(dotIndex - 1) ?: return false
        val next = buffer.getOrNull(dotIndex + 1)
        if (prev.isDigit() && next != null && next.isDigit()) return true
        // Single letter preceded by a dot or a space: the "д" of "т.д."
        if (prev.isLetter()) {
            val before = buffer.getOrNull(dotIndex - 2)
            if (before == null || before == '.' || before.isWhitespace()) return true
        }
        return false
    }

    /**
     * Guards against a model that streams a very long clause without any
     * terminator: fall back to a comma-level pause so speech still starts.
     */
    private fun overlongCut(): Int? {
        if (buffer.length < maxChunkChars) return null
        val window = buffer.substring(0, maxChunkChars)
        val soft = window.lastIndexOfAny(SOFT_BREAKS)
        if (soft >= minChunkChars) return skipSpace(soft + 1)
        val space = window.lastIndexOf(' ')
        return if (space >= minChunkChars) skipSpace(space) else null
    }

    private fun skipSpace(from: Int): Int {
        var k = from
        while (k < buffer.length && buffer[k].isWhitespace()) k++
        return k
    }
}
