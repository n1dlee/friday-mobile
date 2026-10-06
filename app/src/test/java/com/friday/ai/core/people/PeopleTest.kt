package com.friday.ai.core.people

import com.friday.ai.core.CommandRouter
import com.friday.ai.core.DeviceContext
import com.friday.ai.core.MediaSearch
import com.friday.ai.core.people.ChannelChooser.Reason
import com.friday.ai.core.people.ChannelChooser.Situation
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {

    @Test
    fun `each number knows its country`() {
        assertEquals("UZ", PhoneNumbers.inspect("+998 90 123 45 67", "US").region)
        assertEquals("US", PhoneNumbers.inspect("(415) 555-0132", "US").region)
        // A local number is read in the user's own country, as the dialler would.
        assertEquals("+74951234567", PhoneNumbers.inspect("8 495 123-45-67", "RU").e164)
    }

    @Test
    fun `an unreadable number is kept, not thrown away`() {
        val n = PhoneNumbers.inspect("мамин", "US")
        assertNull(n.region)
        assertEquals("мамин", n.raw)
    }

    @Test
    fun `countries are named in the user's language`() {
        assertEquals("Узбекистан", PhoneNumbers.countryName("UZ", russian = true))
        assertEquals("Uzbekistan", PhoneNumbers.countryName("UZ", russian = false))
    }
}

class PeopleResolverTest {

    private fun person(name: String, starred: Boolean = false) =
        Person(name, listOf(PhoneNumber("+998901234567", "+998901234567", "UZ")), starred)

    @Test
    fun `mum is found however she is saved`() {
        listOf("Mamito", "Мамуля", "Mom", "Мама ❤️", "Mother", "мамочка").forEach { saved ->
            val r = PeopleResolver.resolve("маме", listOf(person(saved), person("Иван Петров")))
            assertEquals(saved, (r as? PeopleResolver.Result.Found)?.person?.name)
        }
    }

    @Test
    fun `a pet name beats a name that merely starts the same`() {
        val r = PeopleResolver.resolve("маме", listOf(person("Мамед"), person("Мамуля")))
        assertEquals("Мамуля", (r as PeopleResolver.Result.Found).person.name)
    }

    @Test
    fun `two equally good matches are asked about, not guessed`() {
        val r = PeopleResolver.resolve("Саше", listOf(person("Саша Иванов"), person("Саша Ким")))
        assertTrue("got $r", r is PeopleResolver.Result.Ambiguous)
    }

    @Test
    fun `a starred contact settles a tie`() {
        val r = PeopleResolver.resolve("Саше", listOf(person("Саша Иванов"), person("Саша Ким", starred = true)))
        assertEquals("Саша Ким", (r as PeopleResolver.Result.Found).person.name)
    }

    @Test
    fun `nobody is nobody`() {
        assertEquals(PeopleResolver.Result.NotFound, PeopleResolver.resolve("Зигфриду", listOf(person("Мама"))))
    }
}

class ChannelChooserTest {

    private val uzMobile = PhoneNumbers.inspect("+998 90 123 45 67", "US")
    private val usMobile = PhoneNumbers.inspect("+1 415 555 0132", "US")
    private val both = setOf(Channel.WHATSAPP, Channel.TELEGRAM)

    private fun mum(vararg on: Channel, phones: List<PhoneNumber> = listOf(uzMobile)) =
        Person("Mamito", phones, messengers = on.toSet())

    @Test
    fun `sms to mum abroad goes through the messenger she is on`() {
        // The user is in the US, mum's number is Uzbek: "смс маме" means "message mum".
        val c = ChannelChooser.choose(Situation(mum(Channel.WHATSAPP), Channel.SMS, "US", both))!!
        assertEquals(Channel.WHATSAPP, c.channel)
        assertTrue(c.reasons.contains(Reason.Abroad("UZ")))
        assertTrue(c.reasons.contains(Reason.RegisteredOn(Channel.WHATSAPP)))
        val said = ChannelChooser.explain(c, russian = true)
        assertTrue(said, said.contains("Узбекистан") && said.contains("WhatsApp"))
    }

    @Test
    fun `the messenger she is actually on wins over the default`() {
        val c = ChannelChooser.choose(Situation(mum(Channel.TELEGRAM), null, "US", both))!!
        assertEquals(Channel.TELEGRAM, c.channel)
    }

    @Test
    fun `not knowing who is on what, WhatsApp is the default abroad`() {
        assertEquals(Channel.WHATSAPP, ChannelChooser.choose(Situation(mum(), null, "US", both))!!.channel)
    }

