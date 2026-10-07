package com.friday.ai.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.friday.ai.command.CommandExecutor
import com.friday.ai.agent.FridayAgent
import com.friday.ai.core.Acknowledgements
import com.friday.ai.core.SystemPromptBuilder
import com.friday.ai.data.local.dao.ChatMessageDao
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.service.messages.Announcer
import com.friday.ai.core.MediaControls
import com.friday.ai.service.voice.AudioFocusHold
import com.friday.ai.service.voice.ServiceNotifier
import com.friday.ai.service.voice.SpeakerGate
import com.friday.ai.service.voice.SpokenAnswer
import com.friday.ai.service.voice.VoiceConversation
import com.friday.ai.service.voice.VoiceIO
import com.friday.ai.service.voice.VoiceSession
import com.friday.ai.service.voice.WakeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get

/**
 * The foreground service that keeps Friday listening.
 *
 * Only lifecycle and wiring live here. The parts each do one thing:
 * [WakeListener] waits for the owner's "Пятница", [VoiceConversation] runs the
 * exchange that follows, [SpokenAnswer] speaks the model's replies,
 * [CommandExecutor] carries out commands — the same one the chat uses — and
 * [VoiceSession] keeps the record.
 */
class FridayWakeWordService : Service() {

    companion object {
        private const val TAG = "FridayWakeWord"

        /** True while the service exists; read by diagnostics. */
        @Volatile
        var running = false
            private set

        /** Where the enrolled voice profile lives. */
        const val PREF_VOICE_PROFILE = "voice_profile"

        /**
         * Settle margin before the wake listener takes the microphone back.
         * The recorder has already released it; this is time for the audio
         * HAL, not a guess at how long release takes.
         */
        private const val MIC_SETTLE_MS = 200L

        /** Long enough for a player to react to getting the audio back. */
        private const val FOCUS_SETTLE_MS = 800L

        fun start(context: Context) {
            try {
                val intent = Intent(context, FridayWakeWordService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, FridayWakeWordService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop: ${e.message}")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var notifier: ServiceNotifier
    private lateinit var overlay: FridayOverlayManager
    private lateinit var focus: AudioFocusHold
    private lateinit var speaker: EdgeTtsSpeaker
    private lateinit var wake: WakeListener
    private lateinit var transcriber: WhisperTranscriber
    private lateinit var gate: SpeakerGate
    private lateinit var conversation: VoiceConversation
    private lateinit var prefDao: UserPreferenceDao
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        notifier = ServiceNotifier(this)
        try {
            notifier.createChannel()
            startForeground(ServiceNotifier.NOTIFICATION_ID, notifier.build("Initializing..."))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}")
            stopSelf()
            return
        }
        assemble()
    }

    private fun assemble() {
        prefDao = get(UserPreferenceDao::class.java)
        overlay = FridayOverlayManager(this)
        focus = AudioFocusHold(this)
        speaker = EdgeTtsSpeaker(this).apply {
            // The greeting follows "Пятница" every time; recorded once, it
            // plays at once instead of after a round-trip to Microsoft.
            prefetch(Acknowledgements.all)
        }
        gate = SpeakerGate(SpeakerEmbedder(applicationContext))
        wake = WakeListener(applicationContext, scope, gate, notifier)
        transcriber = WhisperTranscriber(get(GroqApiService::class.java), cacheDir).apply {
            // Fires on the recording thread ~20x a second; the reactor only
            // stores the value and redraws on its own vsync, so no hop needed.
            onLevel = { overlay.level(it) }
        }
        val session = VoiceSession(
            prefDao, get(FridayMemory::class.java), get(ChatMessageDao::class.java), get(ModelCatalog::class.java)
        )
        conversation = VoiceConversation(
            scope = scope,
            io = VoiceIO(
                overlay, speaker, transcriber, OfflineCommandRecognizer(this), wake, notifier, focus,
                inCall = { com.friday.ai.core.CallState.inCall(this) }
            ),
            gate = gate,
            commands = get(CommandExecutor::class.java),
            answers = SpokenAnswer(
                get(FridayAgent::class.java), get(SystemPromptBuilder::class.java),
                get(FridayMemory::class.java), session, speaker, overlay
            ),
            session = session
        )
        wake.onWake = { heard -> conversation.onWake(heard) }
        overlay.onDismissed = {
            // The conversation is over: give the audio back. A player that was
            // told to pause must not take that as its cue to play again.
            focus.release()
            scope.launch {
                delay(FOCUS_SETTLE_MS)
                get<MediaControls>(MediaControls::class.java).keepPaused()
            }
            scope.launch {
                delay(MIC_SETTLE_MS)
                wake.resume()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started && ::conversation.isInitialized) {
            started = true
            scope.launch { begin() }
        }
        return START_STICKY
    }

    private suspend fun begin() {
        gate.load(prefDao.get(PREF_VOICE_PROFILE))
        prefDao.get("whisper_threshold")?.toDoubleOrNull()?.let { transcriber.silenceThreshold = it }
        if (wake.initialise(prefDao.get("wake_threshold")?.toDoubleOrNull())) wake.resume()
        // Calls and messages the notification listener heard about.
        scope.launch {
            get<Announcer>(Announcer::class.java).announcements
                .collect { conversation.announce(it.text, it.listenAfter) }
        }
    }

    override fun onDestroy() {
        running = false
        started = false
        if (::conversation.isInitialized) {
            wake.destroy()
            speaker.destroy()
            overlay.dismiss()
        }
        scope.cancel()
        super.onDestroy()
    }
}
