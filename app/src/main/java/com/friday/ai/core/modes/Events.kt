package com.friday.ai.core.modes

/** What can start a mode besides the owner's voice and the clock. */
enum class Trigger(val key: String) {
    BLUETOOTH("bluetooth"), WIFI("wifi"), CHARGER("charger");

    companion object {
        fun of(key: String): Trigger? = entries.firstOrNull { it.key == key }
    }
}

/** "При подключении к Toyota Touch — включать режим вождения." */
data class ModeEvent(
    val id: String,
    val modeId: String,
    val trigger: Trigger,
    /** The Bluetooth device's name or the network's SSID, as the phone reports it; empty for the charger. */
    val value: String,
    val onConnect: Boolean,
    val exit: Boolean
)

/** What the phone can tell about its links right now, for turning "к машине" into a device. */
interface PhoneLinks {
    data class Bluetooth(val permitted: Boolean, val paired: List<String>, val connected: List<String>)

    suspend fun bluetooth(): Bluetooth

    /** The Wi-Fi network the phone is on; null when not on one, or it can't be read (location off). */
    suspend fun wifi(): String?
}

/**
 * Event requests in words: "включай режим вождения, когда подключаюсь к
 * машине", "когда ставлю телефон на зарядку — режим сна", "когда снимаю с
 * зарядки, выключай режим сна", "когда подключаюсь к домашнему вайфаю —
 * режим дома".
 */
object EventPhrases {

    data class Request(
        /** The words after "режим", name first. */
        val rest: String,
        val trigger: Trigger,
        /** What the owner called it: "машине", "колонке", "домашнему". Empty for the charger. */
        val target: String,
        val onConnect: Boolean,
        val exit: Boolean
    )

    private val wake = Regex("^(?:пятница|friday)[,!.\\s]+")
    private val whenWord = Regex("(?:^|\\s)(?:когда|как только|при|when|whenever)\\s")

    private val chargerOn = Regex(
        "(?:ставлю|поставлю|кладу|положу|подключаю|подключу)\\s+(?:телефон\\s+)?(?:на|к)\\s+зарядк|" +
            "(?:подключаю|подключу|втыкаю)\\s+зарядк|подключени\\p{L}* зарядк|" +
            "(?:put|plug)\\s+(?:it\\s+|the phone\\s+)?(?:on|in)"
    )
    private val chargerOff = Regex(
        "(?:снимаю|сниму|убираю|уберу)\\s+(?:телефон\\s+)?с\\s+зарядк|" +
            "(?:отключаю|отключу|вынимаю|выну)\\s+зарядк|unplug"
    )
    private val connectTo = Regex(
        "(?:подключаюсь|подключусь|подключается|подключился|подключении|сажусь|сяду|захожу|зайду|connect(?:s|ed)?)" +
            "\\s+(?:к|в|to|in)\\s+(.+?)(?=,|\\s+(?:—|-|включай|выключай|запускай|отключай|режим)|$)"
    )
    private val disconnectFrom = Regex(
        "(?:отключаюсь|отключусь|отключается|отключился|отключении|выхожу|выйду|disconnect(?:s|ed)?)" +
            "\\s+(?:от|из|from|of)\\s+(.+?)(?=,|\\s+(?:—|-|включай|выключай|запускай|отключай|режим)|$)"
    )
    private val home = Regex("(?:прихожу|приду|возвращаюсь|вернусь)\\s+домой|get home|arrive home")
    private val wifiWords = Regex("(?:вай-?фа\\p{L}*|wi-?fi|сет\\p{L}*|network)")
    private val exitVerb = Regex("(?:^|\\s)(?:выключай|отключай|выходи|turn off|end)\\s+(?:режим|mode)")
    private val modeName = Regex(
        "(?:режим\\p{L}*|mode)\\s+(.+?)(?=,|\\s+(?:—|-)\\s|\\s+(?:когда|как только|при|when)\\s|$)"
    )
    private val btWords = Regex("\\s+(?:блютуз\\p{L}*|bluetooth)")
    private val spaces = Regex("\\s+")

    fun parse(text: String): Request? {
        val t = text.trim().lowercase().replace('ё', 'е').replace(wake, "").trimEnd('.', '!', '?', ' ')
        val rest = modeName.find(t)?.groupValues?.get(1)?.trim()
        val found = trigger(t)
        if (!whenWord.containsMatchIn(" $t") || rest == null || found == null) return null
        val (trigger, target, onConnect) = found
        return Request(rest, trigger, target, onConnect, exit = exitVerb.containsMatchIn(" $t"))
    }

    private fun trigger(t: String): Triple<Trigger, String, Boolean>? {
        val connected = connectTo.find(t)?.groupValues?.get(1)?.trim()
        val disconnected = disconnectFrom.find(t)?.groupValues?.get(1)?.trim()
        return when {
            chargerOff.containsMatchIn(t) -> Triple(Trigger.CHARGER, "", false)
            chargerOn.containsMatchIn(t) -> Triple(Trigger.CHARGER, "", true)
            home.containsMatchIn(t) -> Triple(Trigger.WIFI, "дом", true)
            connected != null -> Triple(kindOf(connected), cleanTarget(connected), true)
            disconnected != null -> Triple(kindOf(disconnected), cleanTarget(disconnected), false)
            else -> null
        }
    }

    private fun kindOf(target: String) = if (wifiWords.containsMatchIn(target)) Trigger.WIFI else Trigger.BLUETOOTH

    private fun cleanTarget(target: String) =
        target.replace(wifiWords, "").replace(btWords, "").replace(spaces, " ").trim()

    /**
     * Which paired device [spoken] means: the one whose name shares the most
     * words with it ("колонке jbl" → "JBL Flip 6"). Null when none shares at
     * least half — "машине" says nothing about "Toyota Touch".
     */
    fun device(spoken: String, names: List<String>): String? = names
        .map { it to ModeNames.overlap(spoken, it) }
        .filter { it.second >= MIN_MATCH }
        .maxByOrNull { it.second }
        ?.first

    private const val MIN_MATCH = 0.5
}
