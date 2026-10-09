package com.friday.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for "I said bye but Friday kept listening". The old check
 * was an exact match against a fixed set, so anything but a bare stop word
 * fell through and the assistant re-opened its overlay.
 */
class ConversationControlTest {

    @Test
    fun `bare farewells end the conversation`() {
        listOf("bye", "Bye", "BYE", "пока", "Пока", "stop", "стоп", "хватит")
            .forEach { assertTrue("'$it' should end the chat", ConversationControl.isFarewell(it)) }
    }

    @Test
    fun `farewells with punctuation end the conversation`() {
        listOf("Bye.", "Пока!", "bye?", "  stop,  ", "Хватит...")
            .forEach { assertTrue("'$it' should end the chat", ConversationControl.isFarewell(it)) }
    }

    @Test
    fun `repeated and padded farewells end the conversation`() {
        // The exact-match version missed every one of these.
        listOf("bye bye", "ok bye", "окей пока", "пока пока", "alright bye", "ладно пока")
            .forEach { assertTrue("'$it' should end the chat", ConversationControl.isFarewell(it)) }
    }

    @Test
    fun `multi word closing phrases end the conversation`() {
        listOf("that's all", "это всё", "до свидания", "nothing else", "больше ничего")
            .forEach { assertTrue("'$it' should end the chat", ConversationControl.isFarewell(it)) }
    }

    @Test
    fun `a polite word inside a real request does not end the conversation`() {
        // The reason the check is length-limited rather than a plain "contains".
        listOf(
            "thanks, now open youtube for me",
            "спасибо, а теперь открой ютуб пожалуйста",
            "stop the timer and set a new one for ten minutes",
            "останови таймер и поставь новый на десять минут"
        ).forEach { assertFalse("'$it' should NOT end the chat", ConversationControl.isFarewell(it)) }
    }

    @Test
    fun `ordinary requests do not end the conversation`() {
        listOf(
            "какая сегодня погода",
            "what is the weather today",
            "открой камеру",
            "поставь будильник на семь утра"
        ).forEach { assertFalse("'$it' should NOT end the chat", ConversationControl.isFarewell(it)) }
    }

    @Test
    fun `more ways of saying goodbye`() {
        listOf("на этом пока", "Ну, на сегодня всё.", "до связи", "можешь отдыхать", "отключайся", "we're done")
            .forEach { assertTrue("'$it' should end the chat", ConversationControl.isFarewell(it)) }
    }

    @Test
    fun `a goodbye at the end of a longer phrase is found, and what came before is kept`() {
        mapOf(
            "Отлично, работаю в фоне, на этом пока." to "Отлично, работаю в фоне",
            "Открой камеру, ну всё, пока!" to "Открой камеру",
            "Поставь будильник на семь. Спокойной ночи." to "Поставь будильник на семь",
            "Set an alarm for seven, bye" to "Set an alarm for seven"
        ).forEach { (said, request) -> assertEquals(request, ConversationControl.beforeFarewell(said)) }
    }

    @Test
    fun `the word пока as a conjunction and thanks after a request are not goodbye`() {
        assertNull(ConversationControl.beforeFarewell("Включи музыку, пока я готовлю"))
        assertNull(ConversationControl.beforeFarewell("Открой камеру, спасибо"))
        assertNull(ConversationControl.beforeFarewell("пока"))
        assertNull(ConversationControl.beforeFarewell("Какая погода завтра?"))
        assertFalse(ConversationControl.isFarewell("напомни до встречи купить цветы для мамы"))
        assertFalse(ConversationControl.isFarewell("Отлично, работаю в фоне, на этом пока."))
    }

    @Test
    fun `empty or blank input is not a farewell`() {
        assertFalse(ConversationControl.isFarewell(""))
        assertFalse(ConversationControl.isFarewell("   "))
    }

    @Test
    fun `assistant replies can also signal the end`() {
        // Used so that when Friday itself says goodbye, it stops listening
        // instead of re-opening the mic.
        assertTrue(ConversationControl.isFarewell("Пока!"))
        assertTrue(ConversationControl.isFarewell("Goodbye."))
        assertTrue(ConversationControl.isFarewell("До свидания"))
    }

    @Test
    fun `a long assistant answer is never mistaken for a farewell`() {
        assertFalse(
            ConversationControl.isFarewell(
                "Пока в Ташкенте тепло, но к вечеру обещают дождь, так что возьми зонт."
            )
        )
    }
}
