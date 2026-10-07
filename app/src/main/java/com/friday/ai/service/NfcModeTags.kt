package com.friday.ai.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import com.friday.ai.core.modes.ModeTags
import com.friday.ai.core.modes.NfcTags
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import java.security.SecureRandom

/**
 * NFC tags for modes on this phone: the signing secret, and starting the
 * screen that writes a tag.
 */
class AndroidNfcTags(private val context: Context, private val prefs: UserPreferenceDao) : NfcTags {

    companion object {
        private const val PREF_SECRET = "nfc_secret"
        private const val SECRET_BYTES = 32
        private const val HEX = 16
        const val EXTRA_MODE_ID = "mode_id"
        const val EXTRA_MODE_NAME = "mode_name"
    }

    override fun available(): Boolean = NfcAdapter.getDefaultAdapter(context)?.isEnabled == true

    override fun startWriting(modeId: String, modeName: String) {
        context.startActivity(
            Intent(context, NfcWriteActivity::class.java)
                .putExtra(EXTRA_MODE_ID, modeId)
                .putExtra(EXTRA_MODE_NAME, modeName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Made once, kept sealed like the API keys, and moved by settings export so old tags keep working. */
    suspend fun secret(): ByteArray {
        prefs.get(PREF_SECRET)?.takeIf { it.isNotBlank() }?.let { return hex(it) }
        val fresh = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)
        prefs.set(UserPreferenceEntity(PREF_SECRET, fresh.joinToString("") { "%02x".format(it) }))
        return fresh
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(HEX).toByte() }.toByteArray()
}

/**
 * A Friday tag was touched, whatever was open (or nothing): Android starts
 * this from the tag's MIME type. It hands the payload to the voice service,
 * which toggles the mode and says what happened, and closes.
 */
class NfcTagActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        payload(intent)?.let { FridayWakeWordService.tag(this, it) }
            ?: Log.w("NfcTagActivity", "Not a Friday tag")
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun payload(intent: Intent): ByteArray? {
        @Suppress("DEPRECATION")
        val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return null
        return messages.filterIsInstance<NdefMessage>()
            .flatMap { it.records.toList() }
            .firstOrNull { it.tnf == NdefRecord.TNF_MIME_MEDIA && String(it.type) == ModeTags.MIME }
            ?.payload
    }
}
