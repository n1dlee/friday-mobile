package com.friday.ai.service.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.friday.ai.service.VoskModelManager
import com.friday.ai.service.WakeWordEngine
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The always-on microphone: waits for "Пятница", and only passes it on when
 * the voice belongs to the owner.
 *
 * A stranger's wake word gets no reaction at all — not a refusal, which
 * would only tell them an assistant is listening. (That check is a
 * convenience, not security: with no voice profile, or when the embedding
 * fails, [admits] lets the call through. What must stay locked is decided by
 * LockPolicy and the command's own confirmation.)
 *
 * One controller owns every transition — listen, stop for a command, pause
 * for a call, recover, destroy — and runs them one at a time ([mutex]).
 * Each listening session carries a generation number; a detection, a
 * failure or a delayed resume that arrives after a newer transition is
 * ignored, so nothing late can reopen the microphone over a conversation,
 * a call or a shutdown.
 *
 * Exactly one recorder at a time: [stop] interrupts the capture (unblocking
 * its read) and joins it before returning, so the command recorder opens
 * only on a free microphone. A capture that ends on its own — read error,
 * stalled frames, recorder refused — is retried in this already running
 * service after 1, 2, 4, 8 and 16 s, then reported [WakeHealth.Status.FAILED];
 * every step shows in the notification and in [WakeStatus].
 */
// Dependencies as functions so every transition is tested with a fake source and virtual time;
// one public function per transition, each serialized through the same lock.
@Suppress("LongParameterList", "TooManyFunctions")
class WakeListener(
    private val scope: CoroutineScope,
    /** The owner check on the wake audio; heavy, run on [io]. */
    private val admits: (FloatArray) -> Boolean,
    /** Shows the status line (the foreground notification). */
    private val show: (String) -> Unit,
    /** Loads the model into a source: the source and the model's path, or why not. */
    private val load: suspend (energyThreshold: Double?) -> Loaded,
    private val micAllowed: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val backoffMs: List<Long> = BACKOFF_MS,
    private val onRecovery: () -> Unit = {}
) {

    /** What [load] produced. */
    sealed interface Loaded {
        data class Ready(val source: WakeSource, val modelPath: String) : Loaded
        data class Unavailable(val reason: WakeFailure) : Loaded
    }

    companion object {
        private const val TAG = "WakeListener"

        /** Retry delays after an unexpected end of capture; then FAILED. */
        val BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)

        /** No frame for this long while listening: the recorder has stalled. */
        const val STALL_MS = 5_000L
        private const val WATCHDOG_MS = 1_000L

        /** Capture that ran this long counts as healthy again: the retry count starts over. */
        const val HEALTHY_MS = 60_000L

        /** How long stop and destroy wait for a capture to let go of the microphone. */
        const val TEARDOWN_MS = 2_000L

        /** The listener the service uses: the Vosk engine, the model manager, the real permission. */
        fun create(context: Context, scope: CoroutineScope, gate: SpeakerGate, notifier: ServiceNotifier) =
            WakeListener(
                scope = scope,
                admits = gate::admitsWake,
                show = notifier::update,
                load = { threshold -> loadVosk(context, notifier, threshold) },
                onRecovery = { VoiceTelemetry.diagnostics.restarted() },
                micAllowed = {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                }
            )

        private suspend fun loadVosk(context: Context, notifier: ServiceNotifier, threshold: Double?): Loaded {
            val models = VoskModelManager(context)
            if (!models.isModelReady()) {
                notifier.update("Downloading voice model...")
                models.downloadModel()
            }
            val path = models.getModelPath() ?: return Loaded.Unavailable(WakeFailure.MODEL)
            val engine = WakeWordEngine()
            if (!engine.loadModel(path)) return Loaded.Unavailable(WakeFailure.MODEL)
            threshold?.let { engine.energyThreshold = it }
            return Loaded.Ready(engine, path)
        }
    }

    /**
     * Called once the owner is confirmed, with the words the call was heard
     * in, whether they were still speaking, and the audio up to that point
     * (for a command said in the same breath). The recorder is already
     * released.
     */
    var onWake: ((heard: String, continuing: Boolean, audio: FloatArray) -> Unit)? = null

    /** Where the on-device model lives; the offline recogniser shares it. */
    var modelPath: String? = null
        private set

    private val _health = MutableStateFlow(WakeHealth(WakeHealth.Status.DISABLED))
    val health: StateFlow<WakeHealth> = _health.asStateFlow()

    /** When the recorder last delivered a frame (monotonic clock). */
    @Volatile var lastFrameAt = 0L
        private set

    private val mutex = Mutex()
    private val generation = AtomicLong()
    private var source: WakeSource? = null
    private var job: Job? = null
    @Volatile private var destroyed = false
    @Volatile private var pausedForCall = false
    @Volatile private var stalled = false

    /** Teardown must outlive the service scope, which is cancelled as the service goes. */
    private val teardown = CoroutineScope(SupervisorJob() + io)

    /** The current generation: captured before a delayed [resume], so a stale one is ignored. */
    val token: Long get() = generation.get()

    /**
     * Fetches and loads the on-device model. Slow the first time — it may
     * download — so it runs off the main thread.
     *
     * @return true once listening can start
     */
    suspend fun initialise(energyThreshold: Double?): Boolean {
        if (!micAllowed()) {
            report(WakeHealth(WakeHealth.Status.UNAVAILABLE, WakeFailure.PERMISSION))
            return false
        }
        report(WakeHealth(WakeHealth.Status.LOADING))
        return when (val loaded = withContext(io) { load(energyThreshold) }) {
            is Loaded.Ready -> {
                mutex.withLock {
                    source?.close()
                    source = loaded.source
                }
                modelPath = loaded.modelPath
                true
            }
            is Loaded.Unavailable -> {
                report(WakeHealth(WakeHealth.Status.UNAVAILABLE, loaded.reason))
                false
            }
        }
    }

    /** Whether the model is loaded; false after a failed [initialise]. */
    val ready: Boolean get() = source != null

    /** The Vosk model, for following a command word by word; null when none is loaded. */
    val voskModel: org.vosk.Model? get() = (source as? WakeWordEngine)?.vosk

    /**
     * Starts listening unless a capture is already running — two calls close
     * together still open one recorder.
     */
    fun listen() {
        scope.launch { start(token = null) }
    }

    /**
     * Back to idle listening after a conversation. With [token] (taken when
     * the conversation ended), a resume that a newer transition has
     * overtaken — a new turn, a call, a stop — does nothing.
     */
    fun resume(token: Long? = null) {
        scope.launch { start(token) }
    }

    /** Stops listening and waits until the microphone is free for the command recorder. */
    suspend fun stop() {
        halt(WakeHealth.Status.PAUSED_FOR_COMMAND)
    }

    /** A call is ringing or in progress: let go of the microphone until it ends. */
    suspend fun pauseForCall() {
        pausedForCall = true
        halt(WakeHealth.Status.PAUSED_FOR_CALL)
    }

    /**
     * The call ended (and audio has stayed normal). With [listenNow] false —
     * a conversation is on screen — the conversation's own end resumes
     * listening instead, so two recorders never meet.
     */
    fun callEnded(listenNow: Boolean = true) {
        if (!pausedForCall) return
        pausedForCall = false
        if (listenNow) listen()
    }

    /**
     * The service is going. The capture is interrupted and the decoder is
     * closed only after it has returned — closing native Vosk under a
     * running read crashes. Never blocks the caller: the wait runs on its
     * own scope, bounded; if the capture never returns, the decoder is left
     * open rather than freed under it.
     */
    fun destroy() {
        destroyed = true
        generation.incrementAndGet()
        // At once, without the lock: a blocked read starts returning now.
        source?.interrupt()
        teardown.launch {
            // Under the lock: no start can slip a new capture in after this.
            mutex.withLock {
                val running = job
                job = null
                val src = source
                source = null
                running?.cancel()
                src?.interrupt()
                val done = running == null || withTimeoutOrNull(TEARDOWN_MS) { running.join() } != null
                if (done) {
                    src?.close()
                    report(WakeHealth(WakeHealth.Status.STOPPED))
                } else {
                    Log.w(TAG, "Capture did not end within $TEARDOWN_MS ms; decoder left open")
                    report(WakeHealth(WakeHealth.Status.FAILED, WakeFailure.TEARDOWN))
                }
            }
        }
    }

    private suspend fun start(token: Long?) = mutex.withLock {
        val src = source ?: return@withLock
        val stale = token != null && token != generation.get()
        val busy = job?.isActive == true
        val held = destroyed || pausedForCall
        if (held || stale || busy) return@withLock
        val gen = generation.incrementAndGet()
        job = scope.launch(io) { run(src, gen) }
    }

    private suspend fun halt(status: WakeHealth.Status) = mutex.withLock {
        generation.incrementAndGet()
        val running = job
        job = null
        if (running != null) {
            report(WakeHealth(WakeHealth.Status.PAUSING))
            running.cancel()
            source?.interrupt()
            if (withTimeoutOrNull(TEARDOWN_MS) { running.join() } == null) {
                Log.w(TAG, "Capture did not end within $TEARDOWN_MS ms")
                report(WakeHealth(WakeHealth.Status.FAILED, WakeFailure.TEARDOWN))
                return@withLock
            }
        }
        report(WakeHealth(status))
    }

    /** One listening session: captures, retries on failure, and hands a detection over. */
    private suspend fun run(src: WakeSource, gen: Long) {
        var attempt = 0
        while (current(gen)) {
            // A retry keeps showing "recovering" until frames arrive.
            if (attempt == 0) report(WakeHealth(WakeHealth.Status.STARTING))
            val began = clock()
            val end = watched(src, began)
            if (!current(gen)) return
            if (end is WakeSource.End.Detected) {
                if (handOver(end, gen)) return
                continue
            }
            if (clock() - began >= HEALTHY_MS && lastFrameAt > began) attempt = 0
            val reason = failureOf(end)
            if (final(reason, attempt)) {
                Log.w(TAG, "Wake capture failed: $reason")
                report(WakeHealth(WakeHealth.Status.FAILED, reason))
                return
            }
            val wait = backoffMs[attempt]
            attempt++
            Log.w(TAG, "Wake capture ended ($reason); retry $attempt in $wait ms")
            report(WakeHealth(WakeHealth.Status.RECOVERING, reason, attempt, wait))
            delay(wait)
        }
    }

    /** Why a capture that was not a detection ended; a missing permission overrides the rest. */
    private fun failureOf(end: WakeSource.End): WakeFailure {
        val reason = when (end) {
            is WakeSource.End.Failed -> end.reason
            else -> if (stalled) WakeFailure.STALLED else WakeFailure.UNEXPECTED
        }
        return if (micAllowed()) reason else WakeFailure.PERMISSION
    }

    /** No retry: retrying cannot fix the permission or the model, and the schedule has run out. */
    private fun final(reason: WakeFailure, attempt: Int): Boolean =
        reason == WakeFailure.PERMISSION || reason == WakeFailure.MODEL || attempt >= backoffMs.size

    /** A capture with a watchdog: no frame for [STALL_MS] interrupts it as stalled. */
    private suspend fun watched(src: WakeSource, began: Long): WakeSource.End = coroutineScope {
        stalled = false
        var listening = false
        val dog = launch {
            while (isActive) {
                delay(WATCHDOG_MS)
                if (clock() - maxOf(lastFrameAt, began) >= STALL_MS) {
                    stalled = true
                    src.interrupt()
                    return@launch
                }
            }
        }
        val end = try {
            src.capture {
                lastFrameAt = clock()
                // "Listening" only once audio is really arriving.
                if (!listening) {
                    listening = true
                    report(WakeHealth(WakeHealth.Status.LISTENING))
                }
            }
        } finally {
            dog.cancel()
        }
        end
    }

    /** Checks the voice and passes the call on; false when it was not the owner (keep listening). */
    private fun handOver(end: WakeSource.End.Detected, gen: Long): Boolean {
        if (!admits(end.audio)) {
            Log.i(TAG, "Wake word ignored: not the enrolled speaker")
            return false
        }
        if (!current(gen)) return true
        report(WakeHealth(WakeHealth.Status.PAUSED_FOR_COMMAND))
        onWake?.invoke(end.heard, end.continuing, end.audio)
        return true
    }

    private fun current(gen: Long) = !destroyed && generation.get() == gen

    private fun report(health: WakeHealth) {
        val changed = _health.value != health
        _health.value = health
        WakeStatus.report(health)
        if (changed && health.status == WakeHealth.Status.RECOVERING) onRecovery()
        if (changed && health.status != WakeHealth.Status.PAUSING) show(health.describe())
    }
}
