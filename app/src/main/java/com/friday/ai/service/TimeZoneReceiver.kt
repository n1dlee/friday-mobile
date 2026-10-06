package com.friday.ai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.friday.ai.data.local.dao.UserPreferenceDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-books the morning brief when the phone's clock or time zone changes.
 *
 * A booked brief is a delay in milliseconds, worked out in the zone the phone
 * was in at the time. Land in another country and that delay points at the
 * old zone's morning, so it is recomputed against the new one.
 */
class TimeZoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED &&
            intent.action != Intent.ACTION_TIME_CHANGED
        ) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefDao: UserPreferenceDao =
                    org.koin.java.KoinJavaComponent.get(UserPreferenceDao::class.java)
                if (prefDao.get(MorningBriefWorker.PREF_ENABLED) != "false") {
                    val hour = prefDao.get(MorningBriefWorker.PREF_HOUR)?.toIntOrNull()
                        ?: MorningBriefWorker.DEFAULT_HOUR
                    MorningBriefWorker.schedule(context, hour, replace = true)
                    Log.i(TAG, "Clock changed (${intent.action}); brief re-booked")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "TimeZoneReceiver"
    }
}
