package com.friday.ai.service.mail

import android.app.PendingIntent
import android.content.Intent
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import com.friday.ai.data.remote.GmailApi

/**
 * Connecting and disconnecting the Gmail account, step by step.
 *
 * Kept out of the settings screen's view model so the screen only shows where
 * the connection stands, and every reason it can fail is worded in one place.
 */
class GmailConnector(
    private val auth: GmailAuth,
    private val api: GmailApi,
    private val prefDao: UserPreferenceDao
) {

    sealed interface Step {
        data class Connected(val account: String) : Step

        /** Google needs the user to approve access; launch [intent] and pass its result to [finish]. */
        data class NeedsConsent(val intent: PendingIntent) : Step

        data class Failed(val reason: String) : Step
    }

    private companion object {
        const val HTTP_FORBIDDEN = 403
    }

    suspend fun account(): String? = prefDao.get(MailAssistant.PREF_ACCOUNT)?.ifBlank { null }

    suspend fun start(): Step = next(auth.authorize())

    /** Continues with what the consent screen returned, whether or not it ended with OK. */
    suspend fun finish(data: Intent?, completed: Boolean): Step = next(auth.fromConsent(data, completed))

    /**
     * Friday forgets the account. Access already granted stays listed under
     * the Google account's third-party connections until removed there.
     */
    suspend fun disconnect() {
        prefDao.set(UserPreferenceEntity(MailAssistant.PREF_ACCOUNT, ""))
    }

    private suspend fun next(result: GmailAuth.Result): Step = when (result) {
        is GmailAuth.Result.NeedsConsent -> Step.NeedsConsent(result.intent)
        is GmailAuth.Result.Failed -> Step.Failed(result.reason)
        is GmailAuth.Result.Token -> try {
            // Asking Gmail who this is doubles as proof the token works.
            val account = api.accountEmail(result.value)
            prefDao.set(UserPreferenceEntity(MailAssistant.PREF_ACCOUNT, account))
            Step.Connected(account)
        } catch (e: Exception) {
            Step.Failed(problem(e))
        }
    }

    /** Access was granted but Gmail itself refused: say which setting is missing. */
    private fun problem(e: Exception): String {
        val text = e.message.orEmpty()
        return when {
            text.contains("has not been used") || text.contains("is disabled") ->
                "Вход прошёл, но в Google Cloud не включён Gmail API " +
                    "(APIs & Services → Library → Gmail API → Enable)"
            e is GmailApi.HttpError && e.code == HTTP_FORBIDDEN ->
                "Gmail запретил доступ (403). Проверьте, что при входе вы разрешили доступ к почте"
            else -> "Gmail не ответил: $text"
        }
    }
}
