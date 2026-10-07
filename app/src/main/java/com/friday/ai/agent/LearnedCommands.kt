package com.friday.ai.agent

import android.util.Log
import com.friday.ai.core.SpokenText
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import com.friday.ai.data.remote.groqJson
import com.friday.ai.domain.model.CommandResult
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Phrases the model has worked out once, carried out directly from then on.
 *
 * "Закинь будильник на полвосьмого" matches no pattern; the model turns it
 * into set_alarm(7, 30). Remembered, the same words next time set the alarm
 * at once — no round-trip, no tokens. The command layer grows to fit the way
 * the user talks.
 *
 * What is stored is the tool call, not its result: it is interpreted afresh
 * each time, so "будильник на завтра в 8" still works out which morning
 * that is. Learning is cautious — see [worthLearning] — and undone when the
 * user's next words correct it.
 */
class LearnedCommands(
    private val prefs: UserPreferenceDao,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Whether a tool works right now. A phrase learned while Gmail was
     * connected must not run once it isn't — it falls through to the model,
     * which knows why.
     */
    private val available: (tool: String) -> Boolean = { true }
) {

    companion object {
        private const val TAG = "LearnedCommands"
        private const val PREFIX = "learned:"

        /** A correction this soon after learning undoes it. */
        private const val UNDO_WINDOW_MS = 2 * 60 * 1000L

        /** Calls whose meaning does not depend on when or after what they were said. */
        private val STABLE_TOOLS = setOf(
            "set_alarm", "set_timer", "call", "flashlight", "phone_control", "media", "play", "open_settings",
            "camera", "voice_recorder", "open_app", "find_nearby", "read_messages", "weather", "briefing",
            // "Мне грустно" worked out as run_mode(грусти) once: the owner's own way of naming the mode.
            "run_mode"
        )

        /**
         * Words that lean on what was said before: "а теперь на восемь",
         * "включи его", "то же самое". Their meaning is not in the phrase.
         */
        private val CONTEXTUAL = Regex(
            "(?:^|\\s)(?:а|и|ну|тогда|теперь|тоже|ещё|еще|снова|опять|его|её|ее|их|это|этот|эту|эти|туда|там|" +
                "так|такой|тот|ту|same|it|that|this|them|there|again|too|also|then)(?:\\s|$)"
        )

        fun key(text: String): String = SpokenText.normalise(text)

        /**
         * Whether a phrase and the call made for it are safe to repeat blindly.
         * A date is relative to the day it was said ("в пятницу"), so an alarm
         * that carries one is not stored.
         */
        fun worthLearning(text: String, tool: String, args: JsonObject): Boolean =
            tool in STABLE_TOOLS &&
                !(tool == "set_alarm" && !(args["date"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) &&
                !CONTEXTUAL.containsMatchIn(key(text)) &&
                key(text).split(' ').size >= 2

        /** "Нет", "не то", "неправильно": the last thing done was not what was meant. */
        private val CORRECTION = Regex(
            "^(?:нет|не то|не так|неправильно|не это|я не это|я не так|отмена|отмени|" +
                "wrong|no|not that|that's not)(?:\\s|$)"
        )

        fun isCorrection(text: String): Boolean = CORRECTION.containsMatchIn(key(text))
    }

    private val shortcuts = ConcurrentHashMap<String, ActionEnvelope>()

    @Volatile
    private var lastLearned: Pair<String, Long>? = null

    /** Reads what was learned before; until it finishes, lookups simply find nothing. */
    fun load() {
        scope.launch {
            runCatching { prefs.withPrefix(PREFIX) }
                .onSuccess { rows ->
                    rows.forEach { row ->
                        val stored = ActionSchema.parse(row.value)
                        if (stored == null) {
                            // Written by a version whose meaning can't be carried over: dropped, not guessed.
                            Log.w(TAG, "Dropping \"${row.key.removePrefix(PREFIX)}\": can't be read in this version")
                        } else {
                            shortcuts[row.key.removePrefix(PREFIX)] = stored
                        }
                    }
                }
                .onFailure { Log.w(TAG, "Could not load: ${it.message}") }
        }
    }

    /** The command learned for [text], interpreted for [now]; null if none. */
    fun command(text: String, now: LocalDateTime): CommandResult? {
        val s = shortcuts[key(text)]?.takeIf { available(it.tool) } ?: return null
        return (AgentTools.interpret(s.tool, s.args, now) as? AgentTools.Call.Command)?.command
    }

    fun learn(text: String, tool: String, arguments: String) {
        val args = runCatching { groqJson.parseToJsonElement(arguments).jsonObject }.getOrNull() ?: return
        if (!worthLearning(text, tool, args)) return
        val k = key(text)
        val envelope = ActionEnvelope(tool, args)
        shortcuts[k] = envelope
        lastLearned = k to clock()
        Log.i(TAG, "Learned \"$k\" -> $tool$args")
        scope.launch { prefs.set(UserPreferenceEntity(PREFIX + k, envelope.toJson())) }
    }

    /**
     * Called with each new thing the user says. If it corrects what was just
     * learned, the lesson is dropped. Returns the phrase that was forgotten.
     */
    fun noteReply(text: String): String? {
        val (k, at) = lastLearned ?: return null
        if (clock() - at > UNDO_WINDOW_MS) {
            lastLearned = null
            return null
        }
        lastLearned = null
        if (!isCorrection(text)) return null
        forget(k)
        return k
    }

    fun all(): Map<String, ActionEnvelope> = shortcuts.toMap()

    fun forget(phrase: String) {
        shortcuts.remove(phrase)
        scope.launch { prefs.delete(PREFIX + phrase) }
    }

    fun forgetAll() {
        shortcuts.clear()
        scope.launch { prefs.deleteWithPrefix(PREFIX) }
    }
}
