package com.friday.ai.ui.diagnostics

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.ai.core.capabilities.Diagnostics
import com.friday.ai.core.capabilities.Diagnostics.Fix
import com.friday.ai.core.capabilities.Diagnostics.Status
import com.friday.ai.service.FridayNotificationListener
import com.friday.ai.ui.settings.requestBatteryExemption
import com.friday.ai.ui.theme.ArcAmber
import com.friday.ai.ui.theme.ArcCyan

/**
 * "Is everything in order?" — every permission, setting and integration a
 * Friday feature depends on, what its absence breaks, and one tap to fix it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    onNavigateBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    // Back from a system screen, the answer may have changed.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val fix = rememberFixer(onOpenSettings) { viewModel.refresh() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Diagnostics", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        val current = rows
        if (current == null) {
            Text("Checking…", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Summary(Diagnostics.problems(current)) }
            Diagnostics.Group.entries.forEach { group ->
                val inGroup = current.filter { it.group == group }
                if (inGroup.isEmpty()) return@forEach
                item {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                }
                items(inGroup, key = { it.id }) { row -> DiagnosticRow(row, onFix = fix) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Summary(problems: Int) {
    val ok = problems == 0
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = (if (ok) ArcCyan else ArcAmber).copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = if (ok) ArcCyan else ArcAmber
            )
            Spacer(Modifier.width(12.dp))
            Text(
                when (problems) {
                    0 -> "Everything Friday needs is in place"
                    1 -> "1 thing needs your attention"
                    else -> "$problems things need your attention"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun DiagnosticRow(row: Diagnostics.Row, onFix: (Fix) -> Unit) {
    val (icon, tint) = statusLook(row.status)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The icon carries the status as well as the colour, so it doesn't rely on colour alone.
        Icon(icon, contentDescription = row.status.name.lowercase(), tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                row.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
        }
        row.fix?.let { fix ->
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onFix(fix) }) { Text("Fix") }
        }
    }
}

@Composable
private fun statusLook(status: Status): Pair<ImageVector, Color> = when (status) {
    Status.OK -> Icons.Filled.CheckCircle to ArcCyan
    Status.PROBLEM -> Icons.Filled.ErrorOutline to ArcAmber
    Status.OFF -> Icons.Filled.RemoveCircleOutline to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    Status.INFO -> Icons.Filled.Info to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
}

/**
 * Turns a [Fix] into the system action that grants it. A runtime permission
 * is requested in place; if Android won't show its dialog any more (denied
 * for good), the app's own settings page is opened instead, since that is
 * then the only place it can be granted.
 */
@Composable
private fun rememberFixer(onOpenSettings: () -> Unit, onChanged: () -> Unit): (Fix) -> Unit {
    val context = LocalContext.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val activity = context.findActivity()
        // Nothing granted and no dialog left to show: only the app's settings page can grant it now.
        // (Coarse location instead of fine is a grant, not a refusal.)
        val refusedForGood = result.values.none { it } && activity != null &&
            result.keys.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (refusedForGood) context.openAppDetails()
        onChanged()
    }
    return { fix ->
        when (fix) {
            Fix.FRIDAY_SETTINGS -> onOpenSettings()
            Fix.OVERLAY -> context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Fix.NOTIFICATION_ACCESS -> FridayNotificationListener.openSettings(context)
            Fix.BATTERY -> requestBatteryExemption(context)
            else -> permissionsFor(fix).takeIf { it.isNotEmpty() }?.let(request::launch)
        }
    }
}

private fun permissionsFor(fix: Fix): Array<String> = when (fix) {
    Fix.MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
    Fix.POST_NOTIFICATIONS ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        else emptyArray()
    Fix.CONTACTS -> arrayOf(Manifest.permission.READ_CONTACTS)
    Fix.PHONE -> arrayOf(Manifest.permission.CALL_PHONE)
    Fix.CALENDAR -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    Fix.LOCATION -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    else -> emptyArray()
}

private fun Context.openAppDetails() = startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
