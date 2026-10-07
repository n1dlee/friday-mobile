package com.friday.ai.service

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.friday.ai.AssistActivity
import com.friday.ai.R
import com.friday.ai.core.modes.Mode

/**
 * "Пятница" in Quick Settings: pull the shade down, tap, talk. The same as
 * the side button: it opens [AssistActivity], which starts a conversation.
 * On a locked phone Android asks to unlock first.
 */
class FridayTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.app_name)
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(::open) else open()
    }

    private fun open() {
        val intent = Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

/**
 * Long press on Friday's icon: the modes used most, one tap to turn each on
 * or off. Kept in step with the modes as they change.
 */
object ModeShortcuts {

    const val ACTION = "com.friday.ai.MODE"
    const val EXTRA_ID = "mode_id"
    private const val MAX = 4

    fun publish(context: Context, modes: List<Mode>) {
        val shortcuts = modes.sortedByDescending { it.runCount }.take(MAX).map { mode ->
            ShortcutInfoCompat.Builder(context, "mode:${mode.id}")
                .setShortLabel("Режим ${mode.name}".take(SHORT_LABEL))
                .setLongLabel("Режим ${mode.name}: включить или выключить")
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_reactor_small))
                .setIntent(
                    Intent(context, ModeShortcutActivity::class.java).setAction(ACTION).putExtra(EXTRA_ID, mode.id)
                )
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    private const val SHORT_LABEL = 25
}

/** A mode's launcher shortcut: toggles it through the voice service, which says what happened. */
class ModeShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(ModeShortcuts.EXTRA_ID)?.let { FridayWakeWordService.toggleMode(this, it) }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
