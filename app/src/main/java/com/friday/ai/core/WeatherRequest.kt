package com.friday.ai.core

import com.friday.ai.domain.model.CommandResult

/**
 * Weather questions the fixed patterns missed, and the owner naming their city.
 *
 * "Будет ли дождь?", "во сколько ждать дождь?" and "какая погода тогда
 * получается в Геттисберге?" all went to the model, which answered from a
 * web page in inches. They are weather questions: they get the real
 * forecast. "Мой город — Геттисберг" was heard and forgotten; now it is
 * kept, and a weather question in the same breath is answered for it.
 */
object WeatherRequest {

    private val I = RegexOption.IGNORE_CASE

    /**
     * Case-sensitive on purpose: the city is the capitalised word, as speech
     * recognition writes names — "мой город красивый" names no city.
     */
    private val homeCity = Regex(
        """(?:[Мм]ой\s+(?:родной\s+|домашний\s+)?город(?:\s*(?:—|-|:|это))?|[Яя]\s+живу\s+(?:в|во))\s+""" +
            """([\p{Lu}][\p{L}\-]*(?:\s+[\p{Lu}][\p{L}\-]*)?)"""
    )
    private val englishHomeCity =
        Regex("""(?:my\s+(?:home\s+)?city\s+is|i\s+live\s+in)\s+([\p{L}][\p{L}\- ]{1,40}?)(?=[.,!?]|$)""", I)

    /** Words only a weather question uses. */
    private val weatherWords = Regex(
        """(?:погод|дожд|осадк|снег|снегопад|гроз|зонт|ветр|ветер|weather|rain|snow|umbrella|wind)""",
        I
    )

    /** A question: a question mark, or a question word up front. */
    private val asks = Regex(
        """\?\s*$|^(?:а\s+|но\s+|и\s+|хорошо,?\s+(?:а|но)?\s*)?(?:какая|какой|какие|как|что|сколько|во\s+сколько|""" +
            """когда|будет|будут|пойд[её]т|ожидается|нужен|брать|взять|what|how|when|will|is\s+it|do\s+i)(?!\p{L})""",
        I
    )

    /** "в Москве", "in Paris": a capitalised place after the preposition. */
    private val place = Regex("""(?:^|\s)(?:в|во|in)\s+([\p{Lu}][\p{L}\-]+(?:\s+[\p{Lu}][\p{L}\-]+)?)""")

    fun parse(text: String, dayOffset: Int): CommandResult? {
        val t = text.trim()
        val city = (homeCity.find(t) ?: englishHomeCity.find(t))?.groupValues?.get(1)?.trim()
        val weather = CommandResult.Weather(placeIn(t, except = city), dayOffset)
            .takeIf { weatherWords.containsMatchIn(t) && asks.containsMatchIn(t) }
        return when {
            city != null && weather != null ->
                // The city just named is the one asked about.
                CommandResult.Sequence(listOf(CommandResult.SetHomeCity(city), weather.copy(place = null)))
            city != null -> CommandResult.SetHomeCity(city)
            else -> weather
        }
    }

    private fun placeIn(text: String, except: String?): String? =
        place.findAll(text).map { it.groupValues[1] }.firstOrNull { it != except }
}
