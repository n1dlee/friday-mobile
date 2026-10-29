package com.friday.ai.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.friday.ai.core.apps.AppVisibility
import com.friday.ai.core.voice.VoicePanel
import com.friday.ai.core.voice.VoicePhase
import com.friday.ai.core.voice.VoicePresentation
import com.friday.ai.core.voice.Surfaces
import com.friday.ai.service.lock.LockScreen
import com.friday.ai.service.voice.VoiceUi
import com.friday.ai.ui.theme.FridayTheme
import com.friday.ai.ui.voice.PanelAction
import com.friday.ai.ui.voice.VoicePanelCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Friday's voice panel: writes each stage of the conversation into the one
 * session state ([VoiceUi]) and puts it on whichever surface Android allows
 * right now ([Surfaces]):
 *
 *  - over the home screen or another app — a compact floating capsule; a
 *    tap on the text expands it (whole text, "Открыть в Friday"), the cross
 *    closes it;
 *  - inside Friday's own screen — her own layer, no second window on top;
 *  - over the lock screen — a private panel ([LockScreen]);
 *  - nowhere, when nothing is allowed: the answer is spoken.
 *
 * The panels draw from the state, never from each other's Views.
 */
@Suppress("TooManyFunctions") // one call per stage of a conversation, plus the window it lives in
class FridayOverlayManager(private val context: Context) {

    private companion object {
        const val TAG = "FridayOverlay"
    }

    private val store = VoiceUi.store
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var window: OverlayWindow? = null
    private var autoDismiss: Runnable? = null
    private var lastSession = -1L
    @Volatile private var generation = 0L

    var onDismissed: (() -> Unit)? = null

    /** True while a session is open: Friday is in the middle of something. */
    val isShowing: Boolean get() = store.state.value != null

    init {
        // Surfaces follow the session and Friday's own screen coming and going.
        scope.launch { combine(store.state, AppVisibility.foreground) { p, _ -> p }.collect(::render) }
    }

    /** Opens a session: [userInitiated] when the owner called, false for Friday's own announcements. */
    fun begin(userInitiated: Boolean) {
        generation++
        onMain(generation) { cancelAutoDismiss() }
        store.begin(userInitiated)
    }

    fun greet(text: String) = stage(VoicePhase.GREETING) { it.copy(answer = text) }

    fun listening() = stage(VoicePhase.LISTENING)

    /** The owner started speaking (or the recording just opened). */
    fun hearing(on: Boolean) = store.phase(store.state.value?.phase ?: VoicePhase.LISTENING) { it.copy(hearing = on) }

    /** Recording over: [heard] once it has been transcribed, null while it is being. */
    fun processing(heard: String?) = stage(VoicePhase.PROCESSING) { it.copy(heard = heard, answer = null) }

    /** A streamed answer, before ([speaking] false) and once the voice has started. */
    fun streaming(text: String, speaking: Boolean, isCurrent: () -> Boolean) =
        stage(if (speaking) VoicePhase.SPEAKING else VoicePhase.PROCESSING, isCurrent) { it.copy(answer = text) }

    fun speaking(text: String) = stage(VoicePhase.SPEAKING) { it.copy(answer = text) }

    /** The answer, left for reading, then the panel closes by itself. */
    fun showResult(text: String, autoDismissMs: Long = 4000) {
        stage(VoicePhase.RESULT) { it.copy(answer = text) }
        dismissIn(autoDismissMs)
    }

    /** A real failure — no key, no network, no microphone — said plainly. */
    fun error(message: String, autoDismissMs: Long = 4000) {
        stage(VoicePhase.ERROR) { it.copy(answer = message) }
        dismissIn(autoDismissMs)
    }

    fun source(label: String?) = store.source(label)

    /** Live microphone loudness, 0..1; drives the reactor only. */
    fun level(value: Float) = store.level(value)

    fun dismiss() {
        val closing = ++generation
        onMain(closing) {
            cancelAutoDismiss()
            store.end()
            onDismissed?.invoke()
        }
    }