    @Test
    fun `the user's favourite messenger is preferred when she is on both or unknown`() {
        val c = ChannelChooser.choose(Situation(mum(), null, "US", both, favourite = Channel.TELEGRAM))!!
        assertEquals(Channel.TELEGRAM, c.channel)
    }

    @Test
    fun `a number at home gets a plain sms`() {
        val friend = Person("Jake", listOf(usMobile), messengers = setOf(Channel.WHATSAPP))
        assertEquals(Channel.SMS, ChannelChooser.choose(Situation(friend, null, "US", both))!!.channel)
    }

    @Test
    fun `with numbers in both countries, the local one is texted`() {
        val c = ChannelChooser.choose(Situation(mum(phones = listOf(uzMobile, usMobile)), Channel.SMS, "US", both))!!
        assertEquals(Channel.SMS, c.channel)
        assertEquals("US", c.number.region)
    }

    @Test
    fun `a messenger named by the user is used`() {
        val c = ChannelChooser.choose(Situation(mum(Channel.WHATSAPP), Channel.TELEGRAM, "US", both))!!
        assertEquals(Channel.TELEGRAM, c.channel)
        assertEquals(listOf(Reason.Asked), c.reasons)
    }

    @Test
    fun `a named messenger that is not installed is said, and the next best is used`() {
        val c = ChannelChooser.choose(Situation(mum(), Channel.TELEGRAM, "US", setOf(Channel.WHATSAPP)))!!
        assertEquals(Channel.WHATSAPP, c.channel)
        assertTrue(ChannelChooser.explain(c, true).contains("Telegram не установлен"))
    }

    @Test
    fun `what was used for this person last time is used again`() {
        val c = ChannelChooser.choose(Situation(mum(Channel.WHATSAPP), null, "US", both, usual = Channel.TELEGRAM))!!
        assertEquals(Channel.TELEGRAM, c.channel)
    }

    @Test
    fun `abroad with no messenger, the sms is international and said so`() {
        val c = ChannelChooser.choose(Situation(mum(), null, "US", emptySet()))!!
        assertEquals(Channel.SMS, c.channel)
        assertTrue(ChannelChooser.explain(c, true).contains("международное SMS"))
    }

    @Test
    fun `nobody's country known means no guessing`() {
        val c = ChannelChooser.choose(Situation(mum(), null, null, both))!!
        assertEquals(Channel.SMS, c.channel)
    }
}

class SpokenRequestsTest {

    private val router = CommandRouter()

    @Test
    fun `messages with an app or the word sms are read whole`() {
        assertEquals(
            CommandResult.SendMessage("маме", "я опоздаю", Channel.SMS),
            router.route("смс маме что я опоздаю")
        )
        assertEquals(
            CommandResult.SendMessage("маме", "я опоздаю", Channel.WHATSAPP),
            router.route("напиши маме в ватсап, что я опоздаю")
        )
        assertEquals(
            CommandResult.SendMessage("Ивану", "буду в пять", Channel.TELEGRAM),
            router.route("напиши в телеграм Ивану: буду в пять")
        )
        assertEquals(CommandResult.SendMessage("маме", null, Channel.WHATSAPP), router.route("напиши маме в ватсап"))
    }

    @Test
    fun `writing without saying it is a message is left to the model`() {
        assertTrue(router.route("напиши стих про осень") is CommandResult.ChatMessage)
        assertTrue(router.route("что значит это сообщение") is CommandResult.ChatMessage)
    }

    @Test
    fun `searches to play are told from plain playback`() {
        assertEquals(
            CommandResult.PlayMedia("Believer", MediaKind.MUSIC, "спотифае"),
            router.route("включи Believer в спотифае")
        )
        assertEquals(
            CommandResult.PlayMedia("обзор айфона", MediaKind.VIDEO, "youtube"),
            router.route("найди на ютубе обзор айфона")
        )
        assertEquals(
            CommandResult.PlayMedia("Shape of You", MediaKind.MUSIC, null),
            router.route("включи песню Shape of You")
        )
        assertTrue(router.route("включи музыку") is CommandResult.MediaControl)
        assertTrue(router.route("включи спотифай") is CommandResult.MediaControl)
        assertTrue(router.route("включи музыку в машине") is CommandResult.MediaControl)
    }
}

class MediaChooserTest {

    private val spotify = DeviceContext.App("com.spotify.music", "Spotify")
    private val samsung = DeviceContext.App("com.sec.android.app.music", "Samsung Music")
    private val ytMusic = DeviceContext.App("com.google.android.apps.youtube.music", "YouTube Music")
    private val youtube = DeviceContext.App("com.google.android.youtube", "YouTube")

