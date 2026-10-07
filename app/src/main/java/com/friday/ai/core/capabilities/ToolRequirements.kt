package com.friday.ai.core.capabilities

/**
 * What each tool needs in order to work.
 *
 * A tool whose needs aren't met is not offered to the model at all: offered,
 * it gets called, fails, and the model has to talk its way out of a promise
 * it shouldn't have been able to make. Leaving it out also saves tokens.
 * The same check keeps a learned phrase from running a tool that stopped
 * working since it was learned.
 *
 * Tools not listed need nothing beyond what Friday can't run without (a
 * Groq key) or degrade on their own — an alarm, a flashlight, weather for a
 * named city.
 */
object ToolRequirements {

    enum class Need(val en: String) {
        GMAIL("Gmail isn't connected"),
        NOTIFICATIONS("notification access is off"),
        CONTACTS("contacts access is off"),
        CALENDAR("calendar access is off"),
        LOCATION("location access is off"),
        MODES("no modes created yet");

        fun met(c: FridayCapabilities): Boolean = when (this) {
            GMAIL -> c.integrations.gmail
            NOTIFICATIONS -> c.permissions.notificationListener
            CONTACTS -> c.permissions.contacts
            CALENDAR -> c.permissions.calendar
            LOCATION -> c.permissions.location
            MODES -> c.modes.isNotEmpty()
        }
    }

    /** What a tool does, in words for the "unavailable" note in the prompt. */
    private data class Rule(val needs: Set<Need>, val what: String)

    private val rules: Map<String, Rule> = mapOf(
        "mail" to Rule(setOf(Need.GMAIL), "e-mail"),
        // Chats and the media session both come through the notification listener.
        "read_messages" to Rule(setOf(Need.NOTIFICATIONS), "reading chats"),
        "reply_message" to Rule(setOf(Need.NOTIFICATIONS), "replying in chats"),
        "media" to Rule(setOf(Need.NOTIFICATIONS), "pausing/skipping what plays"),
        // Names are resolved in the phone book; without it only bare numbers work.
        "call" to Rule(setOf(Need.CONTACTS), "calling people by name"),
        "send_message" to Rule(setOf(Need.CONTACTS), "messaging people by name"),
        // New events fall back to the calendar's own editor; moving one needs to read it.
        "move_event" to Rule(setOf(Need.CALENDAR), "moving calendar events"),
        "remind_near_place" to Rule(setOf(Need.LOCATION), "reminders near places"),
        "run_mode" to Rule(setOf(Need.MODES), "the owner's modes")
    )

    fun missing(tool: String, c: FridayCapabilities): Set<Need> =
        rules[tool]?.needs?.filterNot { it.met(c) }?.toSet().orEmpty()

    /** True when [tool] can work now; with no snapshot yet, nothing is held back. */
    fun met(tool: String, c: FridayCapabilities?): Boolean = c == null || missing(tool, c).isEmpty()

    /**
     * One line for the system prompt naming what can't be done right now and
     * why, so the model says "turn on notification access" instead of
     * pretending, or inventing a reason. Null when nothing is missing.
     */
    fun unavailableNote(c: FridayCapabilities): String? {
        // No modes is not something to fix; it just means there are none to offer.
        val gaps = rules.filterKeys { it != "run_mode" }.values
            .mapNotNull { rule ->
                val unmet = rule.needs.filterNot { it.met(c) }
                if (unmet.isEmpty()) null else rule.what to unmet
            }
            .groupBy({ it.second.joinToString { n -> n.en } }, { it.first })
        if (gaps.isEmpty()) return null
        return "Unavailable right now (those tools are not offered; if asked, say what the user can enable): " +
            gaps.entries.joinToString("; ") { (why, what) -> "${what.joinToString(", ")} — $why" } + "."
    }
}
