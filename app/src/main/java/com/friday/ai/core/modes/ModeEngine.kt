// Long lines on purpose: each reply is a Russian/English pair kept side by side.
@file:Suppress("MaxLineLength")

package com.friday.ai.core.modes

import com.friday.ai.agent.ActionEnvelope
import com.friday.ai.agent.AgentTools
import com.friday.ai.agent.LearnedCommands
import com.friday.ai.command.CommandExecutor
import com.friday.ai.core.DeviceController
import com.friday.ai.domain.model.CommandResult
import java.time.LocalDateTime

/**
 * Modes, end to end: "создай режим грусти: …" → compiled, saved and read
 * back; "режим грусти" → its steps run, with no model call; "выключи режим
 * грусти" → what the steps changed is put back.
 *
 * Device settings report their own undo ([DeviceController]); music a mode
 * started is paused and a flashlight it lit is turned off ([ModeSteps.undoOf]).
 * Things that can't be taken back — a message sent, an alarm set — stay.
 * Every reply is assembled from what the steps actually reported, so a
 * missing permission is said, not hidden.
 */
@Suppress("TooManyFunctions") // one handler per kind of request
class ModeEngine(
    private val store: ModeStore,
    /** [ModeCompiler.compile]; a function so the engine is tested without Groq. */
    private val compile: suspend (name: String, description: String) -> ModeCompiler.Result,
    private val device: DeviceController,
    private val clock: () -> Long = System::currentTimeMillis,
    private val now: () -> LocalDateTime = LocalDateTime::now
) {

    private companion object {
        /** "Отмена" this soon after creating a mode removes it. */
        const val UNDO_WINDOW_MS = 2 * 60 * 1000L
    }

    @Volatile
    private var lastCreated: Pair<String, Long>? = null

    /**
     * The mode request in [text], or null. Run, exit and describe only count
     * when the name is one of the owner's modes: "включи режим полёта" is a
     * system setting, not a mode Friday doesn't have.
     */
    fun route(text: String): CommandResult.Mode? {
        lastCreated?.let { (id, at) ->
            if (clock() - at <= UNDO_WINDOW_MS && LearnedCommands.isCorrection(text)) {
                lastCreated = null
                return CommandResult.Mode.CancelCreated(id)
            }
        }
        return when (val request = ModePhrases.parse(text)) {
            is CommandResult.Mode.Run -> request.takeIf { store.find(it.name) != null }
            is CommandResult.Mode.Exit -> request.takeIf { store.find(it.name) != null }
            is CommandResult.Mode.Describe -> request.takeIf { store.find(it.name) != null }
            else -> request
        }
    }

    /** Carries out [request]; [runner] runs one ordinary command and returns its reply. */
    suspend fun handle(
        request: CommandResult.Mode,
        russian: Boolean,
        runner: suspend (CommandResult) -> String
    ): String {
        val say = Say(russian)
        return when (request) {
            is CommandResult.Mode.Create -> create(request.name, request.description, say)
            is CommandResult.Mode.Run -> store.find(request.name)?.let { run(it, say, runner) } ?: missing(request.name, say)
            is CommandResult.Mode.Exit -> store.find(request.name)?.let { exit(it, say, runner) } ?: missing(request.name, say)
            is CommandResult.Mode.Describe -> store.find(request.name)?.let { describe(it, say) } ?: missing(request.name, say)
            is CommandResult.Mode.Delete -> store.find(request.name)?.let { delete(it, say) } ?: missing(request.name, say)
            is CommandResult.Mode.CancelCreated -> cancel(request.id, say)
            CommandResult.Mode.ListAll -> list(say)
        }
    }

    private suspend fun create(name: String, description: String?, say: Say): String {
        if (description == null) {
            return say(
                "Что должно происходить в режиме $name? Скажите целиком, например: " +
                    "«создай режим $name: включи грустную музыку в Spotify и «Не беспокоить»».",
                "What should $name mode do? Say it in one go, e.g. \"create $name mode: play calm music and Do Not Disturb\"."
            )
        }
        return when (val compiled = compile(name, description)) {
            is ModeCompiler.Result.Failed -> if (compiled.reason == "no_key") {
                say("Чтобы разобрать описание режима, нужен ключ Groq в настройках.", "I need the Groq key in Settings to understand the mode.")
            } else {
                say(
                    "Не поняла, что делать в режиме $name. Опишите конкретнее: что включить, что выключить.",
                    "I couldn't work out what $name mode should do. Say it more concretely: what to turn on or off."
                )
            }
            is ModeCompiler.Result.Steps -> {
                val (mode, replaced) = store.save(name, description, compiled.steps)
                lastCreated = mode.id to clock()
                val what = ModeSteps.summary(mode.steps, say.russian)
                val skipped = if (compiled.skipped.isEmpty()) "" else say(
                    " Часть описания сделать не умею — её пропустила.",
                    " Part of it is beyond what I can do, so I left it out."
                )
                val head = if (replaced) say("Обновила режим $name", "Updated $name mode") else say("Режим $name", "$name mode")
                say(
                    "$head: $what.$skipped Скажите «режим $name», чтобы включить, или «отмена», если я поняла не так.",
                    "$head: $what.$skipped Say \"$name mode\" to start it, or \"cancel\" if I got it wrong."
                )
            }
        }
    }

    private suspend fun run(mode: Mode, say: Say, runner: suspend (CommandResult) -> String): String {
        val (messages, undo) = carryOut(mode.steps, say, runner)
        // Already on: the phone's state from before the first run is what "выключи" must restore.
        store.put(
            mode.copy(undo = mode.undo ?: undo, lastRunAt = clock(), runCount = mode.runCount + 1)
        )
        return say("Режим ${mode.name}. ", "${mode.name} mode. ") + sentences(messages)
    }

    private suspend fun exit(mode: Mode, say: Say, runner: suspend (CommandResult) -> String): String {
        val undo = mode.undo ?: return say("Режим ${mode.name} сейчас не включён.", "${mode.name} mode isn't on.")
        val (messages, _) = carryOut(undo, say, runner)
        store.put(mode.copy(undo = null))
        return if (messages.isEmpty()) {
            say("Режим ${mode.name} выключен — возвращать было нечего.", "${mode.name} mode is off — nothing to put back.")
        } else {
            say("Режим ${mode.name} выключен. ", "${mode.name} mode is off. ") + sentences(messages)
        }
    }

    /** Runs [steps] in order; returns what each said and what undoes them, newest first. */
    private suspend fun carryOut(
        steps: List<ActionEnvelope>,
        say: Say,
        runner: suspend (CommandResult) -> String
    ): Pair<List<String>, List<ActionEnvelope>> {
        val messages = mutableListOf<String>()
        val undo = ArrayDeque<ActionEnvelope>()
        steps.forEach { step ->
            when (val c = (AgentTools.interpret(step.tool, step.args, now()) as? AgentTools.Call.Command)?.command) {
                null -> messages += say("Шаг «${ModeSteps.describe(step, say.russian)}» не выполнить", "Couldn't do \"${ModeSteps.describe(step, say.russian)}\"")
                is CommandResult.DeviceControl -> {
                    val result = device.apply(c.action, c.level, say.russian)
                    messages += result.message
                    result.undo?.let { undo.addFirst(ModeSteps.deviceEnvelope(it.action, it.level)) }
                }
                else -> {
                    val reply = runner(c)
                    messages += reply
                    if (!CommandExecutor.isFailure(reply)) ModeSteps.undoOf(step)?.let(undo::addFirst)
                }
            }
        }
        return messages to undo.toList()
    }

    private fun describe(mode: Mode, say: Say): String {
        val state = if (mode.active) say("Сейчас включён.", "It's on now.") else say("Сейчас выключен.", "It's off now.")
        return say("Режим ${mode.name}: ", "${mode.name} mode: ") + ModeSteps.summary(mode.steps, say.russian) + ". " + state
    }

    private suspend fun delete(mode: Mode, say: Say): String {
        store.delete(mode.id)
        val note = if (mode.active) {
            say(" Настройки, которые он менял, оставила как есть.", " The settings it changed stay as they are.")
        } else {
            ""
        }
        return say("Удалила режим ${mode.name}.", "Deleted ${mode.name} mode.") + note
    }

    private suspend fun cancel(id: String, say: Say): String {
        val mode = store.byId(id) ?: return say("Отменять нечего.", "Nothing to cancel.")
        store.delete(id)
        return say(
            "Убрала режим ${mode.name}. Опишите его ещё раз, по-другому.",
            "Removed ${mode.name} mode. Describe it again, differently."
        )
    }

    private fun list(say: Say): String {
        val modes = store.all()
        if (modes.isEmpty()) {
            return say(
                "Режимов пока нет. Скажите, например: «создай режим отдыха: беззвучный режим и яркость на минимум».",
                "No modes yet. Say, for example: \"create rest mode: silent ringer and lowest brightness\"."
            )
        }
        val names = modes.joinToString { it.name + if (it.active) say(" (включён)", " (on)") else "" }
        return say("Ваши режимы: $names.", "Your modes: $names.")
    }

    private fun missing(name: String, say: Say) = say(
        "Режима «$name» у меня нет. Скажите «создай режим $name» и что в нём делать.",
        "There's no \"$name\" mode. Say \"create $name mode\" and what it should do."
    )

    /** Joined so it reads as one spoken answer: each reply a sentence. */
    private fun sentences(messages: List<String>) =
        messages.filter { it.isNotBlank() }.joinToString(" ") { m -> m.trim().let { if (it.last() in ".!?…") it else "$it." } }

    private class Say(val russian: Boolean) {
        operator fun invoke(ru: String, en: String) = if (russian) ru else en
    }
}
