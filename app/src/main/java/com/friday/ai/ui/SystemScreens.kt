package com.friday.ai.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Where the phone's digital assistant is chosen (the one the side button
 * calls). Older or unusual phones only have the voice input screen.
 */
fun openDefaultAssistantSettings(context: Context) {
    fun open(action: String) = runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!open(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) open(Settings.ACTION_VOICE_INPUT_SETTINGS)
}
