package com.friday.ai.service.voice

import com.friday.ai.data.remote.GroqApiException
import com.friday.ai.core.VoiceTurn
import java.io.IOException

/**
 * Turns a failed answer into something worth hearing.
 *
 * "Something went wrong" was all Friday ever said, whatever the cause — so when
 * Groq retired her model there was no way to tell from the phone. Each cause
 * the user can act on gets its own sentence.
 */
object VoiceErrors {

    private const val UNAUTHORISED = 401
    private const val RATE_LIMITED = 429

    fun asr(reason: VoiceTurn.Failure): String = when (reason) {
        VoiceTurn.Failure.AUTH -> "Ключ Groq не подходит. Проверьте его в настройках."
        VoiceTurn.Failure.CONFIG -> "Распознавание Groq недоступно: проверьте настройки."
        VoiceTurn.Failure.NO_KEY -> "Не задан ключ Groq и нет подходящей локальной команды."
        VoiceTurn.Failure.CAPTURE -> "Микрофон недоступен. Попробуйте ещё раз."
        else -> "Распознавание недоступно; локально разобрать запрос не удалось. Проверьте интернет."
    }

    fun localOnly(reason: VoiceTurn.Failure, russian: Boolean): String = when {
        reason == VoiceTurn.Failure.NO_KEY && russian -> asr(reason)
        russian -> "Распознала локально, но для этого запроса нужно облачное распознавание. " +
            "Повторите позже или напишите в Friday."
        else -> "I heard local words, but this request needs cloud recognition. Try again later or type it in Friday."
    }

    fun spoken(e: Throwable, russian: Boolean): String = when {
        e is GroqApiException && e.modelMissing ->
            if (russian) "Groq сменил модель, я уже переключилась. Повторите, пожалуйста."
            else "Groq changed its models; I've switched over. Please say that again."
        e is GroqApiException && e.code == UNAUTHORISED ->
            if (russian) "Ключ Groq не подходит. Проверьте его в настройках."
            else "The Groq key was rejected. Please check it in settings."
        e is GroqApiException && e.code == RATE_LIMITED ->
            if (russian) "Groq просит подождать: исчерпан лимит запросов."
            else "Groq asks me to wait: the request limit is used up."
        e is IOException ->
            if (russian) "Не могу связаться с Groq. Проверьте интернет."
            else "I can't reach Groq. Check the connection."
        else ->
            if (russian) "Groq не ответил: ${e.message ?: e::class.simpleName}"
            else "Groq didn't answer: ${e.message ?: e::class.simpleName}"
    }

    /** Said when the model answered with nothing at all. */
    fun empty(russian: Boolean): String =
        if (russian) "Модель ответила пустотой. Попробуйте ещё раз." else "The model came back empty. Please try again."
}
