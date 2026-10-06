package com.friday.ai.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.friday.ai.core.ErrandPlaces
import com.friday.ai.data.local.dao.ErrandDao
import com.friday.ai.data.remote.NearbyPlacesService
import java.util.concurrent.TimeUnit

/**
 * Checks, now and then, whether you're near somewhere that would let you do
 * one of your open errands.
 *
 * Real geofences need coordinates up front, but the shops aren't known until
 * we look — so this samples location on a schedule instead and asks
 * OpenStreetMap what's around. It only runs while there are open errands, so
 * the battery cost disappears when there's nothing to watch for.
 */
class ErrandWatchWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ErrandWatch"
        private const val WORK_NAME = "friday_errand_watch"
        const val CHANNEL_ID = "friday_errands"
        private const val NOTIFICATION_BASE_ID = 3000

        /** Frequent enough to be useful, rare enough not to drain the battery. */
        private const val INTERVAL_MINUTES = 20L

        /** Don't mention the same errand again within this window. */
        private const val RENOTIFY_QUIET_MS = 2L * 60 * 60 * 1000

        /** A fix older than this says nothing about where you are now. */
        private const val MAX_FIX_AGE_MS = 15L * 60 * 1000

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ErrandWatchWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
            Log.i(TAG, "Errand watch scheduled every $INTERVAL_MINUTES min")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val errandDao: ErrandDao = org.koin.java.KoinJavaComponent.get(ErrandDao::class.java)

        val errands = runCatching { errandDao.active() }.getOrDefault(emptyList())
        if (errands.isEmpty()) {
            // Nothing to watch for — stop burning cycles until there is.
            cancel(applicationContext)
            return Result.success()
        }

        if (!hasLocationPermission()) {
            Log.w(TAG, "No location permission; skipping")
            return Result.success()
        }

        val fix = lastKnownLocation() ?: run {
            Log.i(TAG, "No recent location fix")
            return Result.success()
        }

        val places: NearbyPlacesService =
            org.koin.java.KoinJavaComponent.get(NearbyPlacesService::class.java)
        val now = System.currentTimeMillis()

        for (errand in errands) {
            if (now - errand.lastNotifiedAt < RENOTIFY_QUIET_MS) continue

            val types = ErrandPlaces.placeTypesFor(errand.what)
            if (types.isEmpty()) continue

            val nearby = places.near(fix.latitude, fix.longitude, types)
            if (nearby.isEmpty()) continue

            val nearest = nearby.first()
            notifyNearby(errand.id, errand.what, nearest.distanceMeters, nearby.size)
            runCatching { errandDao.markNotified(errand.id, now) }

            // One prompt at a time; being told about three errands at once in
            // the street is noise, not help.
            break
        }

        return Result.success()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Uses the last known fix rather than requesting a new one: waking the GPS
     * every twenty minutes would be a real battery cost for a feature that
     * only needs rough position.
     */
    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? = try {
        val lm = applicationContext
            .getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val now = System.currentTimeMillis()
        lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time < MAX_FIX_AGE_MS }
            .maxByOrNull { it.time }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read location: ${e.message}")
        null
    }

    private fun notifyNearby(errandId: Long, what: String, meters: Int, count: Int) {
        val manager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Errand reminders", NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Reminders when you're near a useful place" }
            )
        }

        val text = ErrandPlaces.nearbyPrompt(what, meters, count, russian = true)

        val open = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
        val pending = PendingIntent.getActivity(
            applicationContext, errandId.toInt(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        manager.notify(
            NOTIFICATION_BASE_ID + errandId.toInt(),
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle("Friday")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        )
        Log.i(TAG, "Prompted about '$what' — nearest ${meters}m")
    }
}
