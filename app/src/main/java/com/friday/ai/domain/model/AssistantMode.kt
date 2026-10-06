package com.friday.ai.domain.model

/**
 * Behavioral modes of the AI assistant.
 * Each mode alters the system prompt personality and response style.
 */
enum class AssistantMode(
    val displayName: String,
    val emoji: String,
    val description: String
) {
    DEFAULT("Default", "🤖", "Balanced general-purpose assistant"),
    ANALYTICAL("Analytical", "🔬", "Precise, data-driven, structured responses"),
    CREATIVE("Creative", "🎨", "Imaginative, expansive, metaphorical thinking"),
    CODING("Coding", "💻", "Technical, code-focused, concise explanations"),
    STUDY("Study", "📚", "Educational, step-by-step, patient tutoring"),
    FOUNDER("Founder", "🚀", "Strategic, business-oriented, action-biased"),
    FOCUS("Focus", "🎯", "Minimal, ultra-concise, no fluff")
}
