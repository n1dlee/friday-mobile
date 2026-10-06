package com.friday.ai.domain.usecase

import com.friday.ai.domain.repository.AssistantRepository
import kotlinx.coroutines.flow.Flow

class AnalyzeContentUseCase(private val repository: AssistantRepository) {

    suspend fun analyzeImage(
        imageBase64: String,
        prompt: String = "Describe what you see on this screen in detail. Answer in the user's language."
    ): Flow<String> {
        return repository.analyzeImage(imageBase64, prompt)
    }

    suspend fun analyzeText(
        text: String,
        prompt: String = "Analyze the following content and provide detailed insights:"
    ): Flow<String> {
        return repository.analyzeText(text, prompt)
    }
}
