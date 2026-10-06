package com.friday.ai.service.voice

import android.os.SystemClock
import android.util.Log
import com.friday.ai.agent.FridayAgent
import com.friday.ai.core.ConversationControl
import com.friday.ai.core.CorrectionDetector
import com.friday.ai.core.SentenceChunker
import com.friday.ai.core.SystemPromptBuilder
import com.friday.ai.data.remote.GroqApiException
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.data.remote.dto.ApiMessage
import com.friday.ai.domain.model.AssistantMode
import com.friday.ai.service.EdgeTtsSpeaker
import com.friday.ai.service.FridayMemory
import com.friday.ai.service.FridayOverlayManager
import kotlinx.coroutines.CancellationException

/**
 * A conversational answer from the model, spoken as it is written.
 *
 * Each sentence goes to the speaker the moment it is complete, so Friday
 * starts talking while the rest of the answer is still being generated.
 */
class SpokenAnswer(
    private val agent: FridayAgent,
    private val prompts: SystemPromptBuilder,
    private val memory: FridayMemory,
    private val session: VoiceSession,
    private val speaker: EdgeTtsSpeaker,
    private val overlay: FridayOverlayManager
) {

    sealed interface Result {
        /** Spoken in full. [followUp] is false when Friday herself said goodbye. */
        data class Finished(val response: String, val followUp: Boolean) : Result

        /** Nothing usable came back; [message] should be said instead. */
        data class Failed(val message: String) : Result
    }

    private companion object {
        const val TAG = "SpokenAnswer"

        /** Cap on overlay redraws while tokens stream in. */
        const val OVERLAY_REFRESH_MS = 120L

        /** Earlier exchanges sent with the question, for context. */
        const val HISTORY_TURNS = 10

        const val VOICE_RULE =
            "\n\nYou are Friday, a voice assistant. Answer in ONE short, natural sentence — this is a spoken " +
                "conversation, not a chat window. Only go longer than one sentence if the user explicitly asks for " +
                "detail or the request genuinely requires a list/steps. Respond in the same language the user speaks."
    }

    suspend fun answer(userText: String): Result {
        val sessionId = session.id()
        val history = memory.getSessionHistory(sessionId, limit = HISTORY_TURNS)
        val messages = buildList {
            val system = prompts.build(AssistantMode.DEFAULT, memory.buildMemoryContext(), withTools = true) +
                VOICE_RULE
            add(ApiMessage(role = "system", content = system))
            history.forEach {
                add(ApiMessage(role = "user", content = it.userInput))
                add(ApiMessage(role = "assistant", content = it.assistantResponse))
            }
            add(ApiMessage(role = "user", content = userText))
        }

        val russian = session.russian()
        val speech = speaker.beginSpeech()
        return try {
            val response = stream(messages, speech, russian)
            if (response.isBlank()) {
                speech.cancel()
                return Result.Failed(VoiceErrors.empty(russian))
            }
            overlay.show(FridayOverlayManager.State.SPEAKING, response)
            remember(userText, response, assistantSpokeBefore = history.isNotEmpty())
            speech.endInput()
            speech.awaitCompletion()
            // If Friday herself just said goodbye the exchange is over: don't
            // reopen the microphone and sit there in the user's way.
            Result.Finished(response, followUp = !ConversationControl.isFarewell(response))
        } catch (e: CancellationException) {
            speech.cancel()
            throw e
        } catch (e: GroqApiException) {
            speech.cancel()
            Log.e(TAG, "Groq refused: ${e.message}")
            // A retired model: re-read what exists so the next turn works.
            if (e.modelMissing) session.repairModel()
            Result.Failed(VoiceErrors.spoken(e, russian))
        } catch (e: Exception) {
            speech.cancel()
            Log.e(TAG, "Answer failed: ${e.message}")
            Result.Failed(VoiceErrors.spoken(e, russian))
        } finally {
            // Never leave a session waiting on input that will never arrive.
            speech.endInput()
        }
    }

    private suspend fun stream(
        messages: List<ApiMessage>,
        speech: EdgeTtsSpeaker.SpeechSession,
        russian: Boolean
    ): String {
        val response = StringBuilder()
        val chunker = SentenceChunker()
        var speaking = false
        var lastRedraw = 0L

        val settings = FridayAgent.Settings(
            session.apiKey(), session.model(), GroqApiService.VOICE_MAX_TOKENS, session.backupModel()
        )
        agent.reply(settings, messages, russian)
            .collect { token ->
                response.append(token)
                chunker.append(token).forEach { sentence ->
                    speech.offer(sentence)
                    speaking = true
                }
                // Throttled: redrawing on every token queued hundreds of
                // main-thread posts per reply.
                val now = SystemClock.uptimeMillis()
                if (now - lastRedraw >= OVERLAY_REFRESH_MS) {
                    lastRedraw = now
                    overlay.show(
                        if (speaking) FridayOverlayManager.State.SPEAKING else FridayOverlayManager.State.PROCESSING,
                        response.toString()
                    )
                }
            }
        chunker.flush()?.let { speech.offer(it) }
        return response.toString()
    }

    private suspend fun remember(userText: String, response: String, assistantSpokeBefore: Boolean) {
        session.log(userText, response, "voice_chat")
        memory.extractMemoriesInBackground(userText, response)
        // A correction is the strongest signal there is, so it is stored on
        // its own rather than left to the general extractor.
        CorrectionDetector.detect(userText, assistantSaidSomething = assistantSpokeBefore)
            ?.let { memory.recordCorrection(it) }
        session.mirror(userText, response)
    }
}
