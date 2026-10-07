package com.friday.ai.ui.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.friday.ai.data.backup.SettingsArchive
import com.friday.ai.data.backup.SettingsTransfer
import com.friday.ai.service.FridayWakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings export and import, step by step: pick a file, type the
 * passphrase, (for import) see what the file holds, confirm.
 */
class TransferViewModel(
    private val appContext: Context,
    private val transfer: SettingsTransfer,
    private val modes: com.friday.ai.core.modes.ModeStore? = null,
    private val schedules: com.friday.ai.core.modes.ModeSchedules? = null
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Working : State
        /** A file opened fine; nothing is replaced until [confirmImport]. */
        data class Review(val summary: SettingsTransfer.Summary) : State
        data class Done(val message: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var pending: SettingsArchive.Content? = null

    fun export(uri: Uri, passphrase: CharArray) = work {
        val bytes = transfer.export(passphrase)
        withContext(Dispatchers.IO) {
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: error("Не удалось открыть файл для записи")
        }
        State.Done("Сохранено (${bytes.size / KB} КБ). Храните пароль отдельно от файла.")
    }

    fun open(uri: Uri, passphrase: CharArray) = work {
        val bytes = withContext(Dispatchers.IO) {
            appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Не удалось открыть файл")
        }
        val content = transfer.read(bytes, passphrase)
        pending = content
        State.Review(transfer.summary(content))
    }

    fun confirmImport() {
        val content = pending ?: return
        work {
            transfer.apply(content)
            pending = null
            // The database was replaced under the in-memory copy.
            modes?.load()
            schedules?.let { com.friday.ai.service.ModeAlarms(appContext).rebook(it.all(), emptySet()) }
            // Wake word, thresholds and voice profile are read when the service starts.
            if (FridayWakeWordService.running) {
                FridayWakeWordService.stop(appContext)
                FridayWakeWordService.start(appContext)
            }
            State.Done("Готово: настройки перенесены.")
        }
    }

    fun dismiss() {
        pending = null
        _state.value = State.Idle
    }

    private fun work(block: suspend () -> State) {
        _state.value = State.Working
        viewModelScope.launch {
            _state.value = try {
                block()
            } catch (e: SettingsArchive.Failure) {
                State.Failed(describe(e))
            } catch (e: Exception) {
                Log.e(TAG, "Transfer failed: ${e.javaClass.simpleName}")
                State.Failed(e.message ?: "Не получилось")
            }
        }
    }

    private fun describe(failure: SettingsArchive.Failure): String = when (failure) {
        is SettingsArchive.Failure.NotAnArchive -> "Это не файл настроек Пятницы"
        is SettingsArchive.Failure.WrongPassphraseOrDamaged -> "Неверный пароль, или файл повреждён"
        is SettingsArchive.Failure.NewerFormat ->
            "Файл сделан более новой версией Пятницы — сначала обновите приложение"
    }

    private companion object {
        const val TAG = "Transfer"
        const val KB = 1024
    }
}
