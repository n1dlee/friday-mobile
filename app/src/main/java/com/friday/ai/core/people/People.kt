package com.friday.ai.core.people

import com.friday.ai.core.ContactMatcher
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/** Ways to send someone a message. SMS needs no app; the others need theirs installed. */
enum class Channel(val label: String, val packages: List<String>) {
    SMS("SMS", emptyList()),
    WHATSAPP("WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b")),
    TELEGRAM("Telegram", listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"));

    companion object {
        /** "ватсап", "в телеграм", "WhatsApp" → the channel; null if none is named. */
        fun named(text: String): Channel? {
            val t = text.lowercase()
            return when {
                listOf("whatsapp", "ватсап", "вотсап", "вацап", "ватсапп", "уатсап").any { it in t } -> WHATSAPP
                listOf("telegram", "телеграм", "телега", "тг ").any { it in "$t " } -> TELEGRAM
                listOf("смс", "sms", "эсэмэс", "text message").any { it in t } -> SMS
                else -> null
            }
        }
    }
}

/**
 * One phone number of a person, with the country it belongs to.
 *
 * @param region ISO country ("US", "UZ"), or null when the number can't be read
 * @param e164 the number in full international form, when it could be read
 */
data class PhoneNumber(val raw: String, val e164: String?, val region: String?, val mobile: Boolean = true)

/** A phone-book entry with everything a decision about contacting them needs. */
data class Person(
    val name: String,
    val phones: List<PhoneNumber>,
    val starred: Boolean = false,
    /** Apps that have registered this person as a user — from the phone book, not a guess. */
    val messengers: Set<Channel> = emptySet(),
    /** Phone-book id; null for a number that was dictated rather than looked up. */
    val id: Long? = null
)

/**
 * Reading numbers the way the phone does.
 *
 * A saved number is often local ("8 900 …", "(415) 555-…"); it is read in
 * the country of the user's SIM, as the dialler would. Built on Google's
 * libphonenumber, so every country's numbering plan is covered without a
 * table in this code.
 */
object PhoneNumbers {

    private val util = PhoneNumberUtil.getInstance()

    fun inspect(raw: String, homeRegion: String?, mobile: Boolean = true): PhoneNumber = try {
        val parsed = util.parse(raw, homeRegion?.uppercase() ?: "ZZ")
        PhoneNumber(
            raw = raw,
            e164 = util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164),
            region = util.getRegionCodeForNumber(parsed)?.takeIf { it != "ZZ" },
            mobile = mobile
        )
    } catch (_: NumberParseException) {
        PhoneNumber(raw, e164 = null, region = null, mobile = mobile)
    }

    /** "Узбекистан" / "Uzbekistan". */
    fun countryName(region: String, russian: Boolean): String =
        Locale("", region).getDisplayCountry(if (russian) Locale("ru") else Locale.ENGLISH).ifBlank { region }
}

/**
 * Who the user means.
 *
 * Unlike a single "best match", this can say "I'm not sure": "мама" against a
 * phone book holding both "Мамуля" and "Мамед" is not something to settle by
 * picking one and texting them.
 */
object PeopleResolver {

    sealed interface Result {
        data class Found(val person: Person) : Result

        /** Several people fit about equally well; ask which. */
        data class Ambiguous(val candidates: List<Person>) : Result

        data object NotFound : Result
    }

    /** Points between the best and second-best match for the best to win outright. */
    private const val CLEAR_LEAD = 20
    private const val MAX_CANDIDATES = 3

    fun resolve(query: String, people: List<Person>): Result {
        val ranked = ContactMatcher.ranked(query, people) { it.name }
        if (ranked.isEmpty()) return Result.NotFound
        val (best, bestScore) = ranked.first()
        val close = ranked.filter { (_, score) -> bestScore - score < CLEAR_LEAD }
        return when {
            // Only spelled alike: asked, never called on a guess.
            bestScore == ContactMatcher.FUZZY -> Result.Ambiguous(close.take(MAX_CANDIDATES).map { it.first })
            close.size == 1 -> Result.Found(best)
            // A starred contact among equals is the one the user means.
            close.count { it.first.starred } == 1 -> Result.Found(close.first { it.first.starred }.first)
            else -> Result.Ambiguous(close.take(MAX_CANDIDATES).map { it.first })
        }
    }
}
