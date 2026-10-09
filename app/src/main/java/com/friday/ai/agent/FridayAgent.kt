package com.friday.ai.agent

import android.util.Log
import com.friday.ai.command.CommandExecutor
import com.friday.ai.core.capabilities.FridayCapabilities
import com.friday.ai.core.capabilities.ToolRequirements
import com.friday.ai.data.remote.ChatEvent
import com.friday.ai.data.remote.GroqApiException
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.data.remote.dto.ApiMessage
import com.friday.ai.data.remote.dto.ToolCall
import com.friday.ai.data.remote.groqJson
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject

/**
 * Friday answering with her hands free: the model may use [AgentTools] while
 * it answers, and is told what each one actually did before it speaks.
 *
 * Before this the model could only talk. Anything the phrase patterns did not
 * recognise reached it as conversation, and it either said it could not help
 * or — worse — said "готово" with nothing done. Now "закинь будильник на
 * полвосьмого", "поставь будильник и напиши маме, что опоздаю" or "сколько
 * будет 17 на 23" are carried out, and the reply is written from the results.
 */
class FridayAgent(
    private val groq: GroqApiService,
    private val commands: CommandExecutor,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    /** Where a phrase the model worked out is kept, to be carried out directly next time. */
    private val learned: LearnedCommands? = null,
    /** A fresh snapshot of what works right now; null means nothing is held back. */
    private val capabilities: suspend () -> FridayCapabilities? = { null }
) {

    private companion object {
        const val TAG = "FridayAgent"

        /**
         * Model turns per answer. Two cover "do it, then say so"; the rest are
         * for a request with several steps or a corrected argument. The last
         * turn is offered no tools, so it has to answer in words.
         */
        const val MAX_ROUNDS = 4

        /** One retry when Groq rejects a malformed tool call; the next attempt is usually fine. */
        const val TOOL_FORMAT_RETRIES = 1

        /**
         * A rate limit often lifts within a second or two ("try again in
         * 855ms"). Waiting that long beats a weaker model — the small one
         * read "полвосьмого" as 8:30. Longer waits go to the backup instead.
         */
        const val WORTH_WAITING_MS = 2_500L
        const val WAIT_MARGIN_MS = 150L
    }

    /**
     * The model in use. Groq's free tier allows each model a few thousand
     * tokens a minute, and the tool list alone is over a thousand; when the
     * main model's minute is used up, the rest of the answer comes from
     * [backup], which has its own allowance.
     */
    private class Models(var current: String, private val backup: String?) {
        fun fallBack(): Boolean {
            if (backup == null || backup == current) return false
            current = backup
            return true
        }
    }

    /**
     * How to reach Groq for one answer.
     *
     * @param backupModel used once [model] hits its rate limit; null to just fail.
     */
    data class Settings(val apiKey: String, val model: String, val maxTokens: Int, val backupModel: String? = null)

    /**
     * The reply to [messages], streamed as text. Tool calls in between are
     * carried out silently; only words reach the collector.
     */
    fun reply(settings: Settings, messages: List<ApiMessage>, russian: Boolean): Flow<String> = flow {
        val conversation = messages.toMutableList()
        val models = Models(settings.model, settings.backupModel)
        val said = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val languageNote = languageNote(said)
        // Only the tools the message is about; the rest when the model asks for them.
        var kit = ToolKit.forText(said)
        val caps = capabilities()
        val done = mutableListOf<Pair<ToolCall, Boolean>>()
        repeat(MAX_ROUNDS) { round ->
            val tools = if (round == MAX_ROUNDS - 1) null else AgentTools.definitions(kit, caps)
            val calls = turn(settings, models, conversation, tools)
            if (calls == null) {
                learnFrom(said, done)
                return@flow
            }
            if (kit != ToolKit.Kit.FULL && calls.any { it.function.name == ToolKit.ESCALATE }) {
                // The guess was wrong: the same turn again, with the phone's tools.
                Log.i(TAG, "Model asked for the phone's tools")
                kit = ToolKit.Kit.FULL
                return@repeat
            }
            conversation += ApiMessage(role = "assistant", content = "", toolCalls = calls)
            calls.forEach { call ->
                val (result, ok) = unavailable(call, caps)?.let { it to false } ?: carryOut(call, russian)
                done += call to ok
                conversation += ApiMessage(role = "tool", content = result + languageNote, toolCallId = call.id)
            }
        }
    }

    /**
     * One phrase, one successful call: worth remembering. Several calls mean
     * a request in parts, which the phrase alone does not describe.
     */
    private fun learnFrom(said: String, done: List<Pair<ToolCall, Boolean>>) {
        val (call, ok) = done.singleOrNull() ?: return
        if (ok) learned?.learn(said, call.function.name, call.function.arguments)
    }

    /**
     * One model turn: its words are passed on as they come. Returns the
     * tools it asked for, or null when it simply answered.
     */
    private suspend fun FlowCollector<String>.turn(
        settings: Settings,
        models: Models,
        conversation: List<ApiMessage>,
        tools: List<com.friday.ai.data.remote.dto.ToolDefinition>?
    ): List<ToolCall>? {
        var retries = TOOL_FORMAT_RETRIES
        var waited = false
        while (true) {
            var calls: List<ToolCall>? = null
            var spoke = false
            try {
                groq.streamChat(settings.apiKey, conversation.toList(), models.current, settings.maxTokens, tools)
                    .collect { event ->
                        when (event) {
                            is ChatEvent.Text -> {
                                spoke = true
                                emit(event.token)
                            }
                            is ChatEvent.ToolCalls -> calls = event.calls
                        }
                    }
                return calls
            } catch (e: GroqApiException) {
                // Retrying after words were already said would say them twice.
                val wait = e.retryAfterMs?.takeIf { e.rateLimited && !waited && it <= WORTH_WAITING_MS }
                when {
                    spoke -> throw e
                    wait != null -> {
                        waited = true
                        Log.w(TAG, "Rate limited, waiting ${wait}ms")
                        delay(wait + WAIT_MARGIN_MS)
                    }
                    e.rateLimited && models.fallBack() -> Log.w(TAG, "Rate limited, switching to ${models.current}")
                    e.toolUseFailed && retries-- > 0 -> Log.w(TAG, "Malformed tool call, retrying: ${e.message}")
                    else -> throw e
                }
            }
        }
    }

    /**
     * Results are often English ("Opening Spotify"), and the model tends to
     * copy them word for word — a rule in the system prompt alone did not
     * stop "Alarm set for 07:30" in reply to a Russian request. A note next
     * to the result itself does. The language is the one the user just used.
     */
    private fun languageNote(said: String): String {
        val russian = said.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        return if (russian) "\n(Tell the user in Russian.)" else "\n(Tell the user in English.)"
    }

    /**
     * Runs one tool and describes the outcome for the model, in plain words.
     * The flag says whether a command was actually carried out.
     */
    /**
     * Why [call] can't run right now, or null if it can. Unavailable tools
     * aren't offered, but a model can still call one it saw earlier in the
     * conversation.
     */
    private fun unavailable(call: ToolCall, caps: FridayCapabilities?): String? {
        val gaps = caps?.let { ToolRequirements.missing(call.function.name, it) }.orEmpty()
        if (gaps.isEmpty()) return null
        return "Error: unavailable right now — ${gaps.joinToString { it.en }}. Nothing was done; " +
            "tell the user what to enable."
    }

    private suspend fun carryOut(call: ToolCall, russian: Boolean): Pair<String, Boolean> {
        val args = try {
            groqJson.parseToJsonElement(call.function.arguments).jsonObject
        } catch (e: SerializationException) {
            return "Error: arguments are not valid JSON (${e.message})" to false
        } catch (e: IllegalArgumentException) {
            return "Error: arguments must be a JSON object (${e.message})" to false
        }
        val result = when (val interpreted = AgentTools.interpret(call.function.name, args, now())) {
            is AgentTools.Call.Invalid -> "Error: ${interpreted.reason}. Nothing was done." to false
            is AgentTools.Call.Answer -> interpreted.text to false
            // Offered only with the light kit and handled before this; harmless if it comes again.
            AgentTools.Call.Escalate -> "The phone's tools are already available." to false
            is AgentTools.Call.Command -> when (val outcome = commands.execute(interpreted.command, russian)) {
                is CommandExecutor.Outcome.Reply -> outcome.text to !CommandExecutor.isFailure(outcome.text)
                // No tool leads to these; said plainly in case one ever does.
                else -> "Nothing was done: this needs the app on screen." to false
            }
        }
        Log.i(TAG, "${call.function.name}(${call.function.arguments}) -> ${result.first}")
        return result
    }
}
