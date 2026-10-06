package com.friday.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactMatcherTest {

    private val phoneBook = listOf(
        Contact("Папа", "+998901112233"),
        Contact("Мама", "+998901112244"),
        Contact("Иван Петров", "+998901112255"),
        Contact("Марина", "+998901112266"),
        Contact("Ravshan Aliyev", "+998901112277"),
        Contact("Работа", "+998901112288")
    )

    private fun call(query: String) = ContactMatcher.findBest(query, phoneBook)?.name

    @Test
    fun `dative case resolves to the contact`() {
        // "позвони папе" -> contact saved as "Папа"
        assertEquals("Папа", call("папе"))
        assertEquals("Мама", call("маме"))
        assertEquals("Марина", call("марине"))
    }

    @Test
    fun `accusative and other cases resolve too`() {
        assertEquals("Папа", call("папу"))
        assertEquals("Мама", call("маму"))
        assertEquals("Марина", call("марину"))
    }

    @Test
    fun `nominative still works`() {
        assertEquals("Папа", call("папа"))
        assertEquals("Марина", call("Марина"))
    }

    @Test
    fun `relationship synonyms find the contact`() {
        // Contact is "Папа" but the user says "отцу" / "dad".
        assertEquals("Папа", call("отцу"))
        assertEquals("Папа", call("dad"))
        assertEquals("Мама", call("матери"))
    }

    @Test
    fun `one word of a full name is enough`() {
        assertEquals("Иван Петров", call("ивану"))
        assertEquals("Иван Петров", call("петрову"))
        assertEquals("Ravshan Aliyev", call("ravshan"))
    }

    @Test
    fun `case insensitive`() {
        assertEquals("Папа", call("ПАПЕ"))
        assertEquals("Ravshan Aliyev", call("RAVSHAN"))
    }

    @Test
    fun `punctuation is ignored`() {
        assertEquals("Папа", call("папе."))
        assertEquals("Мама", call("маме!"))
    }

    @Test
    fun `unknown names return nothing rather than a wrong guess`() {
        // Calling the wrong person is worse than saying "not found".
        assertNull(call("евгению"))
        assertNull(call("хирург"))
        assertNull(call(""))
    }

    @Test
    fun `empty phone book returns nothing`() {
        assertNull(ContactMatcher.findBest("папе", emptyList()))
    }

    @Test
    fun `exact full name beats a partial match`() {
        val book = listOf(
            Contact("Иван", "111"),
            Contact("Иван Петров", "222")
        )
        assertEquals("Иван", ContactMatcher.findBest("иван", book)?.name)
    }

    @Test
    fun `stemming collapses russian cases`() {
        assertEquals(ContactMatcher.stem("папа"), ContactMatcher.stem("папе"))
        assertEquals(ContactMatcher.stem("марина"), ContactMatcher.stem("марине"))
        assertEquals(ContactMatcher.stem("иван"), ContactMatcher.stem("ивану"))
    }

    @Test
    fun `stemming does not destroy short names`() {
        // Guard against over-stripping turning everything into one letter.
        listOf("лев", "ян", "ева").forEach {
            assert(ContactMatcher.stem(it).length >= 2) { "'$it' was over-stemmed" }
        }
    }

    // --- script mismatch -----------------------------------------------
    // The real phone book is mostly Latin ("Kashif", "Father"), while speech
    // recognised in Russian comes back Cyrillic ("Кашифу"). Without
    // transliteration the two never meet and every call fails.

    private val latinBook = listOf(
        Contact("Father", "+998901112233"),
        Contact("Mom", "+998901112244"),
        Contact("Kashif", "+998901112255"),
        Contact("Khasan Aka", "+998901112266"),
        Contact("Timur Rakhimov", "+998901112277"),
        Contact("Zhanna", "+998901112288")
    )

    private fun callLatin(query: String) =
        ContactMatcher.findBest(query, latinBook)?.name

    @Test
    fun `russian speech finds a latin contact`() {
        assertEquals("Kashif", callLatin("кашиф"))
        assertEquals("Kashif", callLatin("кашифу"))
        assertEquals("Timur Rakhimov", callLatin("тимур"))
    }

    @Test
    fun `transliteration variants of the same sound match`() {
        // "х" is written both "kh" and "h"; "ж" both "zh" and "j".
        assertEquals("Khasan Aka", callLatin("хасан"))
        assertEquals("Zhanna", callLatin("жанна"))
    }

    @Test
    fun `russian relationship words find english contacts`() {
        // The actual phone book has "Father" and "Mom" spelled in English.
        assertEquals("Father", callLatin("папе"))
        assertEquals("Father", callLatin("отцу"))
        assertEquals("Mom", callLatin("маме"))
    }

    @Test
    fun `latin queries still work on a latin book`() {
        assertEquals("Father", callLatin("father"))
        assertEquals("Kashif", callLatin("Kashif"))
    }

    @Test
    fun `canonical form is script independent`() {
        assertEquals(ContactMatcher.canonical("Кашиф"), ContactMatcher.canonical("Kashif"))
        assertEquals(ContactMatcher.canonical("Хасан"), ContactMatcher.canonical("Khasan"))
        assertEquals(ContactMatcher.canonical("Жанна"), ContactMatcher.canonical("Zhanna"))
    }

    @Test
    fun `unknown names still return nothing across scripts`() {
        assertNull(callLatin("евгению"))
        assertNull(callLatin("randomperson"))
    }
}
