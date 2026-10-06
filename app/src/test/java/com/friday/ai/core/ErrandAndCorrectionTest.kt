package com.friday.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrandPlacesTest {

    private fun kinds(errand: String) =
        ErrandPlaces.placeTypesFor(errand).map { "${it.key}=${it.value}" }.toSet()

    @Test
    fun `groceries map to shops`() {
        assertTrue(kinds("купить молоко").contains("shop=supermarket"))
        assertTrue(kinds("купить хлеб").contains("shop=supermarket"))
        assertTrue(kinds("buy milk").contains("shop=supermarket"))
    }

    @Test
    fun `russian cases do not break the mapping`() {
        // Matching on stems, so "молока"/"молоку" work like "молоко".
        assertTrue(kinds("купить молока").isNotEmpty())
        assertTrue(kinds("не забыть про лекарства").contains("amenity=pharmacy"))
    }

    @Test
    fun `specific errands map to specific places`() {
        assertTrue(kinds("зайти в аптеку").contains("amenity=pharmacy"))
        assertTrue(kinds("снять наличные").contains("amenity=atm"))
        assertTrue(kinds("забрать посылку").contains("amenity=post_office"))
        assertTrue(kinds("заправиться").contains("amenity=fuel"))
    }

    @Test
    fun `a vague purchase still assumes a shop`() {
        assertTrue("'купить' alone should still map somewhere", kinds("купить что-нибудь").isNotEmpty())
    }

    @Test
    fun `non-errands are not turned into location reminders`() {
        // Nagging in a random shop about these would be worse than silence.
        assertFalse(ErrandPlaces.isLocationWorthy("позвонить маме"))
        assertFalse(ErrandPlaces.isLocationWorthy("написать отчёт"))
        assertFalse(ErrandPlaces.isLocationWorthy("сделать зарядку"))
    }

    @Test
    fun `prompt matches the wording asked for`() {
        val s = ErrandPlaces.nearbyPrompt("молоко", nearestMeters = 120, placeCount = 3, russian = true)
        assertTrue("got: $s", s.startsWith("Вы говорили купить молоко."))
        assertTrue("got: $s", s.contains("рядом"))
        assertTrue("got: $s", s.contains("120 метрах"))
    }

    @Test
    fun `prompt reads correctly for a single place`() {
        val s = ErrandPlaces.nearbyPrompt("молоко", 90, placeCount = 1, russian = true)
        assertTrue("got: $s", s.contains("подходящее место"))
        assertFalse("got: $s", s.contains("места,"))
    }

    @Test
    fun `distance is roughly right`() {
        // Two points ~111m apart in latitude.
        val d = ErrandPlaces.distanceMeters(41.3100, 69.2400, 41.3110, 69.2400)
        assertTrue("got ${d}m", d in 100..120)
        assertEquals(0, ErrandPlaces.distanceMeters(41.31, 69.24, 41.31, 69.24))
    }

    @Test
    fun `overpass clause is well formed`() {
        val clause = ErrandPlaces.PlaceType("shop", "supermarket")
            .overpassClause(500, 41.31, 69.24)
        assertTrue(clause.contains("""["shop"="supermarket"]"""))
        assertTrue(clause.contains("around:500,41.31,69.24"))
    }
}

class CorrectionDetectorTest {

    private fun detect(text: String) = CorrectionDetector.detect(text, assistantSaidSomething = true)

    @Test
    fun `explicit corrections are caught`() {
        assertNotNull(detect("нет, меня зовут Тимур"))
        assertNotNull(detect("я же говорил, я не пью кофе"))
        assertNotNull(detect("это неправильно, я живу в Ташкенте"))
        assertNotNull(detect("no, my name is Timur"))
        assertNotNull(detect("actually I prefer tea"))
    }

    @Test
    fun `the corrected fact is isolated from the objection`() {
        assertEquals("меня зовут Тимур", detect("нет, меня зовут Тимур")?.correctedFact)
        assertEquals("я живу в Ташкенте", detect("неправильно, я живу в Ташкенте")?.correctedFact)
    }

    @Test
    fun `a bare refusal is not a correction`() {
        // "нет" here means "don't do that", not "you got a fact wrong".
        assertNull(detect("нет"))
        assertNull(detect("не надо"))
        assertNull(detect("no thanks"))
        assertNull(detect("не нужно"))
    }

    @Test
    fun `ordinary conversation is not a correction`() {
        assertNull(detect("расскажи про погоду"))
        assertNull(detect("открой камеру"))
        assertNull(detect("спасибо, это помогло"))
    }

    @Test
    fun `nothing is a correction if the assistant had not spoken`() {
        assertNull(CorrectionDetector.detect("нет, меня зовут Тимур", assistantSaidSomething = false))
    }

    @Test
    fun `very short utterances are not corrections`() {
        // Too little to safely rewrite memory with.
        assertNull(detect("нет!"))
        assertNull(detect("не так"))
    }

    @Test
    fun `stored form marks the correction as authoritative`() {
        val c = detect("нет, меня зовут Тимур")!!
        val note = CorrectionDetector.asMemoryNote(c)
        assertTrue("got: $note", note.contains("CORRECTION"))
        assertTrue("got: $note", note.contains("Тимур"))
    }
}
