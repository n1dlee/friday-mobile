package com.friday.ai.command

import com.friday.ai.core.AppLauncher
import com.friday.ai.core.CommandRouter
import com.friday.ai.core.DateTimeParser
import com.friday.ai.core.mail.MailCommands
import com.friday.ai.data.local.dao.ErrandDao
import com.friday.ai.service.WeatherHere
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.service.FridayMemory
import com.friday.ai.service.ProactiveBriefService
import com.friday.ai.service.mail.MailAssistant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandExecutorTest {

    private val dispatcher = StandardTestDispatcher()
    private val phone = mockk<PhoneActions>(relaxed = true)
    private val planner = mockk<PlannerActions>(relaxed = true)
    private val info = mockk<InfoActions>(relaxed = true)
    private val mail = mockk<MailAssistant>(relaxed = true)
    private val executor = CommandExecutor(mockk<CommandRouter>(relaxed = true), phone, planner, info, mail, dispatcher)

    @Test
    fun `each group goes to its own actions`() = runTest(dispatcher) {
        coEvery { phone.run(any(), any()) } returns "phone"
        coEvery { planner.run(any(), any()) } returns "planner"
        coEvery { info.run(any(), any()) } returns "info"

        assertEquals(CommandExecutor.Outcome.Reply("phone"), executor.execute(CommandResult.ToggleFlashlight, true))
        assertEquals(CommandExecutor.Outcome.Reply("planner"), executor.execute(CommandResult.CreateNote("x"), true))
        assertEquals(CommandExecutor.Outcome.Reply("info"), executor.execute(CommandResult.MorningBrief, false))
        coVerify { info.run(CommandResult.MorningBrief, false) }
    }

    @Test
    fun `what only the caller can do is handed back`() = runTest(dispatcher) {
        assertEquals(
            CommandExecutor.Outcome.Conversation("привет"),
            executor.execute(CommandResult.ChatMessage("привет"), true)
        )
        assertEquals(CommandExecutor.Outcome.NeedsScreen, executor.execute(CommandResult.AnalyzeScreen, true))
        assertEquals(
            CommandExecutor.Outcome.NeedsFile("a.pdf"),
            executor.execute(CommandResult.AnalyzeFile("a.pdf"), true)
        )
    }

    @Test
    fun `a failure becomes something to say, in the user's language`() = runTest(dispatcher) {
        coEvery { phone.run(any(), any()) } throws IllegalStateException("занято")
        val ru = executor.execute(CommandResult.ToggleFlashlight, true) as CommandExecutor.Outcome.Reply
        val en = executor.execute(CommandResult.ToggleFlashlight, false) as CommandExecutor.Outcome.Reply
        assertTrue(ru.text, ru.text.startsWith("Не получилось") && ru.text.contains("занято"))
        assertTrue(en.text, en.text.startsWith("Couldn't do that"))
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is not swallowed`() = runTest(dispatcher) {
        // Turning it into a reply would keep a cancelled turn talking.
        coEvery { phone.run(any(), any()) } throws CancellationException("stopped")
        executor.execute(CommandResult.ToggleFlashlight, true)
    }

    @Test
    fun `pending answers are the mail assistant's call`() = runTest(dispatcher) {
        coEvery { mail.answerPending("да", true) } returns "Отправила."
        assertEquals("Отправила.", executor.answerPending("да", true))
    }
}

class PlannerActionsTest {

    private val apps = mockk<AppLauncher>(relaxed = true)
    private val errands = mockk<ErrandDao>(relaxed = true)
    private val memory = mockk<FridayMemory>(relaxed = true)
    private var watched = 0
    private val zone = ZoneId.of("Asia/Tashkent")
    private val planner = PlannerActions(apps, errands, memory, watchErrands = { watched++ }, zone = { zone })

    @Test
    fun `an errand is saved and the place watcher started`() = runTest {
        // In the chat this used to depend on the screen wiring a callback;
        // now saying it and typing it both start the watcher.
        val reply = planner.run(CommandResult.CreateErrand("молоко"), russian = true)
        coVerify { errands.insert(match { it.what == "молоко" }) }
        assertEquals(1, watched)
        assertEquals("Напомню, когда будете рядом", reply)
    }

    @Test
    fun `an event lands at the parsed local time in the phone's zone`() = runTest {
        every { apps.createCalendarEvent(any(), any(), any(), any()) } returns "ok"
        val text = "завтра в 15:00"
        val expected = DateTimeParser.parse(text)!!.instant.atZone(zone).toInstant().toEpochMilli()

        planner.run(CommandResult.CreateEvent("врач", text), russian = true)

        verify { apps.createCalendarEvent("врач", expected, expected + 3_600_000L, any()) }
    }

    @Test
    fun `an unreadable time is said, not guessed`() = runTest {
        val reply = planner.run(CommandResult.CreateEvent("врач", "когда-нибудь"), russian = true)
        assertTrue(reply, reply.contains("couldn't work out"))
        verify(exactly = 0) { apps.createCalendarEvent(any(), any(), any(), any()) }
    }
}

class InfoActionsTest {

    private val weather = mockk<WeatherHere>(relaxed = true)
    private val mail = mockk<MailAssistant>(relaxed = true)
    private val info = InfoActions(weather, mockk<ProactiveBriefService>(relaxed = true), mail, mockk(), mockk())

    @Test
    fun `a cyrillic place gets a russian answer whatever the setting`() = runTest {
        coEvery { weather.summary(any(), any(), any()) } returns null
        val reply = info.run(CommandResult.Weather("Ташкенте"), russian = false)
        coVerify { weather.summary("Ташкенте", 0, true) }
        assertTrue(reply, reply.startsWith("Не смогла получить погоду"))
    }

    @Test
    fun `no place named means where the user is, not a city written into the code`() = runTest {
        coEvery { weather.summary(null, 0, true) } returns "+14°, ясно."
        assertEquals("+14°, ясно.", info.run(CommandResult.Weather(null), russian = true))
    }

    @Test
    fun `tomorrow asks for the forecast`() = runTest {
        info.run(CommandResult.Weather("London", dayOffset = 1), russian = false)
        coVerify { weather.summary("London", 1, false) }
    }

    @Test
    fun `the time zone names a city to fall back on`() {
        assertEquals("New York", WeatherHere.cityOfZone(java.time.ZoneId.of("America/New_York")))
        assertEquals("Tashkent", WeatherHere.cityOfZone(java.time.ZoneId.of("Asia/Tashkent")))
        assertEquals(null, WeatherHere.cityOfZone(java.time.ZoneId.of("UTC")))
    }

    @Test
    fun `mail requests go to the mail assistant`() = runTest {
        coEvery { mail.handle(any(), any()) } returns MailAssistant.Answer("2 письма.")
        assertEquals("2 письма.", info.run(CommandResult.Mail(MailCommands.Request.CheckUnread), russian = true))
    }
}
