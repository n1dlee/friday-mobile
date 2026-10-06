package com.friday.ai.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Whether the system may stop Friday's always-on listener to save battery. */
fun isBatteryRestricted(context: Context): Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return !power.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Asks Android to exempt Friday from battery optimisation. An assistant that
 * must hear its name at any moment cannot be put to sleep, and Samsung's
 * battery manager is known for stopping exactly this kind of service.
 */
@SuppressLint("BatteryLife") // An always-listening assistant is the case this request exists for.
fun requestBatteryExemption(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** Shown under the wake-word switch while the listener could be put to sleep. */
@Composable
fun BackgroundWorkStatus(visible: Boolean) {
    val context = LocalContext.current
    var restricted by remember { mutableStateOf(isBatteryRestricted(context)) }
    // Re-read on return from the system screen, where the choice is made.
    LifecycleResumeEffect(Unit) {
        restricted = isBatteryRestricted(context)
        onPauseOrDispose { }
    }
    if (!visible || !restricted) return

    Column {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Android может усыплять Friday ради экономии батареи — тогда она перестаёт слышать «Пятница». " +
                "Разрешите работу в фоне. На Samsung также: Настройки → Батарея → Ограничения фоновой " +
                "работы → «Приложения без спящего режима» → добавьте Friday.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { requestBatteryExemption(context) }) {
            Text("Разрешить работу в фоне")
        }
    }
}
