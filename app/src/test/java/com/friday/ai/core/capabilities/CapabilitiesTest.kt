// Long lines on purpose: fixtures and tool-call JSON are kept whole.
@file:Suppress("MaxLineLength")

package com.friday.ai.core.capabilities

import com.friday.ai.agent.AgentTools
import com.friday.ai.agent.FridayAgent
import com.friday.ai.agent.LearnedCommands
import com.friday.ai.agent.ToolKit
import com.friday.ai.command.CommandExecutor
import com.friday.ai.core.capabilities.Diagnostics.Fix
import com.friday.ai.core.capabilities.Diagnostics.Status
import com.friday.ai.core.capabilities.ToolRequirements.Need
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.remote.ChatEvent
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.data.remote.dto.ApiMessage
import com.friday.ai.data.remote.dto.FunctionCall
import com.friday.ai.data.remote.dto.ToolCall
import com.friday.ai.data.remote.dto.ToolDefinition
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDateTime
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A phone where everything a feature can need is in place. */
private val allGood = FridayCapabilities(
    permissions = FridayCapabilities.Permissions(
        microphone = true, overlay = true, notificationListener = true, postNotifications = true,
        contacts = true, phone = true, calendar = true, location = true, batteryExempt = true
    ),
    integrations = FridayCapabilities.Integrations(groqKey = true, gmail = true, lazuri = true),
    voice = FridayCapabilities.Voice(wakeWordEnabled = true, wakeModelReady = true, voiceProfile = true, listening = true)
)

private fun FridayCapabilities.withoutGmail() = copy(integrations = integrations.copy(gmail = false))
private fun FridayCapabilities.withoutNotifications() =
    copy(permissions = permissions.copy(notificationListener = false))

class ToolRequirementsTest {

    @Test
    fun `a tool works when what it needs is in place`() {
        assertTrue(ToolRequirements.met("mail", allGood))
        assertEquals(setOf(Need.GMAIL), ToolRequirements.missing("mail", allGood.withoutGmail()))
        assertEquals(setOf(Need.NOTIFICATIONS), ToolRequirements.missing("reply_message", allGood.withoutNotifications()))
    }

    @Test
    fun `tools that need nothing are always there`() {
        val bare = FridayCapabilities()
        listOf("set_alarm", "flashlight", "weather", "calculate", "web_search", "camera")
            .forEach { assertTrue(it, ToolRequirements.met(it, bare)) }
    }

    @Test
    fun `before the first snapshot nothing is held back`() {
        assertTrue(ToolRequirements.met("mail", null))
    }

    @Test
    fun `the prompt is told what is off and why, in one line`() {
        assertNull(ToolRequirements.unavailableNote(allGood))
        val note = ToolRequirements.unavailableNote(allGood.withoutGmail().withoutNotifications())!!
        assertTrue(note, note.contains("e-mail — Gmail isn't connected"))
        // Several tools with the same cause are grouped under it.
        assertTrue(note, note.contains("reading chats, replying in chats, pausing/skipping what plays — notification access is off"))
    }
}

class CapabilityAwareToolsTest {

    private fun names(defs: List<ToolDefinition>) = defs.map { it.function.name }.toSet()

    @Test
    fun `a tool that can't work isn't offered`() {
        val full = names(AgentTools.definitions(ToolKit.Kit.FULL, allGood.withoutGmail().withoutNotifications()))
        assertFalse("mail" in full)
        assertFalse("read_messages" in full)
        assertFalse("media" in full)
        assertTrue("set_alarm" in full)
        assertEquals(names(AgentTools.definitions), names(AgentTools.definitions(ToolKit.Kit.FULL, allGood)))
    }

    @Test
    fun `the light kit is unaffected`() {
        assertEquals(
            names(AgentTools.definitions(ToolKit.Kit.LIGHT)),
            names(AgentTools.definitions(ToolKit.Kit.LIGHT, FridayCapabilities()))
        )
    }

