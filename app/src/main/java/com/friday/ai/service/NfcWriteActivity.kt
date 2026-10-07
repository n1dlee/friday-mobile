package com.friday.ai.service

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.friday.ai.core.modes.ModeTags
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.ErrorColor
import com.friday.ai.ui.theme.FridayTheme
import com.friday.ai.ui.theme.HudBackground
import com.friday.ai.ui.theme.HudOutlinedButton
import com.friday.ai.ui.theme.HudPanel
import com.friday.ai.ui.theme.OnBackground
import com.friday.ai.ui.theme.StatusDot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * "Поднесите метку": writes the mode's tag while this screen is open.
 *
 * The tag gets two records: Friday's own (which mode, signed) and an
 * Android application record, so touching it starts Friday even when she
 * isn't running. Reader mode keeps other apps from grabbing the tag
 * meanwhile.
 */
class NfcWriteActivity : ComponentActivity() {

    private companion object {
        const val TAG = "NfcWrite"
        const val CLOSE_AFTER_MS = 2_000L
    }

    private val tags: AndroidNfcTags by inject()
    private val status = mutableStateOf("Поднесите NFC-метку к задней панели телефона")
    private val state = mutableStateOf(State.WAITING)

    private enum class State { WAITING, DONE, FAILED }

    private val modeId get() = intent.getStringExtra(AndroidNfcTags.EXTRA_MODE_ID).orEmpty()
    private val modeName get() = intent.getStringExtra(AndroidNfcTags.EXTRA_MODE_NAME).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FridayTheme {
                HudBackground {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        HudPanel("NFC-метка · режим $modeName") {
                            StatusDot(
                                when (state.value) {
                                    State.FAILED -> ErrorColor
                                    else -> ArcCyan
                                },
                                live = state.value == State.WAITING
                            )
                            Text(
                                status.value,
                                style = MaterialTheme.typography.titleMedium,
                                color = OnBackground,
                                textAlign = TextAlign.Start
                            )
                            HudOutlinedButton(onClick = { finish() }) { Text("Закрыть") }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = NfcAdapter.getDefaultAdapter(this) ?: return fail("В этом телефоне нет NFC")
        adapter.enableReaderMode(
            this, ::write,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    /** Runs on the NFC thread. */
    private fun write(tag: Tag) {
        lifecycleScope.launch {
            val message = NdefMessage(
                arrayOf(
                    NdefRecord.createMime(ModeTags.MIME, ModeTags.write(modeId, tags.secret())),
                    NdefRecord.createApplicationRecord(packageName)
                )
            )
            val error = withContext(Dispatchers.IO) { runCatching { put(tag, message) }.exceptionOrNull() }
            if (error == null) {
                state.value = State.DONE
                status.value = "Готово. Касание этой метки включает и выключает режим $modeName."
                delay(CLOSE_AFTER_MS)
                finish()
            } else {
                Log.w(TAG, "Write failed: ${error.message}")
                fail("Не получилось записать: ${error.message ?: "метка не поддерживается"}. Попробуйте ещё раз.")
            }
        }
    }

    private fun put(tag: Tag, message: NdefMessage) {
        Ndef.get(tag)?.use { ndef ->
            ndef.connect()
            check(ndef.isWritable) { "метка защищена от записи" }
            check(ndef.maxSize >= message.toByteArray().size) { "на метке мало места" }
            ndef.writeNdefMessage(message)
            return
        }
        NdefFormatable.get(tag)?.use { blank ->
            blank.connect()
            blank.format(message)
            return
        }
        error("метка не поддерживает NDEF")
    }

    private fun fail(text: String) {
        state.value = State.FAILED
        status.value = text
    }
}
