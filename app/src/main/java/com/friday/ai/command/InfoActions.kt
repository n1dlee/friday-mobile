package com.friday.ai.command

import com.friday.ai.service.WeatherHere
import com.friday.ai.service.WebResearch
import com.friday.ai.service.messages.MessageAssistant
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.service.ProactiveBriefService
import com.friday.ai.service.mail.MailAssistant

/** Commands that look something up and tell the user. */
class InfoActions(
    private val weather: WeatherHere,
    private val proactive: ProactiveBriefService,
    private val mail: MailAssistant,
    private val web: WebResearch,
    private val messages: MessageAssistant
) {

    suspend fun run(c: CommandResult.Info, russian: Boolean): String = when (c) {
        is CommandResult.Weather -> weather(c, russian)
        is CommandResult.WhatDidIMiss -> proactive.whatDidIMiss(russian)
        is CommandResult.TellTime -> tellTime(russian)
        is CommandResult.LookUp -> web.answer(c.question, russian)
        is CommandResult.ReadMessages -> messages.read(c.from, russian)
        is CommandResult.MorningBrief -> proactive.morningBrief(russian)
        is CommandResult.Mail -> mail.handle(c.request, russian).text
    }

    private fun tellTime(russian: Boolean): String {
        val t = java.time.LocalTime.now()
        val hhmm = "%02d:%02d".format(t.hour, t.minute)
        return if (russian) "Сейчас $hhmm" else "It's $hhmm"
    }

    /**
     * Real weather from Open-Meteo. The model has no live data and will
     * happily invent a temperature if asked directly, so this never goes
     * through the LLM.
     */
    private suspend fun weather(c: CommandResult.Weather, russianSpeaker: Boolean): String {
        // A Cyrillic place name means a Russian answer whatever else is set.
        val russian = russianSpeaker || c.place.orEmpty().any { it in 'а'..'я' || it in 'А'..'Я' }
        val where = c.place ?: if (russian) "вашего места" else "where you are"
        return weather.summary(c.place, c.dayOffset, russian)
            ?: if (russian) "Не смогла получить погоду для «$where»" else "Couldn't get the weather for $where"
    }
}