    @Test
    fun `a call to a tool that isn't available does nothing and says why`() = runTest {
        val groq = mockk<GroqApiService>()
        val commands = mockk<CommandExecutor>(relaxed = true)
        val sent = mutableListOf<List<ApiMessage>>()
        val turns = ArrayDeque(
            listOf(
                flowOf(ChatEvent.ToolCalls(listOf(ToolCall("c0", function = FunctionCall("mail", """{"action":"check"}"""))))),
                flowOf(ChatEvent.Text("Подключите Gmail."))
            )
        )
        every { groq.streamChat(any(), capture(sent), any(), any(), any()) } answers { turns.removeFirst() }
        val agent = FridayAgent(
            groq, commands, now = { LocalDateTime.of(2026, 10, 7, 12, 0) },
            capabilities = { allGood.withoutGmail() }
        )
        val reply = agent.reply(FridayAgent.Settings("k", "m", 100), listOf(ApiMessage("user", "проверь почту")), true)
            .toList().joinToString("")
        assertEquals("Подключите Gmail.", reply)
        assertTrue(sent[1].last().content.contains("Gmail isn't connected"))
        coVerify(exactly = 0) { commands.execute(any(), any()) }
    }

    @Test
    fun `a learned phrase whose tool stopped working falls through to the model`() = runTest {
        var access = true
        val learned = LearnedCommands(
            mockk<UserPreferenceDao>(relaxed = true), TestScope(testScheduler),
            available = { tool -> ToolRequirements.met(tool, if (access) allGood else allGood.withoutNotifications()) }
        )
        learned.learn("притормози трек", "media", """{"action":"pause"}""")
        val now = LocalDateTime.of(2026, 10, 7, 12, 0)
        assertTrue(learned.command("притормози трек", now) != null)
        access = false
        assertNull(learned.command("притормози трек", now))
    }
}

class DiagnosticsTest {

    private fun row(c: FridayCapabilities, id: String) = Diagnostics.rows(c).single { it.id == id }

    @Test
    fun `everything in place means nothing to fix`() {
        val rows = Diagnostics.rows(allGood)
        assertEquals(0, Diagnostics.problems(rows))
        assertTrue(rows.filter { it.status == Status.OK }.all { it.fix == null })
    }

    @Test
    fun `a missing essential is a problem with a way to fix it`() {
        val c = allGood.copy(
            integrations = allGood.integrations.copy(groqKey = false),
            permissions = allGood.permissions.copy(microphone = false)
        )
        assertEquals(Status.PROBLEM, row(c, "groq").status)
        assertEquals(Fix.FRIDAY_SETTINGS, row(c, "groq").fix)
        assertEquals(Fix.MICROPHONE, row(c, "microphone").fix)
        assertEquals(2, Diagnostics.problems(Diagnostics.rows(c)))
    }

    @Test
    fun `what is off by choice is not counted as broken`() {
        val c = allGood.withoutGmail().copy(permissions = allGood.permissions.copy(phone = false))
        assertEquals(Status.OFF, row(c, "gmail").status)
        assertEquals(Status.OFF, row(c, "phone").status)
        assertEquals(0, Diagnostics.problems(Diagnostics.rows(c)))
    }

    @Test
    fun `voice details only matter when the wake word is on`() {
        val off = allGood.copy(voice = FridayCapabilities.Voice(wakeWordEnabled = false))
        val ids = Diagnostics.rows(off).map { it.id }
        assertTrue("wake_word" in ids)
        assertFalse("wake_model" in ids)
        assertFalse("voice_profile" in ids)
        assertEquals(0, Diagnostics.problems(Diagnostics.rows(off)))
    }

    @Test
    fun `a stopped listener with the wake word on is a problem`() {
        val c = allGood.copy(voice = allGood.voice.copy(listening = false, voiceProfile = false))
        assertEquals(Status.PROBLEM, row(c, "listening").status)
        assertTrue(row(c, "voice_profile").detail.contains("any voice"))
    }

    @Test
    fun `device facts are information, never problems`() {
        val rows = Diagnostics.rows(FridayCapabilities())
        assertTrue(rows.filter { it.group == Diagnostics.Group.DEVICE }.all { it.status == Status.INFO && it.fix == null })
    }
}
