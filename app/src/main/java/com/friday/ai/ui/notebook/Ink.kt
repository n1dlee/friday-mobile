package com.friday.ai.ui.notebook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One sample of a pen or finger: where, and how hard (1 when the device can't tell). */
data class InkPoint(val x: Float, val y: Float, val pressure: Float = 1f)

/** One continuous line, pen down to pen up. */
data class InkStroke(val points: List<InkPoint>) {

    /** Whether the stroke passes within [radius] of ([x], [y]): what the pen's eraser end hits. */
    fun touches(x: Float, y: Float, radius: Float): Boolean = points.any { hypot(it.x - x, it.y - y) <= radius }
}

/**
 * Turns a page of strokes into the picture the vision model gets.
 *
 * Dark ink on white, cropped to the writing with a margin, and scaled so
 * the longer side is at most [MAX_SIDE]: the model reads handwriting best
 * high-contrast, and a smaller image is faster and cheaper to send.
 */
object InkRenderer {

    private const val MAX_SIDE = 1280
    private const val MARGIN = 32f
    private const val BASE_WIDTH = 5f
    private const val JPEG_QUALITY = 90

    fun bounds(strokes: List<InkStroke>): RectF? {
        val all = strokes.flatMap { it.points }
        if (all.isEmpty()) return null
        return RectF(all.minOf { it.x }, all.minOf { it.y }, all.maxOf { it.x }, all.maxOf { it.y })
    }

    fun render(strokes: List<InkStroke>): Bitmap? {
        val box = bounds(strokes) ?: return null
        val width = box.width() + 2 * MARGIN
        val height = box.height() + 2 * MARGIN
        val scale = min(1f, MAX_SIDE / max(width, height))
        val bitmap = Bitmap.createBitmap(
            max(1, (width * scale).toInt()), max(1, (height * scale).toInt()), Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            scale(scale, scale)
            translate(MARGIN - box.left, MARGIN - box.top)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x10, 0x14, 0x1A)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        strokes.forEach { stroke -> draw(canvas, stroke, paint) }
        return bitmap
    }

    private fun draw(canvas: Canvas, stroke: InkStroke, paint: Paint) {
        val p = stroke.points
        if (p.size == 1) {
            paint.strokeWidth = BASE_WIDTH * p[0].pressure.coerceIn(0.4f, 1.6f)
            canvas.drawPoint(p[0].x, p[0].y, paint)
            return
        }
        for (i in 1 until p.size) {
            paint.strokeWidth = BASE_WIDTH * ((p[i - 1].pressure + p[i].pressure) / 2).coerceIn(0.4f, 1.6f)
            canvas.drawLine(p[i - 1].x, p[i - 1].y, p[i].x, p[i].y, paint)
        }
    }

    fun base64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

/** A page waiting to be asked about, from the notebook to the chat. */
class NotebookInbox {
    data class Page(val imageBase64: String, val question: String)

    @Volatile
    private var page: Page? = null

    fun put(p: Page) {
        page = p
    }

    fun take(): Page? = page.also { page = null }
}
