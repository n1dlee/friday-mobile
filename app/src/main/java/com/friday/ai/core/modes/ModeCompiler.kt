package com.friday.ai.core.modes

import android.util.Log
import com.friday.ai.agent.ActionEnvelope
import com.friday.ai.agent.AgentTools
import com.friday.ai.agent.ToolKit
import com.friday.ai.core.capabilities.FridayCapabilities
import com.friday.ai.data.remote.ChatEvent
import com.friday.ai.data.remote.GroqApiException
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.data.remote.dto.ApiMessage
import com.friday.ai.data.remote.dto.ToolCall
import com.friday.ai.data.remote.groqJson
import java.time.LocalDateTime
import kotlinx.serialization.json.jsonObject

/**
 * Turns the owner's words for a mode into its steps.
 *
 * One model call with the phone's tools, asking for the calls that would
 * put the phone in that mode, all at once. The calls are *not* carried out:
 * they are checked like any tool call ([AgentTools.interpret]) and kept as
 * envelopes. So the vocabulary is closed (a mode can only do what Friday
 * can), a misunderstanding costs nothing, and running the mode later needs
 * no model at all.
 */
class ModeCompiler(
    private val groq: GroqApiService,
    /** Groq key, main model and its backup. */
    private val access: suspend () -> Access,
    private val capabilities: suspend () -> FridayCapabilities?,
    /** The phone context the agent gets (installed players, messengers…), for picking apps. */
    private val phone: () -> String = { "" },
    private val now: () -> LocalDateTime = LocalDateTime::now
) {

    data class Access(val apiKey: String, val model: String, val backup: String?)

    sealed interface Result {
        /** [skipped]: parts the model asked for that Friday can't do, said back to the owner. */
        data class Steps(val steps: List<ActionEnvelope>, val skipped: List<String>) : Result
        data class Failed(val reason: String) : Result
    }

    private companion object {
        const val TAG = "ModeCompiler"
        const val MAX_TOKENS = 1_200

        /** Not steps: arithmetic answers, and a mode starting another mode would loop. */
        val EXCLUDED = setOf("calculate", "run_mode")

        const val INSTRUCTIONS =
            "You turn the description of a phone mode into the phone actions that put the phone in that mode. " +
                "Call one tool per action, ALL in this single reply, in the order they should happen. " +
                "Do only what the description asks; add nothing. Fill arguments from the description " +
                "(music to play goes to play with kind=music and the app if named). " +
                "If part of it can't be done with any tool, skip that part. Never answer in text."
    }

    suspend fun compile(name: String, description: String): Result {
        val access = access()
        if (access.apiKey.isBlank()) return Result.Failed("no_key")
        val tools = AgentTools.definitions(ToolKit.Kit.FULL, capabilities())
            .filterNot { it.function.name in EXCLUDED }
        val messages = listOf(
            ApiMessage("system", INSTRUCTIONS + " " + phone()),
            ApiMessage("user", "Mode «$name»: $description")
        )
        val calls = try {
            ask(access.apiKey, access.model, messages, tools)
        } catch (e: GroqApiException) {
            val backup = access.backup?.takeIf { e.rateLimited && it != access.model }
            if (backup == null) {
                null
            } else {
                Log.w(TAG, "Rate limited, compiling with $backup")
                runCatching { ask(access.apiKey, backup, messages, tools) }.getOrNull()
            }
        }
        return calls?.let(::toSteps) ?: Result.Failed("groq")
    }

    private suspend fun ask(
        key: String,
        model: String,
        messages: List<ApiMessage>,
        tools: List<com.friday.ai.data.remote.dto.ToolDefinition>
    ): List<ToolCall> {
        var calls: List<ToolCall> = emptyList()
        groq.streamChat(key, messages, model, MAX_TOKENS, tools).collect { event ->
            if (event is ChatEvent.ToolCalls) calls = event.calls
        }
        return calls
    }

    /** Checks each call as if it were about to run; only valid commands become steps. */
    internal fun toSteps(calls: List<ToolCall>): Result {
        val steps = mutableListOf<ActionEnvelope>()
        val skipped = mutableListOf<String>()
        calls.forEach { call ->
            val name = call.function.name
            val args = runCatching { groqJson.parseToJsonElement(call.function.arguments).jsonObject }.getOrNull()
            when {
                args == null -> skipped += name
                name in EXCLUDED -> skipped += name
                else -> when (val interpreted = AgentTools.interpret(name, args, now())) {
                    is AgentTools.Call.Command -> steps += ActionEnvelope(name, args)
                    is AgentTools.Call.Invalid -> skipped += "$name: ${interpreted.reason}"
                    else -> skipped += name
                }
            }
        }
        Log.i(TAG, "Compiled ${steps.size} steps, skipped $skipped")
        return if (steps.isEmpty()) Result.Failed("no_steps") else Result.Steps(steps, skipped)
    }
}
