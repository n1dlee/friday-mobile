package com.friday.ai.core

import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.DeviceAction
import com.friday.ai.domain.model.MediaAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** Everyday phrases that used to go to the model instead of being carried out. */
class RouterGapsTest {

    private val router = CommandRouter()

    private fun check(expected: CommandResult, vararg phrases: String) =
        phrases.forEach { assertEquals(it, expected, router.route(it)) }

    @Test
    fun `stopping and pausing music`() {
        check(
            CommandResult.MediaControl(MediaAction.STOP, null),
            "Останови музыку", "Стоп музыка", "убери музыку", "выключи музыку"
        )
        check(CommandResult.MediaControl(MediaAction.PAUSE, null), "пауза", "поставь на паузу", "Сделай паузу", "pause")
    }

    @Test
    fun `asking what is playing`() {
        check(CommandResult.NowPlaying, "что сейчас играет", "какая песня играет", "what's playing")
    }

    @Test
    fun `volume by number, to the top, to the bottom, or off`() {
        check(CommandResult.DeviceControl(DeviceAction.VOLUME_SET, 50), "звук на 50", "громкость на 50%")
        check(CommandResult.DeviceControl(DeviceAction.VOLUME_SET, 100), "громкость на максимум", "звук на полную")
        check(CommandResult.DeviceControl(DeviceAction.VOLUME_SET, 10), "громкость на минимум")
        check(CommandResult.DeviceControl(DeviceAction.MUTE), "без звука", "выключи звук")
    }

    @Test
    fun `timers and the time`() {
        check(CommandResult.SetTimer(180, null), "засеки 3 минуты", "set a timer for 3 minutes")
        check(CommandResult.TellTime, "который час", "сколько время", "сколько сейчас времени", "what time is it")
    }

    @Test
    fun `an english alarm "for" a time`() {
        check(CommandResult.SetAlarm(7, 0, null), "set an alarm for 7")
    }

    @Test
    fun `talking about time or music is still conversation`() {
        val q = "сколько времени займёт перелёт?"
        assertEquals(CommandResult.ChatMessage(q), router.route(q))
    }
}
