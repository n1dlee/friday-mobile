package com.friday.ai.service.mail

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Access to the user's Gmail through Google Play services.
 *
 * The consent screen is Google's own; Friday never sees the password, and no
 * client secret is compiled in — Google recognises the app by its package
 * name and signing certificate, registered once in Google Cloud. Tokens are
 * cached by Play services, so asking for one on every request is cheap.
 */
class GmailAuth(private val context: Context) {

    sealed interface Result {
        data class Token(val value: String) : Result

        /** Consent is needed; only an Activity can show it. */
        data class NeedsConsent(val intent: PendingIntent) : Result

        data class Failed(val reason: String) : Result
    }

    private companion object {
        const val TAG = "GmailAuth"

        /**
         * One scope covers reading, marking read and sending. Asking for
         * gmail.send separately would only add a second line to the consent
         * screen for the same access.
         */
        const val SCOPE = "https://www.googleapis.com/auth/gmail.modify"

        /** GoogleSignInStatusCodes.SIGN_IN_CANCELLED, without pulling in the legacy API. */
        const val SIGN_IN_CANCELLED = 12501
    }

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .build()

    private val client get() = Identity.getAuthorizationClient(context)

    /** A token if access is already granted; otherwise what is in the way. */
    suspend fun authorize(): Result = suspendCancellableCoroutine { cont ->
        client.authorize(request)
            .addOnSuccessListener { r ->
                val pending = r.pendingIntent
                val token = r.accessToken
                cont.resume(
                    when {
                        r.hasResolution() && pending != null -> Result.NeedsConsent(pending)
                        token != null -> Result.Token(token)
                        else -> Result.Failed("Google не выдал доступ к почте")
                    }
                )
            }
            .addOnFailureListener { e -> cont.resume(Result.Failed(describe(e))) }
    }

    /**
     * Reads the outcome of the consent screen. The reply is read even when
     * the screen did not finish with OK: when Google itself refuses (wrong
     * certificate, account not allowed), the reason is in that reply, and
     * discarding it left only a bare "Вход отменён" to go on.
     */
    fun fromConsent(data: Intent?, completed: Boolean): Result {
        if (data == null) {
            return Result.Failed(
                if (completed) "Google не вернул ответ — попробуйте ещё раз"
                else "Окно входа закрыто без ответа. Если вы не закрывали его сами, Google показал ошибку: " +
                    "проверьте, что приложение опубликовано (Publish app) или ваш аккаунт есть в Test users"
            )
        }
        return try {
            val r = client.getAuthorizationResultFromIntent(data)
            r.accessToken?.let { Result.Token(it) } ?: Result.Failed("Доступ к почте не выдан")
        } catch (e: ApiException) {
            Result.Failed(describe(e))
        }
    }

    /** Drops a token Gmail rejected, so the next request gets a fresh one. */
    suspend fun invalidate(token: String) = withContext(Dispatchers.IO) {
        try {
            GoogleAuthUtil.clearToken(context, token)
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear token: ${e.message}")
        }
    }

    private fun describe(e: Exception): String {
        val code = (e as? ApiException)?.statusCode
        Log.w(TAG, "Authorization failed (code=$code): ${e.message}")
        // Google reports a missing or mismatched OAuth client as a generic
        // internal error (code 8) carrying this status — the code alone hides it.
        if (e.message.orEmpty().contains("UNREGISTERED_ON_API_CONSOLE")) {
            return "Google не нашёл Friday в Google Cloud (UNREGISTERED_ON_API_CONSOLE). В том же проекте, " +
                "где включён Gmail API, нужен OAuth-клиент типа Android: пакет com.friday.ai, " +
                "SHA-1 4B:B3:CB:54:33:D6:E0:EC:B8:4B:4F:AE:F8:15:40:D7:59:F9:82:96. " +
                "Если клиент создан только что, подождите до получаса"
        }
        return when (code) {
            // Not the user's fault and not fixable on the phone: the Google
            // Cloud OAuth client is missing or registered with another
            // package/certificate.
            CommonStatusCodes.DEVELOPER_ERROR ->
                "Google не узнал приложение (код 10). Нужен OAuth-клиент типа Android: " +
                    "пакет com.friday.ai, SHA-1 4B:B3:CB:54:33:D6:E0:EC:B8:4B:4F:AE:F8:15:40:D7:59:F9:82:96"
            CommonStatusCodes.CANCELED, SIGN_IN_CANCELLED ->
                "Вход отменён (код $code). Если окно Google написало «доступ заблокирован», " +
                    "приложение не опубликовано (Publish app) или аккаунт не добавлен в Test users"
            CommonStatusCodes.NETWORK_ERROR -> "Нет сети (код $code)"
            else -> "Google отказал во входе (код $code): ${e.message.orEmpty()}"
        }
    }
}
