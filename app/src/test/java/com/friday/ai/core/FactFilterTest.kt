package com.friday.ai.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactFilterTest {

    @Test
    fun `a question is never stored as a fact`() {
        // The observed failure: asking "как меня зовут?" saved the question as
        // the answer, after which Friday insisted she remembered the name.
        assertFalse(FactFilter.isStorable("name", "меня зовут?"))
        assertFalse(FactFilter.isStorable("name", "как меня зовут"))
        assertFalse(FactFilter.isStorable("name", "what is my name"))
        assertFalse(FactFilter.isStorable("hobby", "какие у меня увлечения"))
    }

    @Test
    fun `real answers are stored`() {
        assertTrue(FactFilter.isStorable("name", "Тимур"))
        assertTrue(FactFilter.isStorable("age", "20"))
        assertTrue(FactFilter.isStorable("favorite_genre", "hip-hop"))
        assertTrue(FactFilter.isStorable("hobby", "программирование"))
    }

    @Test
    fun `a hedge is not a fact`() {
        assertFalse(FactFilter.isStorable("name", "не знаю"))
        assertFalse(FactFilter.isStorable("city", "unknown"))
        assertFalse(FactFilter.isStorable("job", "maybe a developer"))
    }

    @Test
    fun `echoing the key back is not a fact`() {
        assertFalse(FactFilter.isStorable("name", "name"))
    }

    @Test
    fun `empty and trivial values are rejected`() {
        assertFalse(FactFilter.isStorable("name", ""))
        assertFalse(FactFilter.isStorable("name", "x"))
        assertFalse(FactFilter.isStorable("", "Тимур"))
    }

    @Test
    fun `a legitimate value that merely mentions a question word survives`() {
        // "Какая-то" and "Kak" as parts of real answers must not be caught by
        // the interrogative rule, which only looks at the first word.
        assertTrue(FactFilter.isStorable("music", "группа Кто здесь"))
        assertTrue(FactFilter.isStorable("note", "спросить сколько стоит"))
    }
}
