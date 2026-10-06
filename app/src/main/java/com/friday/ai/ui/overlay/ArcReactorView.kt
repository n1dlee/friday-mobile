package com.friday.ai.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The arc reactor: a circular equaliser that reacts to the voice.
 *
 * Bars radiate outward from a ring, longest where the signal is loudest. Their
 * *lengths* come from real microphone loudness; the variation between
 * neighbours is a travelling field, not a frequency analysis — a single RMS
 * figure is all the recorder produces, and a spectrum would need an FFT the
 * overlay has no use for otherwise. It reads as a voice because it moves with
 * one, which is the part that matters here.
 *
 * Everything runs off one phase clock so the whole thing costs a single
 * vsync-synced animator, and nothing allocates per frame.
 */
class ArcReactorView(context: Context) : View(context) {

    enum class Mood { LISTENING, THINKING, SPEAKING, IDLE }

    private companion object {
        /**
         * One turn of the clock. Every periodic term below is chosen to
         * complete a whole number of cycles in this window, so the wrap from
         * 1.0 back to 0.0 is invisible.
         */
        const val CLOCK_PERIOD_MS = 14_000L
        const val CLOCK_PERIOD_S = CLOCK_PERIOD_MS / 1000f

        /** π rad/s over 14s = exactly 7 cycles. Do not tune this in isolation. */
        const val SHIMMER_RATE = PI.toFloat()

        /** 5 breaths per turn; roughly a resting breathing rate. */
        const val BREATHS_PER_TURN = 5f

        /** 6 laps per turn — one sweep every ~2.3s while thinking. */
        const val THINK_LAPS_PER_TURN = 6f

        const val BAR_COUNT = 40

        /** Fast attack, slow release: bars snap up on speech and settle calmly. */
        const val BAR_ATTACK = 0.50f
        const val BAR_RELEASE = 0.12f
        const val LEVEL_ATTACK = 0.45f
        const val LEVEL_RELEASE = 0.07f
    }

    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Bar heights, 0..1, smoothed across frames. Allocated once. */
    private val bars = FloatArray(BAR_COUNT)

    /** Unit vectors per bar, rebuilt only when the size changes. */
    private val barCos = FloatArray(BAR_COUNT)
    private val barSin = FloatArray(BAR_COUNT)

    private var animator: ValueAnimator? = null
    private var phase = 0f

    private var mood = Mood.IDLE
    private var tint = OverlayPalette.CYAN

    private var displayedLevel = 0f
    private var targetLevel = 0f

    private var radius = 0f
    private var cx = 0f
    private var cy = 0f

    init {
        for (i in 0 until BAR_COUNT) {
            // Start at the top and go clockwise, so the first bar sits where
            // the eye lands.
            val a = i.toFloat() / BAR_COUNT * 2f * PI.toFloat() - PI.toFloat() / 2f
            barCos[i] = cos(a)
            barSin[i] = sin(a)
        }
    }

