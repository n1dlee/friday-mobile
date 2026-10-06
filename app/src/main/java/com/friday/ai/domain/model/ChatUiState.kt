package com.friday.ai.domain.model

/**
 * Immutable UI state for the chat screen.
 * Follows unidirectional data flow pattern.
 */
data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentMode: AssistantMode = AssistantMode.DEFAULT,
    val isVoiceListening: Boolean = false
)
