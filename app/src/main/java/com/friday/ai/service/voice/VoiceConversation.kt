package com.friday.ai.service.voice

import android.util.Log
import com.friday.ai.command.CommandExecutor
import com.friday.ai.core.Acknowledgements
import com.friday.ai.core.ConversationControl
import com.friday.ai.core.OfflineCommands
import com.friday.ai.core.VoiceTurn
import com.friday.ai.core.WakePhrases
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.service.EdgeTtsSpeaker
import com.friday.ai.service.FridayOverlayManager
import com.friday.ai.service.OfflineCommandRecognizer
import com.friday.ai.service.WhisperTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Everything the conversation hears through and speaks through. */
// A holder: one field per device the conversation uses, nothing to group.
@Suppress("LongParameterList")
class VoiceIO(
    val overlay: FridayOverlayManager,
    val speaker: EdgeTtsSpeaker,
    val transcriber: WhisperTranscriber,
    val offline: OfflineCommandRecognizer,
    val wake: WakeListener,
    val notifier: ServiceNotifier,
    /** Pauses other audio for the length of a conversation; released when the panel closes. */
    val focus: AudioFocusHold? = null
)

/**
 * One spoken exchange after the wake word, from greeting to goodbye.
 *
 * All state changes run on [scope], which in the service is the main thread.
 * That replaces a tangle of `mainHandler.post`: callbacks from the speaker and
 * the wake listener arrive on other threads and are funnelled through here one
 * at a time. Blocking work stays inside the components that own it, so nothing
 * here holds the thread.
 *
 * Every wake starts a new *turn*, and anything still in flight from an older
 * one is ignored when it lands. Interrupting Friday stops her speech, and a
 * stopped utterance still reports itself finished — without the turn check
 * that report reopened the microphone alongside the new turn, so one spoken
 * command was recorded, and carried out, twice.
 */
