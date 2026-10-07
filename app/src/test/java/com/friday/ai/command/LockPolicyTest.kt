package com.friday.ai.command

import com.friday.ai.core.CommandRouter
import com.friday.ai.core.mail.MailCommands
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.DeviceAction
import com.friday.ai.service.mail.MailAssistant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPolicyTest {

    private val message = CommandResult.SendMessage("мама", "еду", null)

    @Test
    fun `what reaches people or reads their words waits for unlock without a verified voice`() {
        listOf(
            CommandResult.PhoneCall("мама"), message, CommandResult.ReplyMessage(null, "да"),
            CommandResult.ReadMessages(null), CommandResult.Mail(MailCommands.Request.CheckUnread),
            CommandResult.WhatDidIMiss
        ).forEach { assertTrue("$it", LockPolicy.needsUnlock(it, locked = true, ownerVerified = false)) }
    }

    @Test
    fun `the phone's own things don't`() {
        listOf(
            CommandResult.ToggleFlashlight, CommandResult.DeviceControl(DeviceAction.VOLUME_UP),
            CommandResult.SetTimer(60, null), CommandResult.Mode.Run("отдыха")
        ).forEach { assertFalse("$it", LockPolicy.needsUnlock(it, locked = true, ownerVerified = false)) }
    }

    @Test
    fun `unlocked, or the owner's voice verified, anything goes`() {
        assertFalse(LockPolicy.needsUnlock(message, locked = false, ownerVerified = false))
        assertFalse(LockPolicy.needsUnlock(message, locked = true, ownerVerified = true))
    }

    @Test
    fun `a sequence with one personal step waits`() {
        val s = CommandResult.Sequence(listOf(CommandResult.ToggleFlashlight, message))
        assertTrue(LockPolicy.needsUnlock(s, locked = true, ownerVerified = false))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LockedExecutorTest {

    private val dispatcher = StandardTestDispatcher()
    private val phone = mockk<PhoneActions>(relaxed = true)
    private var locked = true

    private val executor = CommandExecutor(
        mockk<CommandRouter>(relaxed = true), phone, mockk(relaxed = true), mockk(relaxed = true),
        mockk<MailAssistant>(relaxed = true), dispatcher,
        lock = { locked to false }
    )

    @Test
    fun `a locked phone without a voice profile doesn't send, but still lights the torch`() = runTest(dispatcher) {
        coEvery { phone.run(any(), any()) } returns "ok"
        val sent = executor.execute(CommandResult.SendMessage("мама", "еду", null), russian = true)
        assertTrue((sent as CommandExecutor.Outcome.Reply).text.startsWith("Телефон заблокирован"))
        coVerify(exactly = 0) { phone.run(CommandResult.SendMessage("мама", "еду", null), any()) }

        assertEquals(CommandExecutor.Outcome.Reply("ok"), executor.execute(CommandResult.ToggleFlashlight, true))
    }

    @Test
    fun `in a sequence only the personal step waits`() = runTest(dispatcher) {
        coEvery { phone.run(any(), any()) } returns "Фонарик включён"
        val r = executor.execute(
            CommandResult.Sequence(listOf(CommandResult.ToggleFlashlight, CommandResult.PhoneCall("мама"))), true
        ) as CommandExecutor.Outcome.Reply
        assertTrue(r.text, r.text.startsWith("Фонарик включён."))
        assertTrue(r.text.contains("Телефон заблокирован"))
    }

    @Test
    fun `unlocked, it just runs`() = runTest(dispatcher) {
        locked = false
        coEvery { phone.run(any(), any()) } returns "Пишу маме"
        assertEquals(
            CommandExecutor.Outcome.Reply("Пишу маме"),
            executor.execute(CommandResult.SendMessage("мама", "еду", null), true)
        )
    }
}
