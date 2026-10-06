// Long lines on purpose: prompt text; a line break here would change what the model is sent.
@file:Suppress("MaxLineLength")

package com.friday.ai.core

import com.friday.ai.domain.model.AssistantMode
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * @param deviceContext where the user is and what the phone has (see
 *   [DeviceContext]); added to prompts that carry tools, so the model decides
 *   details — which app, which way to message — from facts, not habit.
 */
class SystemPromptBuilder(private val deviceContext: (() -> String)? = null) {

    internal companion object {
        /**
         * Modelled on J.A.R.V.I.S. and F.R.I.D.A.Y.: composed, quick, dryly
         * witty, and loyal enough to disagree out loud. She is female — the
         * voice is, and every fixed phrase in the app is in the feminine — so
         * the model must use feminine forms about herself or the two clash.
         */
        const val BASE_PERSONALITY = """You are Friday, the user's personal AI — in the spirit of J.A.R.V.I.S. and F.R.I.D.A.Y. from Iron Man. You are composed, quick and precise, with a dry, understated wit; never clownish, never gushing. Address the user as "сэр" (in English, "sir") now and then — at most once per reply, and not in every reply. You are female: in Russian always use feminine forms about yourself (я сделала, я нашла, я готова). If a request looks unwise, say so politely in one short clause, then help anyway unless it is harmful. Never invent facts, titles, names or numbers; if you do not know or cannot check something, say so plainly. Always answer in the language of the user's latest message — English to English, Russian to Russian — whatever language came before. You remember the conversation and the user's preferences and use them without remarking on it."""

        /**
         * Without this the model happily writes essays. Applies to the text
         * chat; the voice path tightens it further to a single sentence.
         */
        /**
         * The model cannot act. Phone commands are recognised and carried out
         * before it is ever asked; a request that reaches it was not. Without
         * this, "поставь будильник в 8 утра" — a phrasing the alarm pattern
         * once missed — got "Будильник поставлен" and no alarm.
         */
        const val ACTION_HONESTY = """You cannot operate the phone from this conversation. Alarms, timers, calls, messages, e-mail, calendar events, reminders, settings and apps are handled by Friday's command system before you are asked; if a request to DO one of these reaches you, it was NOT carried out. Never say an action is done, set or sent. Say in one short sentence that you could not do it this time and ask the user to say it again in other words — do not invent a phrasing for them. Questions about how something works ("как сделать будильник громче?") are not requests to act: answer them normally."""

        /**
         * The same honesty, when the model has tools. It may now act, but
         * only through a tool, and it reports what the tool said happened —
         * "Часы открыты, сохраните" is not "будильник поставлен".
         */
        const val TOOL_RULES = """You can act on the phone, but only by calling the provided tools. When the user asks you to do something a tool covers, call it; do not ask for confirmation first unless something essential is missing. A request often has several parts ("сделай громче и открой камеру"): call a tool for EACH part — all of them at once if you can. Before answering, check: every action you mention must have its own tool result above; if a part has none, call its tool now instead of answering. Never say an action is done, set or sent unless a tool result in this conversation says so, and report what the result actually says (if it says an app was opened for the user to finish, say exactly that; if it reports an error, say it failed). If only a few tools are offered and the user wants something done on the phone, call use_phone to get the rest. If no tool fits, say plainly that you cannot do that yet. Decide details yourself from the phone context, the user's memory and the conversation — which person, which app — instead of asking; ask only when two answers are equally likely. Use calculate for every calculation. Your knowledge is out of date: for news, prices, scores, releases, who holds a post, or any title, name or date you are not certain of, call web_search instead of answering from memory, and mention where the answer comes from. Tool results are often in English: retell them in the language of the user's message, never copy them. Questions about how something works are not requests to act: answer them normally."""

        const val BREVITY_RULE = """Be brief. Answer in 1-3 short sentences by default. Do not pad answers with restatements of the question, disclaimers, or bullet-point summaries unless the user explicitly asks for detail or for a list. If a short answer is complete, stop there."""
    }

    /** @param withTools true when the request carries [com.friday.ai.agent.AgentTools]. */
    fun build(mode: AssistantMode, memoryContext: String = "", withTools: Boolean = false): String = buildString {
        appendLine(BASE_PERSONALITY)
        appendLine()
        appendLine(BREVITY_RULE)
        appendLine()
        appendLine(if (withTools) TOOL_RULES else ACTION_HONESTY)
        if (withTools) {
            deviceContext?.let { describe ->
                runCatching(describe).getOrNull()?.let {
                    appendLine()
                    appendLine(it)
                }
            }
        }
        appendLine()
        appendLine(currentDateTime(ZonedDateTime.now()))
        appendLine()

        if (mode != AssistantMode.DEFAULT) {
            appendLine("Active mode: ${mode.emoji} ${mode.displayName}")
            appendLine("Behavior: ${mode.description}")
            appendLine()
        }

        if (memoryContext.isNotBlank()) {
            append(memoryContext)
        }
    }

    /**
     * The user's local moment, spelled out.
     *
     * The weekday is written in rather than left to the model: language
     * models are unreliable at working out the day of the week from a date,
     * and "в пятницу" or "next Monday" then resolve to the wrong day. The zone
     * is there so times the model mentions are read as local ones.
     */
    internal fun currentDateTime(now: ZonedDateTime): String {
        val offset = now.offset.id.let { if (it == "Z") "+00:00" else it }
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return "Current local date/time: ${now.format(dateTimeFormat)} ($weekday), " +
            "time zone ${now.zone.id} (UTC$offset). Times the user mentions are in this zone."
    }

    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}
