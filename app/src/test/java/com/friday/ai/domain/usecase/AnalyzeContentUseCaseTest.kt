package com.friday.ai.domain.usecase

import app.cash.turbine.test
import com.friday.ai.domain.repository.AssistantRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AnalyzeContentUseCaseTest {

    private lateinit var repository: AssistantRepository
    private lateinit var useCase: AnalyzeContentUseCase

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        useCase = AnalyzeContentUseCase(repository)
    }

    @Test
    fun `analyzeImage delegates to repository`() = runTest {
        coEvery { repository.analyzeImage(any(), any()) } returns flowOf("I see a screenshot with...")

        useCase.analyzeImage("base64data").test {
            assertEquals("I see a screenshot with...", awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `analyzeText delegates to repository`() = runTest {
        coEvery { repository.analyzeText(any(), any()) } returns flowOf("This file contains...")

        useCase.analyzeText("file content here").test {
            assertEquals("This file contains...", awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `analyzeImage uses custom prompt`() = runTest {
        coEvery { repository.analyzeImage("img", "What is this?") } returns flowOf("It's a photo")

        useCase.analyzeImage("img", "What is this?").test {
            assertEquals("It's a photo", awaitItem())
            awaitComplete()
        }
    }
}
