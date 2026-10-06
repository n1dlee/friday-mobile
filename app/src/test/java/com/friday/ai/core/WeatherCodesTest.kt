package com.friday.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherCodesTest {

    @Test
    fun `known codes describe the conditions`() {
        assertEquals("ясно", WeatherCodes.describe(0, russian = true))
        assertEquals("clear", WeatherCodes.describe(0, russian = false))
        assertEquals("дождь", WeatherCodes.describe(63, russian = true))
        assertEquals("thunderstorm", WeatherCodes.describe(95, russian = false))
    }

    @Test
    fun `unknown codes fall back instead of crashing`() {
        assertTrue(WeatherCodes.describe(-1, russian = true).isNotBlank())
        assertTrue(WeatherCodes.describe(1234, russian = false).isNotBlank())
    }

    @Test
    fun `summary is a single short sentence`() {
        val s = WeatherCodes.summarize("Ташкенте", 38.0, 39.7, 0, russian = true)
        assertTrue("got: $s", s.contains("38"))
        assertTrue("got: $s", s.contains("ясно"))
        assertEquals("should be one sentence", 1, s.count { it == '.' })
    }

    @Test
    fun `feels-like is mentioned only when it differs noticeably`() {
        val close = WeatherCodes.summarize("Москве", 20.0, 21.0, 3, russian = true)
        assertTrue("shouldn't mention feels-like: $close", !close.contains("ощущается"))

        val far = WeatherCodes.summarize("Москве", 20.0, 27.0, 3, russian = true)
        assertTrue("should mention feels-like: $far", far.contains("ощущается"))
    }

    @Test
    fun `temperatures are rounded, not shown with decimals`() {
        val s = WeatherCodes.summarize("London", 17.6, 17.6, 61, russian = false)
        assertTrue("got: $s", s.contains("18"))
        assertTrue("no decimals expected: $s", !s.contains("17.6"))
    }

    @Test
    fun `english summary reads naturally`() {
        val s = WeatherCodes.summarize("London", 12.0, 9.0, 61, russian = false)
        assertTrue("got: $s", s.startsWith("It's 12°"))
        assertTrue("got: $s", s.contains("light rain"))
    }
}
