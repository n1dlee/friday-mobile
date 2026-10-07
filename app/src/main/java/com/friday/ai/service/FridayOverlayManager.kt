package com.friday.ai.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.friday.ai.ui.overlay.ArcReactorView
import com.friday.ai.ui.overlay.OverlayPalette

/**
 * The floating panel Friday speaks from.
 *
 * A rounded capsule that hovers above the navigation bar rather than sitting
 * on the edge, so the app underneath still reads as the thing you were doing.
 * The arc reactor on the left carries the state; the text is deliberately
 * quiet next to it.
 */
class FridayOverlayManager(private val context: Context) {

    enum class State { LISTENING, PROCESSING, SPEAKING, RESULT, HIDDEN }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var panelView: View? = null
    private var statusText: TextView? = null
    private var mainText: TextView? = null
    private var reactor: ArcReactorView? = null
    private var currentState = State.HIDDEN
    private var autoDismissRunnable: Runnable? = null

    var onDismissed: (() -> Unit)? = null

    /**
     * Who produced the answer on screen — "команда" (the phone did it
     * directly) or "ИИ" (the model answered) — shown next to Friday's name,
     * so when something goes wrong it is clear where to look.
     */
    private var source: String? = null

    fun source(label: String?) {
        handler.post { source = label }
    }

    private val speakerLabel: String get() = source?.let { "Пятница · $it" } ?: "Пятница"

    /** True while the panel is up: Friday is in the middle of something. */
    val isShowing: Boolean get() = currentState != State.HIDDEN

    fun show(state: State, text: String = "") {
        handler.post {
            if (overlayView == null) createOverlay()
            updateState(state, text)
        }
    }

    fun updateText(text: String) {
        handler.post { mainText?.text = text }
    }

    /**
     * Live microphone loudness, 0..1. Drives the reactor's inner ring — this is
     * the only thing that makes the overlay feel like it is actually hearing
     * you rather than playing a canned animation.
     */
    fun level(value: Float) {
        reactor?.level(value)
    }

    fun dismiss() {
        handler.post {
            cancelAutoDismiss()
            val view = overlayView
            if (view == null) {
                currentState = State.HIDDEN
                onDismissed?.invoke()
                return@post
            }
            // Fade out rather than vanish; 180ms is short enough not to feel laggy.
            panelView?.animate()
                ?.alpha(0f)
                ?.translationY(dpF(16))
                ?.setDuration(180)
                ?.withEndAction {
                    removeOverlay()
                    currentState = State.HIDDEN
                    onDismissed?.invoke()
                }
                ?.start() ?: run {
                removeOverlay()
                currentState = State.HIDDEN
                onDismissed?.invoke()
            }
        }
    }

    fun showResult(text: String, autoDismissMs: Long = 4000) {
        handler.post {
            if (overlayView == null) createOverlay()
            updateState(State.RESULT, text)
            cancelAutoDismiss()
            autoDismissRunnable = Runnable { dismiss() }
            handler.postDelayed(autoDismissRunnable!!, autoDismissMs)
        }
    }


    // ------------------------------------------------------------------

    private fun dp(value: Int): Int = dpF(value).toInt()

    private fun dpF(value: Int): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    )

    private fun createOverlay() {
        val container = FrameLayout(context).apply {
            setOnClickListener { dismiss() }
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(22), dp(16))
            background = GradientDrawable().apply {
                setColor(OverlayPalette.PANEL)
                cornerRadius = dpF(34)
                // A hairline of the same light the reactor emits: it makes the
                // capsule look lit from inside instead of pasted on.
                setStroke(dp(1).coerceAtLeast(1), OverlayPalette.PANEL_EDGE)
            }
            elevation = dpF(12)
        }
        panelView = panel

        reactor = ArcReactorView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                marginEnd = dp(16)
            }
        }
        panel.addView(reactor)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        statusText = TextView(context).apply {
            setTextColor(OverlayPalette.CYAN)
            textSize = 11f
            letterSpacing = 0.18f
            isAllCaps = true
        }
        column.addView(statusText)

        mainText = TextView(context).apply {
            setTextColor(OverlayPalette.TEXT)
            textSize = 17f
            // Long answers get read aloud, not printed — three lines is plenty.
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }
        column.addView(mainText)
        panel.addView(column)

        val panelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            // Floating, not docked: clear of the gesture bar on every side.
            setMargins(dp(14), 0, dp(14), dp(28))
        }
        container.addView(panel, panelParams)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        try {
            windowManager.addView(container, params)
            overlayView = container
            panel.alpha = 0f
            panel.translationY = dpF(24)
            panel.animate().alpha(1f).translationY(0f).setDuration(260).start()
        } catch (e: Exception) {
            android.util.Log.e("FridayOverlay", "Failed to add overlay: ${e.message}")
            panelView = null
        }
    }

    private fun updateState(state: State, text: String) {
        currentState = state
        when (state) {
            State.LISTENING -> {
                // A new request: whoever answered the last one is not answering this.
                source = null
                statusText?.text = "Слушаю"
                statusText?.setTextColor(OverlayPalette.CYAN)
                mainText?.text = text
                mainText?.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
                reactor?.setMood(ArcReactorView.Mood.LISTENING)
            }
            State.PROCESSING -> {
                statusText?.text = "Думаю"
                statusText?.setTextColor(OverlayPalette.AMBER)
                mainText?.text = text
                mainText?.visibility = View.VISIBLE
                reactor?.setMood(ArcReactorView.Mood.THINKING)
            }
            State.SPEAKING -> {
                statusText?.text = speakerLabel
                statusText?.setTextColor(OverlayPalette.CYAN)
                mainText?.text = text
                mainText?.visibility = View.VISIBLE
                reactor?.setMood(ArcReactorView.Mood.SPEAKING)
            }
            State.RESULT -> {
                statusText?.text = speakerLabel
                statusText?.setTextColor(OverlayPalette.TEXT_MUTED)
                mainText?.text = text
                mainText?.visibility = View.VISIBLE
                // The reactor stays lit but goes quiet — a full stop reads as a
                // crash, an idle glow reads as "still here".
                reactor?.setMood(ArcReactorView.Mood.IDLE)
            }
            State.HIDDEN -> dismiss()
        }
    }

    private fun removeOverlay() {
        reactor?.stop()
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
        panelView = null
        statusText = null
        mainText = null
        reactor = null
    }

    private fun cancelAutoDismiss() {
        autoDismissRunnable?.let { handler.removeCallbacks(it) }
        autoDismissRunnable = null
    }
}
