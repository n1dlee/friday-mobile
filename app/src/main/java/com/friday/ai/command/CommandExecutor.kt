package com.friday.ai.command

import android.util.Log
import com.friday.ai.agent.LearnedCommands
import com.friday.ai.core.CommandRouter
import com.friday.ai.core.modes.ModeEngine
import java.time.LocalDateTime
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.service.mail.MailAssistant
import com.friday.ai.service.messages.MessageAssistant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Carries out what the user asked for, the same way whether it was typed in
 * the chat or said aloud.
 *
 * Before this existed the chat and the voice service each had their own copy
 * of every command, and the copies had drifted: the chat caught failures and
 * the voice loop did not, so one exception in a spoken command silenced
 * Friday for the rest of the turn. One implementation means one behaviour.
 */
// One collaborator per kind of command, plus the two that hold a pending
// "Отправить?"; grouping them would only hide that.
@Suppress("LongParameterList")
class CommandExecutor(
    private val router: CommandRouter,
    private val phone: PhoneActions,
    private val planner: PlannerActions,
    private val info: InfoActions,
    private val mail: MailAssistant,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val messages: MessageAssistant? = null,
    private val learned: LearnedCommands? = null,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val modes: ModeEngine? = null
) {

    /** What came of a command. Most are a reply; three need the caller. */
    sealed interface Outcome {
        data class Reply(val text: String) : Outcome

        /** Plain conversation: the caller asks the model, in its own way. */
        data class Conversation(val text: String) : Outcome

        /** Needs the screen captured, which only the app's UI can start. */
        data object NeedsScreen : Outcome

        /** Needs a file picked, which only the app's UI can start. */
        data class NeedsFile(val hint: String?) : Outcome
    }

    companion object {
        private const val TAG = "CommandExecutor"
        private const val FAILED_RU = "Не получилось: "
        private const val FAILED_EN = "Couldn't do that: "

        /** Whether [reply] is this executor's report of a command that threw. */
        fun isFailure(reply: String): Boolean = reply.startsWith(FAILED_RU) || reply.startsWith(FAILED_EN)
    }

    /** True when the last [route] was answered from what the model taught, not the patterns. */
    @Volatile
    var lastRouteLearned = false
        private set

    /**
     * What [text] asks for: the phrase patterns first, then phrases the model
     * worked out before ([LearnedCommands]); anything else is conversation.
     * Each call also lets the learner hear a "нет, не то" about its last lesson.
     */
    fun route(text: String): CommandResult {
        learned?.noteReply(text)
        // The owner's own modes come first: one named "тишины" means theirs, not the DND phrase.
        modes?.route(text)?.let {
            lastRouteLearned = false
            return it
        }
        val routed = router.route(text)
        val fromLesson = if (routed is CommandResult.ChatMessage) learned?.command(text, now()) else null
        lastRouteLearned = fromLesson != null
        return fromLesson ?: routed
    }

    /**
     * An answer to a pending "Отправить?", or null if [text] is not one. Must
     * be asked before [route]: "нет" to a draft is not a request.
     */
    suspend fun answerPending(text: String, russian: Boolean): String? =
        mail.answerPending(text, russian) ?: messages?.answerPending(text, russian)

    /**
     * Runs [command]. Never throws for an ordinary failure — it becomes a
     * reply the user hears or reads, rather than an exception that ends the
     * turn without a word.
     */
    suspend fun execute(command: CommandResult, russian: Boolean): Outcome =
        if (command is CommandResult.Sequence) inOrder(command.steps, russian) else single(command, russian)

    /**
     * Each step is carried out even if an earlier one failed — "не получилось"
     * for the alarm should not also cost the user the flashlight — and the
     * replies are said together.
     */
    private suspend fun inOrder(steps: List<CommandResult>, russian: Boolean): Outcome {
        val replies = steps.map { step ->
            when (val outcome = single(step, russian)) {
                is Outcome.Reply -> outcome.text
                // Not produced by the router inside a sequence; said rather than dropped.
                else -> if (russian) "Это нужно сделать отдельно." else "That needs doing on its own."
            }
        }
        return Outcome.Reply(replies.joinToString(" ") { it.trim().let { r -> if (r.last() in ".!?…") r else "$r." } })
    }

    /** A step inside a mode always yields words. */
    private fun replyOf(outcome: Outcome, russian: Boolean): String =
        (outcome as? Outcome.Reply)?.text
            ?: if (russian) "Это нужно сделать отдельно." else "That needs doing on its own."

    private suspend fun single(command: CommandResult, russian: Boolean): Outcome = try {
        // Every action here may touch a content provider, a system service
        // or the network.
        withContext(io) {
            when (command) {
                is CommandResult.Phone -> Outcome.Reply(phone.run(command, russian))
                is CommandResult.Planner -> Outcome.Reply(planner.run(command, russian))
                is CommandResult.Info -> Outcome.Reply(info.run(command, russian))
                is CommandResult.ChatMessage -> Outcome.Conversation(command.text)
                is CommandResult.AnalyzeScreen -> Outcome.NeedsScreen
                is CommandResult.AnalyzeFile -> Outcome.NeedsFile(command.fileHint)
                is CommandResult.Sequence -> inOrder(command.steps, russian)
                is CommandResult.Mode -> Outcome.Reply(
                    modes?.handle(command, russian) { step -> replyOf(single(step, russian), russian) }
                        ?: if (russian) "Режимы недоступны." else "Modes aren't available."
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "${command::class.simpleName} failed: ${e.message}")
        Outcome.Reply(if (russian) "$FAILED_RU${e.message}" else "$FAILED_EN${e.message}")
    }
}
