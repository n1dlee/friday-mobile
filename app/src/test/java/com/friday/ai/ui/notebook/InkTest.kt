package com.friday.ai.ui.notebook

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class InkTest {

    private val line = InkStroke(listOf(InkPoint(10f, 10f), InkPoint(100f, 10f), InkPoint(100f, 60f)))

    @Test
    fun `the eraser hits a stroke it passes over`() {
        assertTrue(line.touches(100f, 55f, radius = 10f))
        assertFalse(line.touches(50f, 50f, radius = 10f))
    }

    @Test
    fun `the inbox hands a page over once`() {
        val inbox = NotebookInbox()
        inbox.put(NotebookInbox.Page("img", "реши"))
        assertEquals("реши", inbox.take()?.question)
        assertNull(inbox.take())
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class InkRendererTest {

    @Test
    fun `dark ink on white, cropped to the writing with a margin`() {
        val stroke = InkStroke(listOf(InkPoint(500f, 500f, 1f), InkPoint(700f, 500f, 1f)))
        val bitmap = InkRenderer.render(listOf(stroke))
        assertNotNull(bitmap)
        bitmap!!
        // 200 wide plus a margin on each side; not the whole 700-pixel canvas.
        assertTrue("width ${bitmap.width}", bitmap.width in 250..280)
        assertEquals(Color.WHITE, bitmap.getPixel(2, 2))
        val ink = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        assertTrue("ink should be dark, was ${Integer.toHexString(ink)}", Color.red(ink) < 0x80)
    }

    @Test
    fun `a huge page is scaled down`() {
        val stroke = InkStroke(listOf(InkPoint(0f, 0f), InkPoint(5000f, 2000f)))
        val bitmap = InkRenderer.render(listOf(stroke))!!
        assertTrue(maxOf(bitmap.width, bitmap.height) <= 1280)
    }

    @Test
    fun `bounds cover every stroke`() {
        val other = InkStroke(listOf(InkPoint(-5f, 200f)))
        val box = InkRenderer.bounds(listOf(InkStroke(listOf(InkPoint(10f, 10f), InkPoint(100f, 60f))), other))!!
        assertEquals(listOf(-5f, 10f, 100f, 200f), listOf(box.left, box.top, box.right, box.bottom))
        assertNull(InkRenderer.bounds(emptyList()))
    }

    @Test
    fun `nothing written, nothing to send`() {
        assertNull(InkRenderer.render(emptyList()))
    }
}
