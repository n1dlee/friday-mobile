package com.friday.ai.core

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The shape of the "what Friday knows about me" map.
 *
 * You at the centre, what you've told it radiating out, and dates branching
 * into the sessions held on them and the notes inside those. Kept free of
 * Compose so the structure and the layout maths can be tested — a graph that
 * silently overlaps itself is hard to notice by eye but easy to assert on.
 */
object LazuriGraph {

    enum class NodeKind { ROOT, CATEGORY, FACT, DATE, SESSION, NOTE }

    data class Node(
        val id: String,
        val label: String,
        val kind: NodeKind,
        val detail: String? = null,
        val children: List<Node> = emptyList()
    ) {
        val hasChildren: Boolean get() = children.isNotEmpty()
    }

    data class Positioned(
        val node: Node,
        val x: Float,
        val y: Float,
        val radius: Float,
        val parentX: Float,
        val parentY: Float
    )

    /** A fact Friday has learned, as stored in memory. */
    data class Fact(val category: String, val key: String, val value: String)

    /** A past conversation, with anything worth showing inside it. */
    data class Session(
        val id: String,
        val title: String,
        val dayLabel: String,
        val startedAt: Long,
        val summary: String? = null,
        val notes: List<String> = emptyList()
    )

    /**
     * Builds the tree. [centreLabel] is the user's name when Friday has
     * learned it, otherwise a neutral placeholder — the map should still be
     * useful on day one.
     */
    fun build(centreLabel: String, facts: List<Fact>, sessions: List<Session>): Node {
        val categoryNodes = facts
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .groupBy { it.category.ifBlank { "other" } }
            .toSortedMap()
            .map { (category, items) ->
                Node(
                    id = "cat:$category",
                    label = prettyCategory(category),
                    kind = NodeKind.CATEGORY,
                    detail = "${items.size}",
                    children = items.map { fact ->
                        Node(
                            id = "fact:$category:${fact.key}",
                            label = prettyKey(fact.key),
                            kind = NodeKind.FACT,
                            detail = fact.value
                        )
                    }
                )
            }

        val dateNodes = sessions
            .groupBy { it.dayLabel }
            .entries
            // Newest day first — recent context is what keeps you on track.
            .sortedByDescending { entry -> entry.value.maxOf { it.startedAt } }
            .map { (day, daySessions) ->
                Node(
                    id = "date:$day",
                    label = day,
                    kind = NodeKind.DATE,
                    detail = "${daySessions.size}",
                    children = daySessions
                        .sortedByDescending { it.startedAt }
                        .map { session -> sessionNode(session) }
                )
            }

        val topLevel = buildList {
            addAll(categoryNodes)
            if (dateNodes.isNotEmpty()) {
                add(
                    Node(
                        id = "dates",
                        label = "Dates",
                        kind = NodeKind.DATE,
                        detail = "${sessions.size}",
                        children = dateNodes
                    )
                )
            }
        }

        return Node(
            id = "root",
            label = centreLabel,
            kind = NodeKind.ROOT,
            children = topLevel
        )
    }

    private fun sessionNode(session: Session): Node {
        val children = buildList {
            session.summary?.takeIf { it.isNotBlank() }?.let {
                add(Node("sum:${session.id}", "Summary", NodeKind.NOTE, it))
            }
            session.notes.forEachIndexed { i, note ->
                add(Node("note:${session.id}:$i", "Note", NodeKind.NOTE, note))
            }
        }
        return Node(
            id = "session:${session.id}",
            label = session.title.ifBlank { "Conversation" },
            kind = NodeKind.SESSION,
            detail = session.summary,
            children = children
        )
    }

    /**
     * Places [children] evenly on a circle around a parent.
     *
     * [startAngle] lets a branch fan out away from the centre instead of
     * wrapping back over it, which is what makes an expanded node readable.
     */
    fun layoutRing(
        children: List<Node>,
        centreX: Float,
        centreY: Float,
        ringRadius: Float,
        nodeRadius: Float,
        startAngle: Float = -Math.PI.toFloat() / 2f,
        sweep: Float = (2 * Math.PI).toFloat()
    ): List<Positioned> {
        if (children.isEmpty()) return emptyList()

        // A full circle needs one gap fewer than an arc, or the first and last
        // node land on top of each other.
        val isFullCircle = sweep >= (2 * Math.PI).toFloat() - 0.001f
        val divisor = if (isFullCircle) children.size else maxOf(children.size - 1, 1)

        return children.mapIndexed { index, child ->
            val angle = if (children.size == 1 && !isFullCircle) {
                startAngle + sweep / 2f
            } else {
                startAngle + sweep * index / divisor
            }
            Positioned(
                node = child,
                x = centreX + ringRadius * cos(angle),
                y = centreY + ringRadius * sin(angle),
                radius = nodeRadius,
                parentX = centreX,
                parentY = centreY
            )
        }
    }

    /** Ring radius that keeps [count] nodes from touching each other. */
    fun ringRadiusFor(count: Int, nodeRadius: Float, minRadius: Float): Float {
        if (count <= 1) return minRadius
        // Circumference must fit every node plus a gap of the same size.
        val needed = (count * nodeRadius * 4f) / (2 * Math.PI).toFloat()
        return maxOf(minRadius, needed)
    }

    /** Scales a node down as it gets deeper, so the centre stays dominant. */
    fun radiusForDepth(baseRadius: Float, depth: Int): Float =
        baseRadius * when (depth) {
            0 -> 1f
            1 -> 0.62f
            2 -> 0.48f
            else -> 0.40f
        }

    private fun prettyCategory(category: String): String = when (category.lowercase()) {
        "preference" -> "Preferences"
        "fact" -> "About me"
        "habit" -> "Habits"
        "music" -> "Music"
        "contact" -> "People"
        "interest" -> "Interests"
        else -> category.replaceFirstChar { it.uppercase() }
    }

    private fun prettyKey(key: String): String =
        key.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

    /** Keeps a value short enough to sit in a bubble. */
    fun truncate(text: String, max: Int = 40): String =
        if (text.length <= max) text else text.take(min(max - 1, text.length)).trimEnd() + "…"
}