    private fun stage(
        phase: VoicePhase,
        isCurrent: () -> Boolean = { true },
        change: (VoicePanel) -> VoicePanel = { it }
    ) {
        onMain(generation) {
            if (isCurrent()) {
                cancelAutoDismiss()
                store.phase(phase, change)
            }
        }
    }

    private fun dismissIn(ms: Long) {
        val current = generation
        onMain(current) {
            cancelAutoDismiss()
            autoDismiss = Runnable { if (generation == current) dismiss() }.also { handler.postDelayed(it, ms) }
        }
    }

    private fun onMain(expected: Long, action: () -> Unit) {
        val guarded = { if (generation == expected) action() }
        if (Looper.myLooper() == Looper.getMainLooper()) guarded() else handler.post { guarded() }
    }

    private fun cancelAutoDismiss() {
        autoDismiss?.let { handler.removeCallbacks(it) }
        autoDismiss = null
    }

    // --- surfaces ---------------------------------------------------------------

    private fun render(panel: VoicePanel?) {
        if (panel == null) {
            removeWindow()
            LockScreen.close(context)
            lastSession = -1
            return
        }
        val surface = Surfaces.choose(conditions(panel))
        if (panel.session != lastSession) {
            lastSession = panel.session
            Log.i(TAG, "Session ${panel.session}: $surface")
        }
        if (surface == Surfaces.Surface.OVERLAY) addWindow() else removeWindow()
        LockScreen.present(context, panel, surface)
    }

    private fun conditions(panel: VoicePanel) = Surfaces.Conditions(
        locked = LockScreen.locked(context),
        appInForeground = AppVisibility.foreground.value,
        overlayAllowed = Settings.canDrawOverlays(context),
        fullScreenAllowed = LockScreen.fullScreenAllowed(context),
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        userInitiated = panel.userInitiated
    )

    private fun addWindow() {
        if (window != null) return
        window = try {
            OverlayWindow(context, windowManager, onInteract = ::cancelAutoDismiss).also { it.attach() }
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Overlay refused: ${e.message}")
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "Overlay not allowed: ${e.message}")
            null
        }
    }

    private fun removeWindow() {
        window?.detach()
        window = null
    }

    /** Stops the panel for good (the service is going). */
    fun release() {
        cancelAutoDismiss()
        removeWindow()
        scope.cancel()
    }
}

/**
 * The floating panel's window: a Compose view in a WindowManager overlay,
 * given the lifecycle a service has none of. Not focusable — the keyboard
 * and Back stay with the app underneath; touches outside the panel reach it.
 */
private class OverlayWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onInteract: () -> Unit
) : LifecycleOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private var view: View? = null

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    fun attach() {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayWindow)
            setViewTreeSavedStateRegistryOwner(this@OverlayWindow)
            setContent { FridayTheme { FloatingPanel(onInteract) { openFriday() } } }
            // Attached = a window Android counts as visible, which lets Friday start apps from the background.
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = AppVisibility.overlay(true)
                override fun onViewDetachedFromWindow(v: View) = AppVisibility.overlay(false)
            })
        }
        windowManager.addView(compose, params())
        view = compose
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun detach() {
        registry.currentState = Lifecycle.State.DESTROYED
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    private fun openFriday() {
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            ?.let { runCatching { context.startActivity(it) } }
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.BOTTOM }
}

/** The floating capsule: the session as the unlocked phone shows it. */
@Composable
private fun FloatingPanel(onInteract: () -> Unit, onOpenFriday: () -> Unit) {
    val panel by VoiceUi.store.state.collectAsState()
    val level by VoiceUi.store.level.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val current = panel ?: return
    val close = { VoiceUi.close() }
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 28.dp)) {
        VoicePanelCard(
            view = VoicePresentation.open(current),
            level = { level },
            expanded = expanded,
            onExpand = {
                expanded = true
                onInteract()
                VoiceUi.store.touched()
            },
            onClose = close,
            actions = if (expanded) {
                listOf(PanelAction("Закрыть", onClick = close), PanelAction("Открыть в Friday", primary = true) {
                    close()
                    onOpenFriday()
                })
            } else {
                emptyList()
            }
        )
    }
}
