package com.friday.ai.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.HudButton
import com.friday.ai.ui.theme.HudNote
import com.friday.ai.ui.theme.HudOutlinedButton
import com.friday.ai.ui.theme.HudPanel
import com.friday.ai.ui.theme.HudReadout
import com.friday.ai.ui.theme.OnBackground
import com.friday.ai.ui.theme.hudFieldColors
import org.koin.androidx.compose.koinViewModel

private fun locationGranted(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun LocationPanel(viewModel: LocationViewModel = koinViewModel()) {
    val context = LocalContext.current
    val saved by viewModel.city.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(saved) { typed = saved }
    var granted by remember { mutableStateOf(locationGranted(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    HudPanel("Местоположение", index = 12) {
        HudNote(
            "Для погоды без названия места. Город, заданный здесь (или словами «мой город — Геттисберг»), " +
                "главнее всего; без него — где телефон, если разрешено; иначе — город часового пояса."
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Город по умолчанию") },
            placeholder = { Text("Пусто — по геолокации") },
            singleLine = true,
            colors = hudFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                HudButton(onClick = { viewModel.save(typed) }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
            }
            Box(Modifier.weight(1f)) {
                HudOutlinedButton(onClick = { viewModel.save("") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Убрать")
                }
            }
        }
        result?.let { HudNote(it) }
        HudReadout(
            "Геолокация",
            if (granted) "РАЗРЕШЕНА" else "НЕ РАЗРЕШЕНА",
            valueColor = if (granted) ArcCyan else OnBackground
        )
        if (!granted) {
            HudOutlinedButton(
                onClick = { ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Разрешить приблизительное местоположение") }
        }
    }
}
