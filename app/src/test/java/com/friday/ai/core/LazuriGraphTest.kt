package com.friday.ai.core

import com.friday.ai.core.LazuriGraph.Fact
import com.friday.ai.core.LazuriGraph.NodeKind
import com.friday.ai.core.LazuriGraph.Session
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LazuriGraphTest {

    private val facts = listOf(
        Fact("fact", "name", "Timur"),
        Fact("fact", "birthday", "12 March"),
        Fact("interest", "hobby", "programming"),
        Fact("music", "favorite_genre", "hip-hop")
    )

    private val sessions = listOf(
        Session("s1", "Как ты?", "Today", 3_000, summary = "Small talk", notes = listOf("idea A")),
        Session("s2", "hello my friend!", "Today", 2_000),
        Session("s3", "Weather chat", "Yesterday", 1_000)
    )

    @Test
    fun `the user sits at the centre`() {
        val root = LazuriGraph.build("Timur", facts, sessions)
        assertEquals(NodeKind.ROOT, root.kind)
        assertEquals("Timur", root.label)
    }

    @Test
    fun `facts are grouped into categories`() {
        val root = LazuriGraph.build("Me", facts, sessions)
        val categories = root.children.filter { it.kind == NodeKind.CATEGORY }
        assertEquals(3, categories.size)
        assertTrue(categories.any { it.label == "About me" })
        assertTrue(categories.any { it.label == "Interests" })
        assertTrue(categories.any { it.label == "Music" })
    }

    @Test
    fun `a category holds its own facts with their values`() {
        val root = LazuriGraph.build("Me", facts, sessions)
        val about = root.children.first { it.label == "About me" }
        assertEquals(2, about.children.size)
        assertTrue(about.children.any { it.detail == "Timur" })
        assertTrue(about.children.any { it.detail == "12 March" })
    }

    @Test
    fun `dates branch into sessions and sessions into notes`() {
        val root = LazuriGraph.build("Me", facts, sessions)
        val dates = root.children.first { it.id == "dates" }

        val today = dates.children.first { it.label == "Today" }
        assertEquals(2, today.children.size)

        val withNotes = today.children.first { it.label == "Как ты?" }
        // Summary plus the one note.
        assertEquals(2, withNotes.children.size)
        assertTrue(withNotes.children.any { it.detail == "Small talk" })
        assertTrue(withNotes.children.any { it.detail == "idea A" })
    }

    @Test
    fun `most recent day comes first`() {
        val root = LazuriGraph.build("Me", facts, sessions)
        val dates = root.children.first { it.id == "dates" }
        assertEquals("Today", dates.children.first().label)
    }

    @Test
    fun `an empty history still produces a usable map`() {
        val root = LazuriGraph.build("Me", emptyList(), emptyList())
        assertEquals(NodeKind.ROOT, root.kind)
        assertTrue("no branches expected", root.children.isEmpty())
    }

    @Test
    fun `facts with no value are dropped rather than shown blank`() {
        val root = LazuriGraph.build("Me", listOf(Fact("fact", "name", "  ")), emptyList())
        assertTrue(root.children.isEmpty())
    }

    // --- layout ---------------------------------------------------------

    @Test
    fun `a full ring spaces nodes without overlapping the first and last`() {
        val nodes = LazuriGraph.build("Me", facts, sessions).children
        val placed = LazuriGraph.layoutRing(nodes, 100f, 100f, 60f, 10f)
        assertEquals(nodes.size, placed.size)

        // Every pair must be at least a node apart.
        for (i in placed.indices) for (j in i + 1 until placed.size) {
            val d = hypot(placed[i].x - placed[j].x, placed[i].y - placed[j].y)
            assertTrue("nodes $i and $j overlap (distance $d)", d > 10f)
        }
    }

    @Test
    fun `every node sits on the ring it was given`() {
        val nodes = LazuriGraph.build("Me", facts, sessions).children
        LazuriGraph.layoutRing(nodes, 50f, 70f, 40f, 8f).forEach {
            val d = hypot(it.x - 50f, it.y - 70f)
            assertEquals(40f, d, 0.01f)
        }
    }

    @Test
    fun `a single child on an arc is centred in it`() {
        val one = LazuriGraph.build("Me", listOf(facts[0]), emptyList()).children
        val placed = LazuriGraph.layoutRing(
            one, 0f, 0f, 10f, 2f,
            startAngle = 0f, sweep = (Math.PI / 2).toFloat()
        )
        // Centre of a quarter turn is 45°, so x and y should match.
        assertEquals(placed[0].x, placed[0].y, 0.01f)
    }

    @Test
    fun `an empty ring places nothing`() {
        assertTrue(LazuriGraph.layoutRing(emptyList(), 0f, 0f, 10f, 2f).isEmpty())
    }

    @Test
    fun `ring grows when there are more nodes to fit`() {
        val small = LazuriGraph.ringRadiusFor(3, nodeRadius = 10f, minRadius = 50f)
        val large = LazuriGraph.ringRadiusFor(30, nodeRadius = 10f, minRadius = 50f)
        assertTrue("more nodes need a wider ring", large > small)
        assertEquals("few nodes keep the minimum", 50f, small, 0.01f)
    }

    @Test
    fun `nodes shrink with depth so the centre stays dominant`() {
        val r0 = LazuriGraph.radiusForDepth(40f, 0)
        val r1 = LazuriGraph.radiusForDepth(40f, 1)
        val r2 = LazuriGraph.radiusForDepth(40f, 2)
        assertTrue(r0 > r1 && r1 > r2)
    }

    @Test
    fun `long values are shortened for the bubble`() {
        val long = "a".repeat(120)
        val short = LazuriGraph.truncate(long, 40)
        assertTrue(short.length <= 40)
        assertTrue(short.endsWith("…"))
        assertEquals("short text", LazuriGraph.truncate("short text", 40))
    }
}
