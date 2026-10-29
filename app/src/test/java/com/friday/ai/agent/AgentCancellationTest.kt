package com.friday.ai.agent

import com.friday.ai.command.CommandExecutor
import com.friday.ai.data.remote.ChatEvent
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.data.remote.dto.ApiMessage
import com.friday.ai.data.remote.dto.FunctionCall
import com.friday.ai.data.remote.dto.ToolCall
import com.friday.ai.domain.model.CommandResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AgentCancellationTest {
    @Test
    fun `completed external effect is not repeated and cancellation prevents the next tool`() = runTest {
        val groq = mockk<GroqApiService>()
        val commands = mockk<CommandExecutor>()
        val calls = listOf(
            ToolCall("first", function = FunctionCall("flashlight", "{}")),
            ToolCall("next", function = FunctionCall("set_timer", "{\"seconds\":60}"))
        )
        every { groq.streamChat(any(), any(), any(), any(), any()) } returns flowOf(ChatEvent.ToolCalls(calls))
        coEvery { commands.execute(CommandResult.ToggleFlashlight, true) } coAnswers {
            currentCoroutineContext().cancel()
            CommandExecutor.Outcome.Reply("on")
        }
        val job = launch {
            FridayAgent(groq, commands).reply(
                FridayAgent.Settings("test", "model", 100), listOf(ApiMessage("user", "включи фонарик и таймер")), true
            ).toList()
        }
        job.join()
        coVerify(exactly = 1) { commands.execute(CommandResult.ToggleFlashlight, true) }
        coVerify(exactly = 0) { commands.execute(CommandResult.SetTimer(60, null), true) }
    }
}
