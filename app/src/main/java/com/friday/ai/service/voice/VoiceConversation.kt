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
import com.friday.ai.core.voice.OwnerCheck
import com.friday.ai.core.voice.VoiceStage

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
    val focus: AudioFocusHold? = null,
    /** True while a phone or VoIP call is in progress. */
    val inCall: () -> Boolean = { false },
    val diagnostics: com.friday.ai.core.voice.VoiceDiagnostics = VoiceTelemetry.diagnostics
)

/** Owns a cancellable spoken exchange; transitions and recorder handover run on [scope]. */
@Suppress("TooManyFunctions") // one function per step of a spoken exchange
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
    private val diagnostics = io.diagnostics

    private var answering = false
    private var interrupted = false
    private val turns = VoiceTurnJobs(scope, interrupt = {
        diagnostics.cancel()
        interrupted = speaking || answering
        speaking = false
        answering = false
        io.transcriber.abort()
        io.offline.abort()
        io.speaker.stop()
    }, blocked = {
        io.overlay.error("Микрофон ещё освобождается — вызовите Пятницу снова", ERROR_MS)
    })

    /**
     * What this conversation is transcribed in: Russian when the owner called
     * "Пятница", Whisper's own guess when they called "Friday".
     */
    private var language: String? = "ru"

    /** True while Friday is talking, so a wake word means "interrupt". */
    @Volatile
    var speaking = false
        private set

    /**
     * The owner said "Пятница" (already verified by the wake listener).
     *
     * With [continuing], they went straight on ("Пятница, включи музыку"):
     * no greeting — it would talk over them — and the recording starts at
     * once, with [lead] (the audio up to the name) in front so the first
     * words of the command aren't lost.
     */
    fun onWake(heard: String, continuing: Boolean = false, lead: FloatArray? = null) {
        turns.replace { mine ->
            diagnostics.begin(mine)
            // The owner called: their session, which may light the lock screen.
            io.overlay.begin(userInitiated = true)
            language = transcriptionLanguage(heard)
            // Music stops before anything is said or heard: the recording is
            // the user's voice, not the user's voice over a song.
            io.focus?.take()
            val bargedIn = interrupted
            if (bargedIn) {
                Log.i(TAG, "Interrupted while speaking")
            }
            io.notifier.update("Listening...")

            // Cutting Friday off and then being greeted would be absurd —
            // that user already knows they were heard.
            if (bargedIn || continuing) {
                io.overlay.listening()
                listen(mine, isFollowUp = false, lead = lead.takeIf { continuing })
                return@replace
            }

            val greeting = acknowledgements.next(WakePhrases.detectLanguage(heard))
            // Shown before it is spoken: the panel appears at once, while the
            // voice still needs a round-trip.
            io.overlay.greet(greeting)
            speaking = true
            io.speaker.speakObserved(greeting, { event -> diagnostics.playback(mine, event) }) {
                turns.launch(mine) {
                    speaking = false
                    io.overlay.listening()
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
            // Friday's own announcement: shown where allowed, never lights the screen.
            io.overlay.begin(userInitiated = false)
            turns.replace { mine ->
                diagnostics.begin(mine)
                // Only when an answer is awaited: a ringing phone keeps its ringtone.
                if (listenAfter) io.focus?.take()
                speakAndContinue(mine, text, allowFollowUp = listenAfter)
            }
        }
    }

    /**
     * Records one voice turn and handles it. A follow-up (Friday has just
     * answered and listens on, with no new wake word) ends quietly on silence
     * instead of complaining "не расслышала".
     */
    internal suspend fun listen(mine: Int, isFollowUp: Boolean, attempt: Int = 1, lead: FloatArray? = null) {
        io.transcriber.listener = feedback(mine)
        // The wake listener may still hold the microphone (it listens while
        // Friday speaks, so she can be interrupted). Two recorders at once
        // left one of them with silence: "Не расслышала" for no reason.
        io.wake.stop()
        // With no network Whisper cannot transcribe at all; fall back to the
        // on-device grammar rather than failing silently.
        if (io.offline.isOffline()) {
            offlineTurn(mine)
            return
        }
        val apiKey = session.apiKey()
        if (apiKey.isBlank() && io.wake.voskModel == null) {
            diagnostics.heard(mine, VoiceTurn.Outcome.Unavailable(VoiceTurn.Failure.NO_KEY))
            diagnostics.stage(mine, VoiceStage.FAILED)
            io.overlay.error("Не задан ключ Groq — откройте настройки Friday", ERROR_MS)
            return
        }

        io.transcriber.language = language
        val outcome = if (lead != null) io.transcriber.recordAndTranscribe(apiKey, gate.commandCheck(), lead)
        else io.transcriber.recordAndTranscribe(apiKey, gate.commandCheck())
        if (!turns.current(mine)) {
            // Interrupted while this was recording: the new turn owns the
            // conversation now, and this audio was its wake word anyway.
            Log.i(TAG, "Dropped a recording from an interrupted turn")
            return
        }
        diagnostics.heard(mine, outcome)
        // Said in one breath with the name: the transcript starts with it too.
        val command = (outcome as? VoiceTurn.Outcome.Heard)?.let { WakePhrases.stripCall(it.text) }
        // Only the name after all: greet and listen, as for a plain call.
        if (lead != null && command != null && command.isBlank()) onWake("", continuing = false)
        else next(mine, outcome, command, isFollowUp, attempt)
    }

    private suspend fun transcriptionLanguage(heard: String): String? = when (WakePhrases.detectLanguage(heard)) {
        WakePhrases.Language.ENGLISH -> null
        WakePhrases.Language.RUSSIAN -> "ru"
        // The side button, the tile: no name was said, so the owner's usual language.
        null -> if (session.russian()) "ru" else null
    }

    private suspend fun next(
        mine: Int,
        outcome: VoiceTurn.Outcome,
        command: String?,
        isFollowUp: Boolean,
        attempt: Int
    ) {
        if (outcome is VoiceTurn.Outcome.Unavailable) {
            diagnostics.stage(mine, VoiceStage.FAILED)
            io.overlay.error(VoiceErrors.asr(outcome.reason), ERROR_MS)
            return
        }
        if (outcome is VoiceTurn.Outcome.Heard && outcome.degradedBy != null) {
            runDegraded(mine, command ?: outcome.text, outcome.degradedBy)
            return
        }
        when (VoiceTurn.next(outcome, isFollowUp, attempt, MAX_LISTEN_ATTEMPTS)) {
            VoiceTurn.Next.PROCESS -> heard(mine, command ?: (outcome as VoiceTurn.Outcome.Heard).text)
            VoiceTurn.Next.LISTEN_AGAIN -> {
                Log.i(TAG, "Attempt $attempt gave $outcome; listening again")
                io.overlay.listening()
                listen(mine, isFollowUp = false, attempt = attempt + 1)
            }
            VoiceTurn.Next.END_QUIETLY -> {
                diagnostics.stage(mine, VoiceStage.FINISHED)
                if (outcome is VoiceTurn.Outcome.Stranger) Log.i(TAG, "Follow-up from another voice ignored")
                // "Отправить?" with no answer heard: it does not wait for a "да" said later.
                if (commands.dropPending()) {
                    io.overlay.showResult("Не расслышала ответ — ничего не отправила", NOTICE_MS)
                } else {
                    io.overlay.dismiss()
                }
            }
            // On screen only: saying it aloud adds noise to a turn that
            // already went nowhere.
            VoiceTurn.Next.SAY_NOT_HEARD -> io.overlay.showResult("Не расслышала", NOTICE_MS)
            VoiceTurn.Next.SAY_NOT_RECOGNISED -> io.overlay.showResult("Не узнала голос", NOTICE_MS)
        }
    }

    private suspend fun runDegraded(mine: Int, text: String, reason: VoiceTurn.Failure) {
        if (ConversationControl.isFarewell(text)) {
            close()
            return
        }
        // Uncertain local words must not confirm a send or become unrestricted agent input.
        commands.dropPending()
        io.overlay.source("локально")
        val command = commands.route(text)
        if (!OfflineCommands.isOfflineCapable(command) || command is CommandResult.SendMessage) {
            speakAndContinue(mine, VoiceErrors.localOnly(reason, session.russian()), false)
            return
        }
        io.overlay.processing(text)
        val reply = spokenReply(commands.execute(command, session.russian()))
        session.log(text, reply, "local_fallback")
        session.mirror(text, reply)
        if (turns.current(mine)) speakAndContinue(mine, reply, allowFollowUp = false)
    }

    private suspend fun heard(mine: Int, text: String) {
        val russian = session.russian()
        // A pending "Отправить?" is answered before anything else — in
        // particular before "нет" can be taken for a goodbye.
        val confirmed = commands.answerPending(text, russian)
        when {
            confirmed != null -> {
                session.mirror(text, confirmed)
                // A name picked from "чьё прочитать?" is read out; "ответь …" may follow.
                speakAndContinue(mine, confirmed, allowFollowUp = commands.awaitsMore())
            }
            ConversationControl.isFarewell(text) -> endConversation()
            else -> {
                // "Открой камеру, на этом пока": done, answered, and then quiet.
                val request = ConversationControl.beforeFarewell(text)
                process(mine, request ?: text, russian, last = request != null)
            }
        }
    }

    /** @param last the owner said goodbye after this: answer, then stop listening. */
    private suspend fun process(mine: Int, text: String, russian: Boolean, last: Boolean = false) {
        val command = commands.route(text)
        // A silent quick link: opened, and nothing shown, said or kept.
        if (command is CommandResult.OpenLink && command.silent) {
            commands.execute(command, russian)
            if (turns.current(mine)) io.overlay.dismiss()
            return
        }
        io.notifier.update("Processing...")
        diagnostics.stage(mine, VoiceStage.THINKING)
        io.overlay.processing(text)
        io.overlay.source(sourceLabel(command, commands.lastRouteLearned, russian))
        val outcome = commands.execute(command, russian)
        if (outcome is CommandExecutor.Outcome.Conversation) {
            answering = true
            val result = try {
                answers.answer(outcome.text, { turns.current(mine) }) { event ->
                    turns.launch(mine) {
                        diagnostics.playback(mine, event)
                        if (event.phase == com.friday.ai.core.voice.PlaybackPhase.PLAYING) {
                            speaking = true
                            io.wake.listen()
                        } else if (event.phase == com.friday.ai.core.voice.PlaybackPhase.WAITING) {
                            speaking = false
                        }
                    }
                }
            } finally {
                if (turns.current(mine)) answering = false
            }
            when (val r = result) {
                is SpokenAnswer.Result.Finished ->
                    if (turns.current(mine)) afterSpeaking(mine, r.response, r.followUp && !last)
                is SpokenAnswer.Result.Failed -> if (turns.current(mine)) speakAndContinue(mine, r.message, false)
            }
            return
        }
        val reply = spokenReply(outcome)
        session.log(text, reply, command::class.simpleName)
        session.mirror(text, reply)
        if (turns.current(mine)) speakAndContinue(mine, reply, allowFollowUp = !last)
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
        io.overlay.listening()
        diagnostics.stage(mine, VoiceStage.LISTENING)
        val heard = io.offline.listenForCommand(modelPath)
        if (turns.current(mine)) {
            diagnostics.heard(mine, heard?.let { VoiceTurn.Outcome.Heard(it, VoiceTurn.Origin.LOCAL) }
                ?: VoiceTurn.Outcome.Nothing)
        }
        when {
            !turns.current(mine) -> Unit
            heard.isNullOrBlank() -> io.overlay.dismiss()
            else -> runOffline(mine, heard)
        }
    }

    private suspend fun runOffline(mine: Int, heard: String) {
        if (ConversationControl.isFarewell(heard)) {
            close()
            return
        }
        io.overlay.processing(heard)
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
        // A call has just started (often one Friday placed): she doesn't talk
        // over it, and the microphone is the call's, not hers.
        if (io.inCall()) {
            speaking = false
            io.overlay.showResult(text, RESULT_LINGER_MS)
            return
        }
        io.overlay.speaking(text)
        speaking = true
        io.wake.listen()
        io.speaker.speakObserved(text, { event -> diagnostics.playback(mine, event) }) {
            turns.launch(mine) { afterSpeaking(mine, text, allowFollowUp) }
        }
    }

    /** Either keep the conversation open, or show the answer briefly and step back. */
    private suspend fun afterSpeaking(mine: Int, spokenText: String, allowFollowUp: Boolean) {
        speaking = false
        // Never open the microphone for a follow-up during a call.
        if (allowFollowUp && !io.inCall()) {
            diagnostics.stage(mine, VoiceStage.LISTENING)
            io.overlay.listening()
            listen(mine, isFollowUp = true)
        } else {
            diagnostics.stage(mine, VoiceStage.FINISHED)
            io.overlay.showResult(spokenText, RESULT_LINGER_MS)
        }
    }

    /** The owner closed the panel: stop talking and listening; what was done stays done. */
    fun close() {
        turns.close {
            commands.dropPending()
            io.overlay.dismiss()
        }
    }

    fun destroy() = turns.destroy()

    private fun endConversation() {
        Log.i(TAG, "User ended the conversation")
        close()
    }

    private fun feedback(mine: Int) = object : WhisperTranscriber.Listener {
        override fun onRecordingStarted() {
            if (turns.current(mine)) {
                diagnostics.stage(mine, VoiceStage.LISTENING)
                io.overlay.hearing(false)
            }
        }
        override fun onTranscriptionStarted() { diagnostics.stage(mine, VoiceStage.TRANSCRIBING) }
        override fun onOwnerCheckStarted() { diagnostics.owner(mine, OwnerCheck.CHECKING) }
        override fun onOwnerChecked(accepted: Boolean) {
            diagnostics.owner(mine, if (accepted) OwnerCheck.ACCEPTED else OwnerCheck.REJECTED)
        }
        override fun onSpeechDetected() { if (turns.current(mine)) io.overlay.hearing(true) }
        override fun onRecordingFinished() { if (turns.current(mine)) io.overlay.processing(null) }
        override fun onError(message: String) { if (turns.current(mine)) io.overlay.error(message, ERROR_MS) }
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
    CommandExecutor.Outcome.Silent -> ""
}
