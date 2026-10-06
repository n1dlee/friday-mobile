package com.friday.ai.core

/**
 * Which Groq model does which job.
 *
 * Groq retires models without much warning, and a hard-coded name fails all
 * at once: when `llama-3.3-70b-versatile` and `llama-3.1-8b-instant` went,
 * Friday's spoken answers, memory, session summaries and mail retelling all
 * stopped together, while speech recognition carried on and hid the problem.
 * So every job names an ordered list of acceptable models, and the first one
 * the account can actually use is chosen from the live list.
 */
object GroqModels {

    enum class Role {
        /** Conversation, spoken or typed. Quality first. */
        CHAT,

        /** Background work: extracting facts, summarising. Speed and cost first. */
        FAST,

        /** Anything with an image in it. */
        VISION
    }

    /*
     * Measured on this account (Oct 2026), short Russian prompts:
     * gpt-oss-120b — best on facts, ~300–800 ms; gpt-oss-20b — ~200–350 ms;
     * qwen3.8-27b — fastest (~200 ms), no reasoning step, and the only one
     * that accepts images. All three get arithmetic wrong at times.
     */
    private val preferences = mapOf(
        Role.CHAT to listOf("openai/gpt-oss-120b", "qwen/qwen3.8-27b", "openai/gpt-oss-20b"),
        Role.FAST to listOf("openai/gpt-oss-20b", "qwen/qwen3.8-27b", "openai/gpt-oss-120b"),
        Role.VISION to listOf("qwen/qwen3.8-27b")
    )

    /** Used before the live list has ever been fetched. */
    fun fallback(role: Role): String = preferences.getValue(role).first()

    /**
     * The model to use for [role].
     *
     * @param available ids the account can use right now; empty when unknown
     * @param chosen the user's own pick from settings, honoured while it exists
     * @return null only when [available] is known and holds nothing suitable
     */
    fun pick(role: Role, available: Set<String>, chosen: String? = null): String? {
        if (available.isEmpty()) return chosen ?: fallback(role)
        if (chosen != null && chosen in available && suits(role, chosen)) return chosen
        return preferences.getValue(role).firstOrNull { it in available }
    }

    /** Models offered in settings for conversation: the known good ones that exist now. */
    fun choicesForChat(available: Set<String>): List<String> =
        preferences.getValue(Role.CHAT).filter { available.isEmpty() || it in available }

    /**
     * Reasoning models think before answering, and the thinking is billed
     * against the same token limit as the answer. With a 150-token cap meant
     * for one spoken sentence, gpt-oss spent 139–148 tokens thinking and was
     * cut off mid-word. Low effort keeps that to a handful of tokens.
     *
     * [thorough] is for requests that carry tools. On low effort the model
     * often did one part of "сделай громче и открой камеру" and then said it
     * had done both; on medium it called a tool for every part, 4 times of 4.
     */
    fun reasoningEffort(model: String, thorough: Boolean = false): String? = when {
        !model.startsWith("openai/gpt-oss") -> null
        thorough -> "medium"
        else -> "low"
    }

    /** Room for the thinking on top of what the answer itself needs. */
    fun tokenBudget(model: String, forAnswer: Int, thorough: Boolean = false): Int =
        when (reasoningEffort(model, thorough)) {
            null -> forAnswer
            "low" -> forAnswer + REASONING_HEADROOM
            else -> forAnswer + THOROUGH_HEADROOM
        }

    private const val REASONING_HEADROOM = 300
    private const val THOROUGH_HEADROOM = 600

    private fun suits(role: Role, model: String): Boolean =
        role != Role.VISION || model in preferences.getValue(Role.VISION)
}
