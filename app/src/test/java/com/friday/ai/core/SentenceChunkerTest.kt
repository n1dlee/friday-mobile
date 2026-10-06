package com.friday.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceChunkerTest {

    /** Feeds text the way a stream would — a few characters at a time. */
    private fun streamAll(chunker: SentenceChunker, text: String, step: Int = 3): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val end = minOf(i + step, text.length)
            out += chunker.append(text.substring(i, end))
            i = end
        }
        chunker.flush()?.let { out += it }
        return out
    }

    @Test
    fun `emits a sentence as soon as it is complete`() {
        val c = SentenceChunker()
        assertTrue(c.append("Летом в Ташкенте очень жарко").isEmpty())
        // Terminator alone isn't enough — the following space confirms it.
        assertTrue(c.append(".").isEmpty())
        assertEquals(listOf("Летом в Ташкенте очень жарко."), c.append(" Обычно"))
    }

    @Test
    fun `does not split a sentence still being generated`() {
        val c = SentenceChunker()
        assertTrue(c.append("Сегодня").isEmpty())
        assertTrue(c.append(" будет").isEmpty())
        assertTrue(c.append(" тепло").isEmpty())
        // Held back, not lost: it all comes out once the stream ends.
        assertEquals("Сегодня будет тепло", c.flush())
    }

    @Test
    fun `splits a multi sentence answer in order`() {
        val text = "Погода сегодня ясная и тёплая. Ветер слабый, около трёх метров. " +
            "Дождя не ожидается совсем."
        val chunks = streamAll(SentenceChunker(), text)
        assertEquals(
            listOf(
                "Погода сегодня ясная и тёплая.",
                "Ветер слабый, около трёх метров.",
                "Дождя не ожидается совсем."
            ),
            chunks
        )
    }

    @Test
    fun `reassembling the chunks reproduces the original text`() {
        val text = "Первое предложение здесь. Второе предложение тут! А третье — вопрос? Да."
        val chunks = streamAll(SentenceChunker(), text)
        assertEquals(text.replace(Regex("\\s+"), " "), chunks.joinToString(" "))
    }

    @Test
    fun `decimal numbers do not break a sentence`() {
        val text = "Курс составляет 12.5 процента за весь прошедший год."
        assertEquals(listOf(text), streamAll(SentenceChunker(), text))
    }

    @Test
    fun `common abbreviations do not break a sentence`() {
        val text = "Возьми хлеб, молоко и т.д. из ближайшего магазина рядом."
        assertEquals(listOf(text), streamAll(SentenceChunker(), text))
    }

    @Test
    fun `very short fragments are merged rather than spoken alone`() {
        // "Да." on its own would be a jarringly short utterance mid-stream.
        val chunks = streamAll(SentenceChunker(), "Да. Именно так всё и произошло тогда.")
        assertEquals(listOf("Да. Именно так всё и произошло тогда."), chunks)
    }

    @Test
    fun `exclamation and question marks terminate sentences`() {
        val chunks = streamAll(SentenceChunker(), "Это отличная новость! Ты уже всё закончил?")
        assertEquals(listOf("Это отличная новость!", "Ты уже всё закончил?"), chunks)
    }

    @Test
    fun `newline is treated as a pause`() {
        val chunks = streamAll(SentenceChunker(), "Первый пункт списка\nВторой пункт списка\n")
        assertEquals(listOf("Первый пункт списка", "Второй пункт списка"), chunks)
    }

    @Test
    fun `a very long clause without punctuation still starts speaking`() {
        val long = "мы едем в магазин, " .repeat(20)
        val c = SentenceChunker(maxChunkChars = 120)
        val emitted = c.append(long)
        assertTrue("expected an early cut, got none", emitted.isNotEmpty())
        assertTrue("chunk should stay near the cap", emitted.first().length <= 120)
    }

    @Test
    fun `flush returns the trailing text with no terminator`() {
        val c = SentenceChunker()
        c.append("Ответ без завершающей точки")
        assertEquals("Ответ без завершающей точки", c.flush())
    }

    @Test
    fun `flush on an empty buffer returns null`() {
        assertNull(SentenceChunker().flush())
        val c = SentenceChunker()
        c.append("Готово к работе прямо сейчас. ")
        assertNull(c.flush())
    }

    @Test
    fun `english text splits the same way`() {
        val chunks = streamAll(SentenceChunker(), "It is warm today outside. Bring a light jacket.")
        assertEquals(listOf("It is warm today outside.", "Bring a light jacket."), chunks)
    }

    @Test
    fun `no text is ever lost across a full stream`() {
        val text = "Короткий. Средней длины предложение здесь. И ещё одно в самом конце"
        val chunks = streamAll(SentenceChunker(), text, step = 1)
        val rejoined = chunks.joinToString(" ")
        listOf("Короткий", "Средней длины", "И ещё одно").forEach {
            assertTrue("lost '$it' from the stream", rejoined.contains(it))
        }
    }
}
