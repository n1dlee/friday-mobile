package com.friday.ai.core

import com.friday.ai.domain.model.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommandRouterTest {

    private lateinit var router: CommandRouter

    @Before
    fun setUp() {
        router = CommandRouter()
    }

    @Test
    fun `route open camera in Russian`() {
        // The camera left the generic app launcher: routing it separately is
        // what lets "открой камеру и сделай фото" open in capture mode instead
        // of losing the mode along with the rest of the sentence.
        val result = router.route("открой камеру")
        assertTrue("got $result", result is CommandResult.OpenCamera)
        assertEquals(
            com.friday.ai.domain.model.CameraMode.JUST_OPEN,
            (result as CommandResult.OpenCamera).mode
        )
    }

    @Test
    fun `route open youtube in Russian`() {
        val result = router.route("запусти ютуб")
        assertTrue(result is CommandResult.OpenApp)
        result as CommandResult.OpenApp
        assertEquals("ютуб", result.appName)
        assertEquals("com.google.android.youtube", result.packageHint)
    }

    @Test
    fun `route open app in English`() {
        val result = router.route("open chrome")
        assertTrue(result is CommandResult.OpenApp)
        result as CommandResult.OpenApp
        assertEquals("chrome", result.appName)
        assertEquals("com.android.chrome", result.packageHint)
    }

    @Test
    fun `route close app in Russian`() {
        val result = router.route("закрой телеграм")
        assertTrue(result is CommandResult.CloseApp)
        result as CommandResult.CloseApp
        assertEquals("телеграм", result.appName)
        assertEquals("org.telegram.messenger", result.packageHint)
    }

    @Test
    fun `route close app in English`() {
        val result = router.route("close youtube")
        assertTrue(result is CommandResult.CloseApp)
        result as CommandResult.CloseApp
        assertEquals("youtube", result.appName)
    }

    @Test
    fun `route unknown app has null package hint`() {
        val result = router.route("открой блокнот")
        assertTrue(result is CommandResult.OpenApp)
        result as CommandResult.OpenApp
        assertEquals("блокнот", result.appName)
        assertNull(result.packageHint)
    }

    @Test
    fun `route analyze screen in Russian`() {
        val result = router.route("проанализируй экран")
        assertTrue(result is CommandResult.AnalyzeScreen)
    }

    @Test
    fun `route analyze screen in English`() {
        val result = router.route("analyze screen")
        assertTrue(result is CommandResult.AnalyzeScreen)
    }

    @Test
    fun `route what's on screen`() {
        val result = router.route("что на экране")
        assertTrue(result is CommandResult.AnalyzeScreen)
    }

    @Test
    fun `route analyze file in Russian`() {
        val result = router.route("проанализируй файл")
        assertTrue(result is CommandResult.AnalyzeFile)
    }

    @Test
    fun `route analyze file with hint`() {
        val result = router.route("проанализируй файл report.pdf")
        assertTrue(result is CommandResult.AnalyzeFile)
        result as CommandResult.AnalyzeFile
        assertEquals("report.pdf", result.fileHint)
    }

    @Test
    fun `route regular message`() {
        val result = router.route("Привет, как дела?")
        assertTrue(result is CommandResult.ChatMessage)
        result as CommandResult.ChatMessage
        assertEquals("Привет, как дела?", result.text)
    }

    @Test
    fun `route is case insensitive`() {
        // Uses an ordinary app: the camera now has its own route, and this
        // test is about case handling in the app launcher.
        val result = router.route("ОТКРОЙ ТЕЛЕГРАМ")
        assertTrue("got $result", result is CommandResult.OpenApp)
    }

    @Test
    fun `route trims whitespace`() {
        val result = router.route("  открой телеграм  ")
        assertTrue("got $result", result is CommandResult.OpenApp)
    }

    @Test
    fun `route phone call in Russian`() {
        val result = router.route("позвони маме")
        assertTrue(result is CommandResult.PhoneCall)
        result as CommandResult.PhoneCall
        assertEquals("маме", result.target)
    }

    @Test
    fun `route phone call in English`() {
        val result = router.route("call +79991234567")
        assertTrue(result is CommandResult.PhoneCall)
        result as CommandResult.PhoneCall
        assertEquals("+79991234567", result.target)
    }

    @Test
    fun `route sms in Russian`() {
        val result = router.route("отправь сообщение маме")
        assertTrue(result is CommandResult.SendMessage)
        result as CommandResult.SendMessage
        assertEquals("маме", result.target)
    }

    @Test
    fun `route alarm in Russian`() {
        val result = router.route("поставь будильник на 7:30")
        assertTrue(result is CommandResult.SetAlarm)
        result as CommandResult.SetAlarm
        assertEquals(7, result.hour)
        assertEquals(30, result.minute)
    }

    @Test
    fun `route alarm in English`() {
        val result = router.route("set alarm at 8:00")
        assertTrue(result is CommandResult.SetAlarm)
        result as CommandResult.SetAlarm
        assertEquals(8, result.hour)
        assertEquals(0, result.minute)
    }

    @Test
    fun `route timer in minutes`() {
        val result = router.route("поставь таймер на 5 минут")
        assertTrue(result is CommandResult.SetTimer)
        result as CommandResult.SetTimer
        assertEquals(300, result.seconds)
    }

    @Test
    fun `route timer in seconds`() {
        val result = router.route("set timer for 30 seconds")
        assertTrue(result is CommandResult.SetTimer)
        result as CommandResult.SetTimer
        assertEquals(30, result.seconds)
    }

    @Test
    fun `route flashlight in Russian`() {
        assertEquals(CommandResult.Flashlight(on = true), router.route("включи фонарик"))
        assertEquals(CommandResult.Flashlight(on = false), router.route("выключи фонарик"))
    }

    @Test
    fun `route flashlight standalone`() {
        val result = router.route("фонарик")
        assertTrue(result is CommandResult.ToggleFlashlight)
    }

    @Test
    fun `route web search in Russian`() {
        val result = router.route("загугли погода москва")
        // Answered aloud from the web, not left in a browser tab.
        assertEquals(CommandResult.LookUp("погода москва"), result)
    }

    @Test
    fun `route web search in English`() {
        val result = router.route("google weather today")
        assertEquals(CommandResult.LookUp("weather today"), result)
    }
}
