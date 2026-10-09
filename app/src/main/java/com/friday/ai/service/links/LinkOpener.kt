package com.friday.ai.service.links

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Browser
import android.util.Log

/** Opens a quick link in a new Chrome tab, or the default browser when Chrome isn't there. */
class LinkOpener(private val context: Context) {

    private companion object {
        const val TAG = "LinkOpener"
        const val CHROME = "com.android.chrome"
    }

    /** False when no browser could take it. */
    fun open(url: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // A tab of its own each time, not the one Friday opened last.
            .putExtra(Browser.EXTRA_APPLICATION_ID, context.packageName)
            .putExtra(Browser.EXTRA_CREATE_NEW_TAB, true)
        return start(Intent(view).setPackage(CHROME)) || start(view)
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.i(TAG, "No activity for ${intent.`package` ?: "the default browser"}: ${e.message}")
        false
    }
}
