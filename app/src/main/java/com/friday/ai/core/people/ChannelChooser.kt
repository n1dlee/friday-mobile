package com.friday.ai.core.people

/**
 * How to reach someone: SMS, WhatsApp or Telegram, and which of their numbers.
 *
 * Decided the way a person would, from what the phone knows — not from a
 * fixed default:
 *  1. An app the user named ("в ватсап") is used if it is installed.
 *  2. Otherwise what the user used for this person last time.
 *  3. A number in the user's own country gets SMS — cheap and always there.
 *  4. A number abroad goes through a messenger: international SMS costs
 *     money and often never arrives. The messenger the person is actually
 *     registered on wins; among those, the user's usual one, then WhatsApp.
 *  5. SMS abroad only when there is no messenger to use — and it is said.
 *
 * "Смс маме" is read as "message mum": when mum's number is in Uzbekistan and
 * the user is in the US, WhatsApp is what they would have picked themselves.
 * Every step is recorded in [Choice.reasons], so the reply can say why.
 *
 * Calls are decided the same way, with [Channel.SMS] standing for the
 * ordinary phone line: "позвони маме" rings her mobile at home, and abroad
 * becomes a WhatsApp call instead of an international one.
 */
object ChannelChooser {

    data class Situation(
        val person: Person,
        /** What the user said: an app, "смс", or nothing. */
        val requested: Channel?,
        /** ISO country of the user's own number (SIM), if known. */
        val myRegion: String?,
        /** Messaging apps on this phone. SMS is always available. */
        val installed: Set<Channel>,
        /** What the user picked for this person before, if anything. */
        val usual: Channel? = null,
        /** The user's general favourite messenger, if they have told Friday one. */
        val favourite: Channel? = null
    )

    sealed interface Reason {
        data object Asked : Reason
        data class AskedButMissing(val channel: Channel) : Reason
        data object UsualForPerson : Reason
        data object SameCountry : Reason
        data class Abroad(val region: String) : Reason
        data class RegisteredOn(val channel: Channel) : Reason
        data object NoMessengerAbroad : Reason
        data object CountryUnknown : Reason
    }

    data class Choice(val channel: Channel, val number: PhoneNumber, val reasons: List<Reason>)

    /** Null only when the person has no number at all. */
    fun choose(s: Situation): Choice? {
        if (s.person.phones.isEmpty()) return null
        val missing = s.requested?.takeIf { it != Channel.SMS && it !in s.installed }
        val noted = listOfNotNull(missing?.let { Reason.AskedButMissing(it) })
        return asked(s) ?: usual(s, noted) ?: atHome(s, noted) ?: abroad(s, noted)
    }

    /** Step 1: an app the user named, if it is here. */
    private fun asked(s: Situation): Choice? =
        s.requested?.takeIf { it != Channel.SMS && it in s.installed }
            ?.let { Choice(it, bestNumber(s.person.phones, s.myRegion), listOf(Reason.Asked)) }

    /**
     * Step 2: what was used for this person before. Only when nothing was
     * asked for: "смс" is honoured for a number at home, and abroad it is
     * taken to mean "a message", which steps 4–5 decide.
     */
    private fun usual(s: Situation, noted: List<Reason>): Choice? =
        s.usual?.takeIf { s.requested == null && (it == Channel.SMS || it in s.installed) }
            ?.let { Choice(it, bestNumber(s.person.phones, s.myRegion), noted + Reason.UsualForPerson) }

    /** Step 3: a number in the user's own country is texted. */
    private fun atHome(s: Situation, noted: List<Reason>): Choice? =
        s.person.phones.filter { s.myRegion != null && it.region == s.myRegion }
            .takeIf { it.isNotEmpty() }
            ?.let { Choice(Channel.SMS, it.preferMobile(), noted + Reason.SameCountry) }

    /** Steps 4–5: abroad, a messenger if there is one; SMS only if there is not. */
    private fun abroad(s: Situation, noted: List<Reason>): Choice {
        val number = s.person.phones.preferMobile()
        val region = number.region
        if (s.myRegion == null || region == null) {
            return Choice(s.requested ?: Channel.SMS, number, noted + Reason.CountryUnknown)
        }
        val messenger = messengerFor(s)
            ?: return Choice(Channel.SMS, number, noted + Reason.Abroad(region) + Reason.NoMessengerAbroad)
        val registered = listOfNotNull(Reason.RegisteredOn(messenger).takeIf { messenger in s.person.messengers })
        return Choice(messenger, number, noted + Reason.Abroad(region) + registered)
    }

    /** Installed messengers, the ones this person is on first, then the user's favourite, then WhatsApp. */
    private fun messengerFor(s: Situation): Channel? {
        val apps = listOf(Channel.WHATSAPP, Channel.TELEGRAM).filter { it in s.installed }
        return apps.sortedWith(
            compareByDescending<Channel> { it in s.person.messengers }
                .thenByDescending { it == s.favourite }
        ).firstOrNull()
    }

    private fun bestNumber(phones: List<PhoneNumber>, myRegion: String?): PhoneNumber =
        phones.filter { it.region == myRegion }.ifEmpty { phones }.preferMobile()

    private fun List<PhoneNumber>.preferMobile(): PhoneNumber = firstOrNull { it.mobile } ?: first()

    /** Why, in a sentence the user can hear. [call] words it for a call rather than a message. */
    fun explain(choice: Choice, russian: Boolean, call: Boolean = false): String = choice.reasons.mapNotNull { r ->
        when (r) {
            Reason.Asked, Reason.SameCountry, Reason.CountryUnknown -> null
            is Reason.AskedButMissing ->
                if (russian) "${r.channel.label} не установлен" else "${r.channel.label} isn't installed"
            Reason.UsualForPerson -> if (russian) "как в прошлый раз" else "as last time"
            is Reason.Abroad -> PhoneNumbers.countryName(r.region, russian).let {
                if (russian) "номер не местный ($it)" else "the number is abroad ($it)"
            }
            is Reason.RegisteredOn ->
                if (russian) "у контакта есть ${r.channel.label}" else "they're on ${r.channel.label}"
            Reason.NoMessengerAbroad -> when {
                call && russian -> "мессенджеров нет, поэтому международный звонок"
                call -> "no messenger installed, so an international call"
                russian -> "мессенджеров нет, поэтому международное SMS"
                else -> "no messenger installed, so an international SMS"
            }
        }
    }.joinToString(", ")
}
