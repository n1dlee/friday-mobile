package com.friday.ai.data.remote

import com.friday.ai.data.remote.dto.FunctionCall
import com.friday.ai.data.remote.dto.ToolCall
import com.friday.ai.data.remote.dto.ToolCallDelta

/** What a streamed reply is made of: words to show or say, and requests to use tools. */
sealed interface ChatEvent {
    data class Text(val token: String) : ChatEvent

    /** Every tool the model asked for in this reply, complete. Comes once, after the text. */
    data class ToolCalls(val calls: List<ToolCall>) : ChatEvent
}

/**
 * Puts tool calls back together from a stream.
 *
 * A call arrives in pieces: the first carries its id and name, later ones
 * add to the arguments string. Pieces are matched by `index`, since several
 * calls can be in flight in one reply.
 */
class ToolCallAssembler {

    private class Partial(var id: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder())

    private val partials = sortedMapOf<Int, Partial>()

    fun add(deltas: List<ToolCallDelta>) {
        deltas.forEach { d ->
            val p = partials.getOrPut(d.index) { Partial() }
            d.id?.let { p.id = it }
            d.function?.name?.let { p.name += it }
            d.function?.arguments?.let { p.arguments.append(it) }
        }
    }

    /** The finished calls; ones that never got a name are dropped as unusable. */
    fun calls(): List<ToolCall> = partials.entries
        .filter { it.value.name.isNotBlank() }
        .map { (index, p) ->
            ToolCall(
                id = p.id.ifBlank { "call_$index" },
                function = FunctionCall(p.name, p.arguments.toString().ifBlank { "{}" })
            )
        }
}
