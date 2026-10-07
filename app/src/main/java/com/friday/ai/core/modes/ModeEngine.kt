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
        const val MAX_NAME_WORDS = 4

        /** At least half of "убери …"'s words must be in a step for it to be that step. */
        const val MIN_STEP_MATCH = 0.5
    }

    @Volatile
    private var lastCreated: Pair<String, Long>? = null

    /** The mode that just ran, for "нет, включи другое". */
    @Volatile
    private var lastRun: Pair<String, Long>? = null

    /** A correction waiting for "да": put [step] into the mode, in place of [replaces] if set. */
    private data class Proposal(val modeId: String, val step: ActionEnvelope, val replaces: Int?, val at: Long)

    @Volatile
    private var proposal: Proposal? = null

    private val correctionLead = Regex(
        "^(?:нет|не то|не так|неправильно|не это|я не это|no|not that|wrong)[,.!\\s]+(?:а\\s+|лучше\\s+|instead\\s+)?"
    )

    /**
     * The mode request in [text], or null. Run, exit and describe only count
     * when the name is one of the owner's modes: "включи режим полёта" is a
     * system setting, not a mode Friday doesn't have.
     */
    fun route(text: String): CommandResult.Mode? {
        correction(text)?.let { return it }
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
            is CommandResult.Mode.AddTo -> request.takeIf { split(it.rest) != null }
            is CommandResult.Mode.RemoveFrom -> request.takeIf { store.find(it.name) != null }
            else -> request
        }
    }

    /** "Нет, включи lo-fi" within the window after a run: the part after "нет" is the wish. */
    private fun correction(text: String): CommandResult.Mode.Correct? {
        val (id, at) = lastRun ?: return null
        if (clock() - at > UNDO_WINDOW_MS) return null
        val lower = text.trim().lowercase().replace('ё', 'е')
        val lead = correctionLead.find(lower) ?: return null
        val instead = text.trim().substring(lead.range.last + 1).trim()
        return CommandResult.Mode.Correct(id, instead).takeIf { instead.split(' ').size >= 2 }
    }

    /** "Да" / "нет" to "запомнить это для режима …?"; null when nothing is asked or it isn't an answer. */
    suspend fun answerPending(text: String, russian: Boolean): String? {
        val p = proposal?.takeIf { clock() - it.at <= UNDO_WINDOW_MS } ?: return null
        val say = Say(russian)
        return when (com.friday.ai.core.mail.Confirmation.classify(text)) {
            com.friday.ai.core.mail.Confirmation.Answer.OTHER -> null
            com.friday.ai.core.mail.Confirmation.Answer.NO -> {
                proposal = null
                say("Хорошо, режим остаётся как был.", "OK, the mode stays as it was.")
            }
            com.friday.ai.core.mail.Confirmation.Answer.YES -> {
                proposal = null
                val mode = store.byId(p.modeId) ?: return say("Этого режима уже нет.", "That mode is gone.")
                val steps = mode.steps.toMutableList()
                if (p.replaces != null && p.replaces in steps.indices) steps[p.replaces] = p.step else steps += p.step
                store.put(mode.copy(steps = steps))
                say(
                    "Запомнила. Режим ${mode.name}: ${ModeSteps.summary(steps, russian)}.",
                    "Got it. ${mode.name} mode: ${ModeSteps.summary(steps, russian)}."
                )
            }
        }
    }

    /** Carries out [request]; [runner] runs one ordinary command, [router] reads a phrase into one. */
    suspend fun handle(
        request: CommandResult.Mode,
        russian: Boolean,
        router: (String) -> CommandResult = { CommandResult.ChatMessage(it) },
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
            is CommandResult.Mode.Correct -> correct(request, say, router, runner)
            is CommandResult.Mode.AddTo -> add(request.rest, say)
            is CommandResult.Mode.RemoveFrom -> store.find(request.name)?.let { remove(it, request.what, say) }
                ?: missing(request.name, say)
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
        lastRun = mode.id to clock()
        // Already on: the phone's state from before the first run is what "выключи" must restore.
        store.put(
            mode.copy(undo = mode.undo ?: undo, lastRunAt = clock(), runCount = mode.runCount + 1)
        )
        return say("Режим ${mode.name}. ", "${mode.name} mode. ") + sentences(messages)
    }

    /**
     * Does what the owner asked instead, then offers to keep it: in place of
     * the step of the same kind ("lo-fi" instead of "грустные песни"), or as
     * a new step.
     */
    private suspend fun correct(
        request: CommandResult.Mode.Correct,
        say: Say,
        router: (String) -> CommandResult,
        runner: suspend (CommandResult) -> String
    ): String {
        lastRun = null
        val mode = store.byId(request.id) ?: return say("Этого режима уже нет.", "That mode is gone.")
        val command = router(request.instead)
        if (command is CommandResult.ChatMessage || command is CommandResult.Mode) {
            return say(
                "Скажите, что сделать вместо этого, например «нет, включи lo-fi».",
                "Tell me what to do instead, e.g. \"no, play lo-fi\"."
            )
        }
        val reply = when (command) {
            is CommandResult.DeviceControl -> device.apply(command.action, command.level, say.russian).message
            else -> runner(command)
        }
        return offer(mode, command, reply, say)
    }

    /** After a correction was carried out: the question whether to keep it in [mode]. */
    private fun offer(mode: Mode, command: CommandResult, reply: String, say: Say): String {
        val step = ModeSteps.envelopeOf(command)
        if (step == null || CommandExecutor.isFailure(reply)) return reply
        val replaces = mode.steps.indexOfFirst { ModeSteps.sameKind(it, step) }.takeIf { it >= 0 }
        proposal = Proposal(mode.id, step, replaces, clock())
        val question = if (replaces != null) {
            val old = ModeSteps.describe(mode.steps[replaces], say.russian)
            say(
                " Запомнить это для режима ${mode.name} вместо «$old»?",
                " Keep this in ${mode.name} mode instead of \"$old\"?"
            )
        } else {
            say(" Добавить это в режим ${mode.name}?", " Add this to ${mode.name} mode?")
        }
        return reply.trim().let { if (it.last() in ".!?…") it else "$it." } + question
    }

    /** "грусти тёплый свет" → (режим грусти, "тёплый свет"): the longest saved name at the start. */
    private fun split(rest: String): Pair<Mode, String>? {
        val words = rest.trim().split(' ').filter { it.isNotBlank() }
        for (k in minOf(MAX_NAME_WORDS, words.size - 1) downTo 1) {
            // Exact: with a spare word allowed, "отдыха будильник" would pass for "отдыха".
            val mode = store.find(words.take(k).joinToString(" "), spare = 0) ?: continue
            return mode to words.drop(k).joinToString(" ")
        }
        return null
    }

    private suspend fun add(rest: String, say: Say): String {
        val (mode, what) = split(rest) ?: return say("Не поняла, в какой режим добавить.", "Which mode should I add it to?")
        return when (val compiled = compile(mode.name, what)) {
            is ModeCompiler.Result.Failed -> say(
                "Не поняла, что добавить в режим ${mode.name}.", "I couldn't work out what to add to ${mode.name} mode."
            )
            is ModeCompiler.Result.Steps -> {
                val steps = mode.steps + compiled.steps
                store.put(mode.copy(steps = steps, description = mode.description + "; " + what))
                say(
                    "Добавила в режим ${mode.name}: ${ModeSteps.summary(compiled.steps, say.russian)}. Теперь в нём: ${ModeSteps.summary(steps, say.russian)}.",
                    "Added to ${mode.name} mode: ${ModeSteps.summary(compiled.steps, say.russian)}. Now it does: ${ModeSteps.summary(steps, say.russian)}."
                )
            }
        }
    }

    /** The step [what] sounds most like, by its words; removed if it's a clear match. */
    private suspend fun remove(mode: Mode, what: String, say: Say): String {
        val scored = mode.steps.mapIndexed { i, step ->
            i to ModeNames.overlap(what, ModeSteps.describe(step, true) + " " + ModeSteps.describe(step, false) + " " + step.toJson())
        }
        val best = scored.maxByOrNull { it.second }?.takeIf { it.second >= MIN_STEP_MATCH }
            ?: return say(
                "В режиме ${mode.name} такого нет. В нём: ${ModeSteps.summary(mode.steps, say.russian)}.",
                "${mode.name} mode has no such step. It does: ${ModeSteps.summary(mode.steps, say.russian)}."
            )
        val steps = mode.steps.filterIndexed { i, _ -> i != best.first }
        store.put(mode.copy(steps = steps))
        val removed = ModeSteps.describe(mode.steps[best.first], say.russian)
        val left = if (steps.isEmpty()) say("В режиме больше ничего нет.", "Nothing is left in it.")
        else say("Осталось: ${ModeSteps.summary(steps, say.russian)}.", "Left: ${ModeSteps.summary(steps, say.russian)}.")
        return say("Убрала из режима ${mode.name}: $removed. ", "Removed from ${mode.name} mode: $removed. ") + left
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