    @Test
    fun `a named app is used`() {
        assertEquals(
            ytMusic,
            MediaSearch.Chooser.choose(MediaKind.MUSIC, listOf(spotify, ytMusic), "ютуб музыке", null)
        )
        assertEquals(youtube, MediaSearch.Chooser.choose(MediaKind.VIDEO, listOf(youtube), "на ютубе", null))
    }

    @Test
    fun `the user's usual app comes before any ranking`() {
        val usual = samsung.packageName
        assertEquals(samsung, MediaSearch.Chooser.choose(MediaKind.MUSIC, listOf(spotify, samsung), null, usual))
    }

    @Test
    fun `otherwise a catalogue app beats a local player`() {
        assertEquals(spotify, MediaSearch.Chooser.choose(MediaKind.MUSIC, listOf(samsung, spotify), null, null))
        assertNull(MediaSearch.Chooser.choose(MediaKind.MUSIC, emptyList(), null, null))
    }
}

class CallsTest {

    private val router = CommandRouter()
    private val uz = PhoneNumbers.inspect("+998 90 123 45 67", "US")
    private val us = PhoneNumbers.inspect("+1 415 555 0132", "US")

    @Test
    fun `how to call is read only from words that say it`() {
        assertEquals(CommandResult.PhoneCall("маме"), router.route("позвони маме"))
        assertEquals(CommandResult.PhoneCall("маме", Channel.WHATSAPP), router.route("позвони маме по ватсапу"))
        assertEquals(CommandResult.PhoneCall("Ивану", Channel.TELEGRAM), router.route("набери Ивану в телеграме"))
        assertEquals(CommandResult.PhoneCall("папе", Channel.SMS), router.route("позвони папе по обычной связи"))
        assertEquals(CommandResult.PhoneCall("+79991234567"), router.route("call +79991234567"))
    }

    @Test
    fun `mum abroad gets a WhatsApp call, a friend at home an ordinary one`() {
        val mum = Person("Mamito", listOf(uz), messengers = setOf(Channel.WHATSAPP))
        val both = setOf(Channel.WHATSAPP, Channel.TELEGRAM)
        val c = ChannelChooser.choose(ChannelChooser.Situation(mum, null, "US", both))!!
        assertEquals(Channel.WHATSAPP, c.channel)
        val jake = Person("Jake", listOf(us), messengers = setOf(Channel.WHATSAPP))
        assertEquals(Channel.SMS, ChannelChooser.choose(ChannelChooser.Situation(jake, null, "US", both))!!.channel)
    }

    @Test
    fun `without a messenger abroad the call is international and said so`() {
        val mum = Person("Mamito", listOf(uz))
        val c = ChannelChooser.choose(ChannelChooser.Situation(mum, null, "US", emptySet()))!!
        assertTrue(ChannelChooser.explain(c, russian = true, call = true).contains("международный звонок"))
    }

    @Test
    fun `the voice entry for the chosen number is used, never the video one`() {
        val entries = listOf(
            CallEntry(1, "vnd.android.cursor.item/vnd.com.whatsapp.video.call", "Video call +998 90 123 45 67"),
            CallEntry(2, "vnd.android.cursor.item/vnd.com.whatsapp.voip.call", "Voice call +1 415 555 0132"),
            CallEntry(3, "vnd.android.cursor.item/vnd.com.whatsapp.voip.call", "Voice call +998 90 123 45 67"),
            CallEntry(4, "vnd.android.cursor.item/vnd.org.telegram.messenger.android.call", "Call +998901234567")
        )
        assertEquals(3L, CallEntry.pick(entries, Channel.WHATSAPP, uz)?.id)
        assertEquals(4L, CallEntry.pick(entries, Channel.TELEGRAM, uz)?.id)
        // No entry names the number: any voice entry of that app still rings the person.
        val other = PhoneNumbers.inspect("+998 91 000 00 00", "US")
        assertEquals(2L, CallEntry.pick(entries, Channel.WHATSAPP, other)?.id)
        assertNull(CallEntry.pick(entries.take(1), Channel.WHATSAPP, uz))
    }

    @Test
    fun `the model can ask for a messenger call`() {
        val r = com.friday.ai.agent.AgentTools.interpret(
            "call",
            com.friday.ai.data.remote.groqJson.parseToJsonElement("""{"contact":"мама","via":"whatsapp"}""")
                .let { it as kotlinx.serialization.json.JsonObject },
            java.time.LocalDateTime.now()
        )
        assertEquals(
            com.friday.ai.agent.AgentTools.Call.Command(CommandResult.PhoneCall("мама", Channel.WHATSAPP)),
            r
        )
    }
}