class VoiceConversation(
    private val scope: CoroutineScope,
    private val io: VoiceIO,
    private val gate: SpeakerGate,
    private val commands: CommandExecutor,
    private val answers: SpokenAnswer,
    private val session: VoiceSession
) {

    private companion object {
        const val TAG = "VoiceConversation"

        /** One retry. A third silent window is just the overlay hanging there. */
        const val MAX_LISTEN_ATTEMPTS = 2

        /** How long a final answer stays on screen before the panel closes. */
        const val RESULT_LINGER_MS = 1500L
        const val NOTICE_MS = 2000L
        const val ERROR_MS = 3000L
    }

    private val acknowledgements = Acknowledgements()

    /** Current turn. Only touched on [scope]'s thread. */
    private var turn = 0

    /** True while Friday is talking, so a wake word means "interrupt". */
    @Volatile
    var speaking = false
        private set

    /** The owner said "Пятница" (already verified by the wake listener). */
    fun onWake(heard: String) {
        scope.launch {
            val mine = ++turn
            // Music stops before anything is said or heard: the recording is
            // the user's voice, not the user's voice over a song.
            io.focus?.take()
            val bargedIn = speaking
            if (bargedIn) {
                Log.i(TAG, "Interrupted while speaking")
                io.speaker.stop()
                speaking = false
            }
            io.notifier.update("Listening...")

            // Cutting Friday off and then being greeted would be absurd —
            // that user already knows they were heard.
            if (bargedIn) {
                io.overlay.show(FridayOverlayManager.State.LISTENING)
                listen(mine, isFollowUp = false)
                return@launch
            }

            val greeting = acknowledgements.next(WakePhrases.detectLanguage(heard))
            // Shown before it is spoken: the panel appears at once, while the
            // voice still needs a round-trip.
            io.overlay.show(FridayOverlayManager.State.SPEAKING, greeting)
            speaking = true
            io.speaker.speak(greeting) {
                scope.launch {
                    if (mine != turn) return@launch
                    speaking = false
                    io.overlay.show(FridayOverlayManager.State.LISTENING)
                    // Only now is the microphone opened. Under the greeting it
                    // would have Friday transcribing her own voice.
                    listen(mine, isFollowUp = false)
                }
            }
        }
    }

    /**
     * Says something unprompted — who is calling, a new message — and, if
     * [listenAfter], keeps listening so the user can answer it without the
     * wake word ("ответь, что еду"). Never cuts into a conversation already
     * under way: that one is the user's.
     */
    fun announce(text: String, listenAfter: Boolean) {
        scope.launch {
            if (speaking || io.overlay.isShowing) {
                Log.i(TAG, "Busy; announcement skipped")
                return@launch
            }
            val mine = ++turn
            // Only when an answer is awaited: a ringing phone keeps its ringtone.
            if (listenAfter) io.focus?.take()
            speakAndContinue(mine, text, allowFollowUp = listenAfter)
        }
    }

    /**
     * Records one voice turn and handles it. A follow-up (Friday has just
     * answered and listens on, with no new wake word) ends quietly on silence
     * instead of complaining "не расслышала".
     */
    internal suspend fun listen(mine: Int, isFollowUp: Boolean, attempt: Int = 1) {
        io.transcriber.listener = overlayFeedback
        // With no network Whisper cannot transcribe at all; fall back to the
        // on-device grammar rather than failing silently.
        if (io.offline.isOffline()) {
            offlineTurn(mine)
            return
        }
        val apiKey = session.apiKey()
        if (apiKey.isBlank()) {
            io.overlay.showResult("API key not set", ERROR_MS)
            return
        }

        val outcome = io.transcriber.recordAndTranscribe(apiKey, gate.commandCheck())
        if (mine != turn) {
            // Interrupted while this was recording: the new turn owns the
            // conversation now, and this audio was its wake word anyway.
            Log.i(TAG, "Dropped a recording from an interrupted turn")
            return
        }
        when (VoiceTurn.next(outcome, isFollowUp, attempt, MAX_LISTEN_ATTEMPTS)) {
            VoiceTurn.Next.PROCESS -> heard(mine, (outcome as VoiceTurn.Outcome.Heard).text)
            VoiceTurn.Next.LISTEN_AGAIN -> {
                Log.i(TAG, "Attempt $attempt gave $outcome; listening again")
                io.overlay.show(FridayOverlayManager.State.LISTENING)
                listen(mine, isFollowUp = false, attempt = attempt + 1)
            }
            VoiceTurn.Next.END_QUIETLY -> {
                if (outcome is VoiceTurn.Outcome.Stranger) Log.i(TAG, "Follow-up from another voice ignored")
                io.overlay.dismiss()
            }
            // On screen only: saying it aloud adds noise to a turn that
            // already went nowhere.
            VoiceTurn.Next.SAY_NOT_HEARD -> io.overlay.showResult("Не расслышала", NOTICE_MS)
            VoiceTurn.Next.SAY_NOT_RECOGNISED -> io.overlay.showResult("Не узнала голос", NOTICE_MS)
        }
    }

    private suspend fun heard(mine: Int, text: String) {
        val russian = session.russian()
        // A pending "Отправить?" is answered before anything else — in
        // particular before "нет" can be taken for a goodbye.
        val confirmed = commands.answerPending(text, russian)
        when {
            confirmed != null -> {
                session.mirror(text, confirmed)
                speakAndContinue(mine, confirmed, allowFollowUp = false)
            }
            ConversationControl.isFarewell(text) -> endConversation()
            else -> process(mine, text, russian)
        }
    }

    private suspend fun process(mine: Int, text: String, russian: Boolean) {
        io.notifier.update("Processing...")
        io.overlay.show(FridayOverlayManager.State.PROCESSING, "\"$text\"")
        val command = commands.route(text)
        io.overlay.source(sourceLabel(command, commands.lastRouteLearned, russian))
        val outcome = commands.execute(command, russian)
        if (outcome is CommandExecutor.Outcome.Conversation) {
            when (val r = answers.answer(outcome.text)) {
                is SpokenAnswer.Result.Finished -> if (mine == turn) afterSpeaking(mine, r.response, r.followUp)
                is SpokenAnswer.Result.Failed -> if (mine == turn) speakAndContinue(mine, r.message, false)
            }
            return
        }
        val reply = spokenReply(outcome)
        session.log(text, reply, command::class.simpleName)
        session.mirror(text, reply)
        if (mine == turn) speakAndContinue(mine, reply, allowFollowUp = true)
    }

    /**
     * One turn with no internet. Only what the phone can do by itself is
     * possible; anything that needs the model is refused plainly.
     */
    private suspend fun offlineTurn(mine: Int) {
        val modelPath = io.wake.modelPath
        if (modelPath == null) {
            speakAndContinue(mine, OfflineCommands.offlineRefusal(true), allowFollowUp = false)
            return
        }
        io.overlay.show(FridayOverlayManager.State.LISTENING)
        val heard = io.offline.listenForCommand(modelPath)
        when {
            mine != turn -> Unit
            heard.isNullOrBlank() -> io.overlay.dismiss()
            else -> runOffline(mine, heard)
        }
    }

    private suspend fun runOffline(mine: Int, heard: String) {
        io.overlay.show(FridayOverlayManager.State.PROCESSING, "\"$heard\"")
        val command = commands.route(heard)
        if (!OfflineCommands.isOfflineCapable(command)) {
            speakAndContinue(mine, OfflineCommands.offlineRefusal(true), allowFollowUp = false)
            return
        }
        val reply = spokenReply(commands.execute(command, session.russian()))
        session.log(heard, reply, command::class.simpleName)
        speakAndContinue(mine, reply, allowFollowUp = true)
    }

    /**
     * Speaks [text], then — unless [allowFollowUp] is false — keeps listening
     * for a reply without needing the wake word again, like a real
     * conversation. The wake word stays live meanwhile, so saying "Пятница"
     * cuts the answer short instead of queueing behind it.
     */
    private fun speakAndContinue(mine: Int, text: String, allowFollowUp: Boolean) {
        io.overlay.show(FridayOverlayManager.State.SPEAKING, text)
        speaking = true
        io.wake.listen()
        io.speaker.speak(text) {
            scope.launch { if (mine == turn) afterSpeaking(mine, text, allowFollowUp) }
        }
    }

    /** Either keep the conversation open, or show the answer briefly and step back. */
    private suspend fun afterSpeaking(mine: Int, spokenText: String, allowFollowUp: Boolean) {
        speaking = false
        if (allowFollowUp) {
            io.overlay.show(FridayOverlayManager.State.LISTENING)
            listen(mine, isFollowUp = true)
        } else {
            io.overlay.showResult(spokenText, RESULT_LINGER_MS)
        }
    }

    /**
     * The user said they are done: stop talking and get out of the way. No
     * goodbye is spoken — the fastest way out of their way is to just go.
     */
    private fun endConversation() {
        Log.i(TAG, "User ended the conversation")
        turn++
        io.speaker.stop()
        io.overlay.dismiss()
    }

    private val overlayFeedback = object : WhisperTranscriber.Listener {
        override fun onRecordingStarted() = io.overlay.updateText("")
        override fun onSpeechDetected() = io.overlay.updateText("Hearing you...")
        override fun onRecordingFinished() =
            io.overlay.show(FridayOverlayManager.State.PROCESSING, "Transcribing...")
        override fun onError(message: String) = io.overlay.showResult("Error: $message", ERROR_MS)
    }
}

/** The panel's note on who answers: the phone directly, a learned phrase, or the model. */
internal fun sourceLabel(command: CommandResult, learned: Boolean, russian: Boolean): String =
    when {
        command is CommandResult.ChatMessage -> if (russian) "ИИ" else "AI"
        learned -> if (russian) "команда · выучена" else "command · learned"
        else -> if (russian) "команда" else "command"
    }

/** What a spoken turn makes of an outcome that is not plain conversation. */
private fun spokenReply(outcome: CommandExecutor.Outcome): String = when (outcome) {
    is CommandExecutor.Outcome.Reply -> outcome.text
    is CommandExecutor.Outcome.Conversation -> outcome.text
    CommandExecutor.Outcome.NeedsScreen -> "Screen analysis available in the app"
    is CommandExecutor.Outcome.NeedsFile -> "File analysis available in the app"
}