    /**
     * True unless the system asks for no animation ("Remove animations" in
     * accessibility settings, or battery saver). We then draw one static frame
     * rather than stopping dead, which would read as a crash.
     */
    private val motionAllowed: Boolean
        get() = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) != 0f

    /** Microphone loudness, 0..1. Safe to call from the recording thread. */
    fun level(value: Float) {
        targetLevel = value.coerceIn(0f, 1f)
        if (!motionAllowed) postInvalidateOnAnimation()
    }

    fun setMood(newMood: Mood) {
        mood = newMood
        tint = when (newMood) {
            Mood.THINKING -> OverlayPalette.AMBER
            else -> OverlayPalette.CYAN
        }
        if (newMood == Mood.IDLE) targetLevel = 0f
        rebuildShaders()
        start()
    }

    fun start() {
        if (animator != null) return
        if (!motionAllowed) {
            displayedLevel = targetLevel
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CLOCK_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                val rate = if (targetLevel > displayedLevel) LEVEL_ATTACK else LEVEL_RELEASE
                displayedLevel += (targetLevel - displayedLevel) * rate
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f
        rebuildShaders()
    }

    private fun rebuildShaders() {
        if (radius <= 0f) return
        // A real LED bloom: opaque at the centre, gone by the edge. Cheaper and
        // sharper than a blur mask, and it stays on the hardware pipeline.
        bloomPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                withAlpha(tint, 0.55f),
                withAlpha(tint, 0.18f),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        val seconds = phase * CLOCK_PERIOD_S
        val breath = 0.5f + 0.5f * sin(phase * BREATHS_PER_TURN * 2f * PI.toFloat())

        val energy = when (mood) {
            // No microphone while Friday talks, so speech gets its own rhythm.
            Mood.SPEAKING -> max(displayedLevel, breath * 0.7f)
            Mood.THINKING -> 0.55f
            Mood.LISTENING -> displayedLevel
            Mood.IDLE -> 0.12f
        }.coerceIn(0f, 1f)

        advanceBars(seconds, energy)
        drawBloom(canvas, breath, energy)
        drawBars(canvas)
        drawInnerRing(canvas, breath)
        drawCore(canvas, energy)
    }

    private fun advanceBars(seconds: Float, energy: Float) {
        // While thinking, the ring runs a single bump around the circle: an
        // obvious "working" tell that never pretends to be hearing anything.
        val head = if (mood == Mood.THINKING) {
            (phase * THINK_LAPS_PER_TURN * BAR_COUNT) % BAR_COUNT
        } else -1f

        for (i in 0 until BAR_COUNT) {
            val shape = if (head >= 0f) {
                val d = abs(i - head).let { min(it, BAR_COUNT - it) }
                val fall = (1f - d / 5f).coerceAtLeast(0f)
                fall * fall
            } else {
                // Neighbours differ by a travelling field, and the far side of
                // the ring stays a little shorter so the shape has a front.
                val shimmer = abs(sin(i * 0.7f + seconds * SHIMMER_RATE))
                shimmer * (1f - i.toFloat() / BAR_COUNT * 0.55f)
            }
            val target = (energy * (0.35f + 0.65f * shape)).coerceIn(0f, 1f)
            val rate = if (target > bars[i]) BAR_ATTACK else BAR_RELEASE
            bars[i] += (target - bars[i]) * rate
        }
    }

    private fun drawBloom(canvas: Canvas, breath: Float, energy: Float) {
        val scale = 0.62f + 0.30f * energy + 0.06f * breath
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        bloomPaint.alpha = (255 * (0.30f + 0.55f * energy)).toInt()
        canvas.drawCircle(cx, cy, radius, bloomPaint)
        canvas.restore()
    }

    private fun drawBars(canvas: Canvas) {
        barPaint.strokeWidth = radius * 0.055f
        val r0 = radius * 0.50f
        for (i in 0 until BAR_COUNT) {
            val h = bars[i]
            // The tallest bars go white-hot; that top end is what makes a loud
            // syllable visible from across the room.
            barPaint.color = withAlpha(
                if (h > 0.55f) OverlayPalette.CYAN_HOT else tint,
                0.30f + 0.65f * h
            )
            val r1 = radius * (0.58f + 0.40f * h)
            canvas.drawLine(
                cx + r0 * barCos[i], cy + r0 * barSin[i],
                cx + r1 * barCos[i], cy + r1 * barSin[i],
                barPaint
            )
        }
    }

    private fun drawInnerRing(canvas: Canvas, breath: Float) {
        ringPaint.strokeWidth = radius * 0.028f
        ringPaint.color = withAlpha(tint, 0.30f + 0.20f * breath)
        canvas.drawCircle(cx, cy, radius * 0.44f, ringPaint)
    }

    private fun drawCore(canvas: Canvas, energy: Float) {
        corePaint.color = withAlpha(tint, 0.9f)
        canvas.drawCircle(cx, cy, radius * (0.20f + 0.05f * energy), corePaint)
        corePaint.color = withAlpha(OverlayPalette.CYAN_HOT, 0.55f + 0.45f * energy)
        canvas.drawCircle(cx, cy, radius * (0.10f + 0.04f * energy), corePaint)
    }

    private fun withAlpha(color: Int, fraction: Float): Int =
        (color and 0x00FFFFFF) or ((255 * fraction.coerceIn(0f, 1f)).toInt() shl 24)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}
