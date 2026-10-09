package com.friday.ai.core.links

import com.friday.ai.command.CommandExecutor
import com.friday.ai.core.CommandRouter
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.service.links.LinkOpener
import com.friday.ai.service.links.QuickLinkStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val films = QuickLink("1", "Фильмы", listOf("давай посмотрим фильм", "включи кино"), "https://films.example")
private val cartoons = QuickLink("2", "Мультики", listOf("давай посмотрим фильм для детей"), "https://kids.example")
private val quiet = QuickLink("3", "Тихий", listOf("открой тихий сайт"), "https://quiet.example", silent = true)

class QuickLinksTest {

    private val links = listOf(films, cartoons, quiet)

    @Test
    fun `the phrase said alone or inside a sentence opens the link`() {
        assertEquals(films, QuickLinks.match("Давай посмотрим фильм!", links))
        assertEquals(films, QuickLinks.match("Пятница, ну давай посмотрим фильм, а?", links))
        assertEquals(films, QuickLinks.match("включи кино", links))
    }

    @Test
    fun `said the way people say it - other forms, other lead-ins, a word in between`() {
        listOf(
            "Я хочу посмотреть фильмы.", "Пятница, посмотрим какой-нибудь фильм", "Ну давай посмотрим фильмы",
            "хочу посмотреть фильм, пожалуйста"
        ).forEach { assertEquals(it, films, QuickLinks.match(it, links)) }
    }

    @Test
    fun `order matters, and a one-word phrase needs a short request`() {
        val kino = QuickLink("4", "Кино", listOf("кино"), "https://kino.example")
        assertEquals(kino, QuickLinks.match("кино", listOf(kino)))
        assertEquals(kino, QuickLinks.match("Пятница, включи кино", listOf(kino)))
        assertNull(QuickLinks.match("расскажи что-нибудь интересное про кино восьмидесятых", listOf(kino)))
        assertNull(QuickLinks.match("фильм хочу посмотреть потом", listOf(films)))
    }

    @Test
    fun `stems`() {
        assertEquals("посмотр", PhraseMatch.stem("посмотреть"))
        assertEquals("посмотр", PhraseMatch.stem("посмотрим"))
        assertEquals("фильм", PhraseMatch.stem("фильмы"))
        assertEquals("кин", PhraseMatch.stem("кино"))
        assertEquals("movie", PhraseMatch.stem("movies"))
    }

    @Test
    fun `the longest phrase wins`() {
        assertEquals(cartoons, QuickLinks.match("давай посмотрим фильм для детей", links))
    }

    @Test
    fun `part of a word or a different phrase does not`() {
        assertNull(QuickLinks.match("включи кинотеатр", links))
        assertNull(QuickLinks.match("какой фильм посмотреть", links))
        assertNull(QuickLinks.match("", links))
    }

    @Test
    fun `phrases and addresses are taken as typed`() {
        assertEquals(
            listOf("давай посмотрим фильм", "включи кино"),
            QuickLinks.phrasesOf("давай посмотрим фильм, включи кино,")
        )
        assertEquals("https://example.com", QuickLinks.webAddress(" example.com "))
        assertEquals("http://example.com/x?y=1", QuickLinks.webAddress("http://example.com/x?y=1"))
        assertNull(QuickLinks.webAddress("не сайт"))
        assertNull(QuickLinks.webAddress("localhost"))
        assertNull(QuickLinks.webAddress("javascript:alert(1)"))
        assertNull(QuickLinks.webAddress("file:///sdcard/x.html"))
    }

    @Test
    fun `stored and read back`() {
        assertEquals(links, QuickLinks.decode(QuickLinks.encode(links)))
        assertEquals(emptyList<QuickLink>(), QuickLinks.decode("не json"))
        assertEquals(emptyList<QuickLink>(), QuickLinks.decode(null))
    }
}

class QuickLinkCommandTest {

    private val store = mockk<QuickLinkStore>()
    private val opener = mockk<LinkOpener>()
    private val router = mockk<CommandRouter>(relaxed = true)
    private val executor = CommandExecutor(
        router, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        UnconfinedTestDispatcher(), links = store, opener = opener
    )

    @Test
    fun `the owner's phrase comes before every other reading`() {
        every { store.match("давай посмотрим фильм") } returns films
        val routed = executor.route("давай посмотрим фильм")
        assertEquals(CommandResult.OpenLink("Фильмы", "https://films.example", false), routed)
        verify(exactly = 0) { router.route(any()) }
    }

    @Test
    fun `a silent link is opened without a word`() = runTest {
        every { opener.open(any()) } returns true
        val outcome = executor.execute(CommandResult.OpenLink("Т", quiet.url, true), true)
        assertEquals(CommandExecutor.Outcome.Silent, outcome)
        verify { opener.open("https://quiet.example") }
    }

    @Test
    fun `an ordinary one says what it opened, and a missing browser is said too`() = runTest {
        every { opener.open(any()) } returns true
        assertEquals(
            CommandExecutor.Outcome.Reply("Открываю «Фильмы»."),
            executor.execute(CommandResult.OpenLink("Фильмы", films.url, false), true)
        )
        every { opener.open(any()) } returns false
        assertEquals(
            CommandExecutor.Outcome.Reply("Не нашла браузер, чтобы открыть ссылку."),
            executor.execute(CommandResult.OpenLink("Фильмы", films.url, false), true)
        )
    }
}
