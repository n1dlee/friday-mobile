package com.friday.ai.core.people

import com.friday.ai.core.Contact
import com.friday.ai.core.ContactMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameHintsTest {

    @Test
    fun `first names, once each, in order`() {
        assertEquals(
            "Аброр, Абдулазиз, Фирдавс, Мама.",
            NameHints.prompt(listOf("Аброр", "Абдулазиз Каримов", "Фирдавс", "аброр", "Мама", "+998 90 123"))
        )
        assertNull(NameHints.prompt(emptyList()))
    }

    @Test
    fun `names saved in Latin are given as they are said`() {
        assertEquals("фирдавс", NameHints.cyrillic("Firdavs"))
        assertEquals("джамшид", NameHints.cyrillic("Jamshid"))
        assertEquals("шохрух", NameHints.cyrillic("Shoxrux"))
        assertEquals("уткир", NameHints.cyrillic("O'tkir"))
        assertEquals("Милана", NameHints.cyrillic("Милана"))
        assertEquals("Абдулазиз.", NameHints.prompt(listOf("Abdulaziz")))
    }

    @Test
    fun `the prompt stays short enough for Whisper`() {
        val letters = "абвгдежзиклмнопрст"
        val n = letters.length
        val many = (0 until 300).map { "Нам" + letters[it % n] + letters[it / n % n] }
        assertTrue(NameHints.prompt(many)!!.length <= 460)
    }

    @Test
    fun `whisper repeating its prompt is not something said`() {
        val prompt = "Аброр, Абдулазиз, Фирдавс, Милана."
        assertTrue(NameHints.isEcho("Аброр, Абдулазиз, Фирдавс.", prompt))
        assertFalse(NameHints.isEcho("Позвони Аброру", prompt))
        assertFalse(NameHints.isEcho("Фирдавс", prompt))
        assertFalse(NameHints.isEcho("Аброр Абдулазиз Фирдавс", null))
    }
}

class TurkicNamesTest {

    private val book = listOf(
        Contact("Аброр", "1"), Contact("Абдулазиз", "2"), Contact("Фирдавс", "3"),
        Contact("Марина", "4"), Contact("Shoxrux Karimov", "5")
    )

    private fun match(query: String) = ContactMatcher.findBest(query, book)?.name

    @Test
    fun `case endings on Turkic names`() {
        assertEquals("Аброр", match("Аброру"))
        assertEquals("Абдулазиз", match("Абдулазизу"))
        assertEquals("Фирдавс", match("Фирдавса"))
        assertEquals("Shoxrux Karimov", match("Шохруху"))
    }

    @Test
    fun `a name split in two by the recogniser`() {
        assertEquals("Абдулазиз", match("Абдул Азиз"))
        assertEquals("Абдулазиз", match("абдул-азизу"))
    }

    @Test
    fun `near spellings match, below any real match`() {
        assertEquals("Фирдавс", match("Фирдаусу"))
        assertEquals("Аброр", match("Аврор"))
        assertEquals(ContactMatcher.FUZZY, ContactMatcher.ranked("Фирдаус", book) { it.name }.first().second)
    }

    @Test
    fun `a near spelling alone is asked about, not called`() {
        val people = listOf(Person("Марина", emptyList()), Person("Иван", emptyList()))
        val r = PeopleResolver.resolve("Мадине", people)
        assertEquals(listOf("Марина"), (r as PeopleResolver.Result.Ambiguous).candidates.map { it.name })
        assertTrue(PeopleResolver.resolve("Марине", people) is PeopleResolver.Result.Found)
    }

    @Test
    fun `short names are not guessed`() {
        assertNull(ContactMatcher.findBest("Маша", listOf(Contact("Миша", "1"))))
    }
}
