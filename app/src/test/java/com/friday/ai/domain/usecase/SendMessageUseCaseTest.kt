package com.friday.ai.domain.usecase

import app.cash.turbine.test
import com.friday.ai.domain.model.AssistantMode
import com.friday.ai.domain.model.Message
import com.friday.ai.domain.model.MessageRole
import com.friday.ai.domain.repository.AssistantRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SendMessageUseCaseTest {

    private lateinit var repository: AssistantRepository
    private lateinit var useCase: SendMessageUseCase

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        useCase = SendMessageUseCase(repository)
    }

    @Test
    fun `invoke delegates to repository sendMessage`() = runTest {
        val tokens = flowOf("Hello", " world", "!")
        coEvery {
            repository.sendMessage("Hi", emptyList(), AssistantMode.DEFAULT)
        } returns tokens

        useCase("Hi", emptyList(), AssistantMode.DEFAULT).test {
            assertEquals("Hello", awaitItem())
            assertEquals(" world", awaitItem())
            assertEquals("!", awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `saveMessage delegates to repository`() = runTest {
        val message = Message(content = "Test", role = MessageRole.USER)

        useCase.saveMessage(message)

        coVerify { repository.saveMessage(message) }
    }
}
