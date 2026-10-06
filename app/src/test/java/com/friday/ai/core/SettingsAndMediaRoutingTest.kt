package com.friday.ai.core

import com.friday.ai.domain.model.CameraMode
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.DeviceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAndMediaRoutingTest {

    private val router = CommandRouter()

    // ---------- camera ----------

    @Test
    fun `opening the camera is not treated as opening an app`() {
        // The generic "открой <app>" handler used to swallow this and lose the
        // mode entirely.
        val r = router.route("открой камеру")
        assertTrue("got $r", r is CommandResult.OpenCamera)
        assertEquals(CameraMode.JUST_OPEN, (r as CommandResult.OpenCamera).mode)
    }

    @Test
    fun `asking for a photo opens the camera ready to shoot`() {
        listOf("открой камеру и сделай фото", "сфотографируй", "сделай снимок").forEach {
            val r = router.route(it)
            assertTrue("'$it' -> $r", r is CommandResult.OpenCamera)
            assertEquals("'$it'", CameraMode.PHOTO, (r as CommandResult.OpenCamera).mode)
        }
    }

    @Test
    fun `video wins over photo when both words appear`() {
        val r = router.route("открой камеру и запиши видео") as CommandResult.OpenCamera
        assertEquals(CameraMode.VIDEO, r.mode)
    }

    @Test
    fun `a selfie opens the front camera`() {
        assertEquals(
            CameraMode.SELFIE,
            (router.route("сделай селфи") as CommandResult.OpenCamera).mode
        )
    }

    @Test
    fun `the recorder is not confused with video`() {
        assertTrue(router.route("включи диктофон") is CommandResult.RecordAudio)
        assertTrue(router.route("запиши аудио") is CommandResult.RecordAudio)
        assertTrue(router.route("запиши голос") is CommandResult.RecordAudio)
    }

    // ---------- settings ----------

    @Test
    fun `settings screens are recognised by name`() {
        mapOf(
            "открой nfc" to SettingsScreens.Screen.NFC,
            "включи нфс" to SettingsScreens.Screen.NFC,
            "открой настройки экрана" to SettingsScreens.Screen.DISPLAY,
            "покажи батарею" to SettingsScreens.Screen.BATTERY,
            "открой геолокацию" to SettingsScreens.Screen.LOCATION,
            "открой настройки" to SettingsScreens.Screen.ROOT
        ).forEach { (phrase, expected) ->
            val r = router.route(phrase)
            assertTrue("'$phrase' -> $r", r is CommandResult.OpenSettings)
            assertEquals(
                "'$phrase'", expected,
                SettingsScreens.match((r as CommandResult.OpenSettings).phrase)
            )
        }
    }

    @Test
    fun `volume and wifi keep their existing handlers`() {
        // routeDeviceControl runs first on purpose: those do something real,
        // and demoting them to "here's the settings screen" would be a
        // regression.
        assertTrue(router.route("включи звук") is CommandResult.DeviceControl)
        assertEquals(
            DeviceAction.OPEN_WIFI_PANEL,
            (router.route("включи вайфай") as CommandResult.DeviceControl).action
        )
    }

    @Test
    fun `the flashlight is still the flashlight, not a settings screen`() {
        assertEquals(CommandResult.Flashlight(on = true), router.route("включи фонарик"))
    }

    @Test
    fun `opening an ordinary app is not hijacked by the settings matcher`() {
        // "приложение" as a bare keyword used to send "открой приложение
        // телеграм" to the app-settings screen.
        listOf("открой телеграм", "открой приложение телеграм", "открой инстаграм").forEach {
            val r = router.route(it)
            assertTrue("'$it' -> $r", r !is CommandResult.OpenSettings)
        }
    }

    @Test
    fun `a verb with no screen named is not a settings command`() {
        assertNull(SettingsScreens.match("включи музыку"))
        assertTrue(router.route("включи музыку") !is CommandResult.OpenSettings)
    }

    @Test
    fun `asking the time is a question, not a trip to the settings`() {
        assertTrue(router.route("покажи время") !is CommandResult.OpenSettings)
    }

    // ---------- matcher ----------

    @Test
    fun `the longest keyword wins`() {
        assertEquals(
            SettingsScreens.Screen.MOBILE_DATA,
            SettingsScreens.match("открой мобильные данные")
        )
    }

    @Test
    fun `matching is on whole words`() {
        // Substring matching would fire on anything containing these letters,
        // and `\b` is ASCII-only so it cannot guard Cyrillic.
        assertNull(SettingsScreens.match("посмотри расписание"))
        assertNull(SettingsScreens.match(""))
    }

    @Test
    fun `yo and case do not matter`() {
        assertEquals(SettingsScreens.Screen.AIRPLANE, SettingsScreens.match("Режим Полёта"))
        assertEquals(SettingsScreens.Screen.AIRPLANE, SettingsScreens.match("режим полета"))
    }

    // ---------- offline ----------

    @Test
    fun `launching things works without a network`() {
        assertTrue(OfflineCommands.isOfflineCapable(CommandResult.OpenCamera(CameraMode.PHOTO)))
        assertTrue(OfflineCommands.isOfflineCapable(CommandResult.RecordAudio))
        assertTrue(OfflineCommands.isOfflineCapable(CommandResult.OpenSettings("нфс")))
    }

    @Test
    fun `every offline phrase still routes to something the phone can do`() {
        // Guards the failure found before: phrases in the grammar that are
        // recognised but route nowhere, so the user hears silence.
        OfflineCommands.GRAMMAR
            .filter { it !in setOf("стоп", "хватит", "пока", "отбой") }
            .forEach { phrase ->
                val command = router.route(phrase)
                assertTrue(
                    "'$phrase' routed to $command, which needs a network",
                    OfflineCommands.isOfflineCapable(command)
                )
            }
    }
}
