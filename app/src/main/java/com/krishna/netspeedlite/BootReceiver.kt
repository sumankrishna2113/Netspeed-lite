package com.krishna.netspeedlite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val showSpeed = prefs.getBoolean(Constants.PREF_SHOW_SPEED, true)
        val isAlertEnabled = prefs.getBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, false)

        if ((showSpeed || isAlertEnabled) && (intent.action == Intent.ACTION_BOOT_COMPLETED ||
                    intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
                    intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                    intent.action == "com.krishna.netspeedlite.RESTART_SERVICE")) {

            val serviceIntent = Intent(context, SpeedService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        context.startForegroundService(serviceIntent)
                    } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
                        // Android 12+ restricts starting foreground services from the background.
                        // Schedule WorkManager as fallback - it will retry when conditions allow
                        android.util.Log.w("BootReceiver", "BG launch restricted (Android 12+), scheduling WorkManager fallback")
                        scheduleWorkerFallback(context)
                    } catch (e: Exception) {
                        android.util.Log.w("BootReceiver", "BG launch failed: ${e.message}, scheduling WorkManager fallback")
                        scheduleWorkerFallback(context)
                    }
                } else {
                    context.startForegroundService(serviceIntent)
                }
            } catch (e: Exception) {
                android.util.Log.e("BootReceiver", "Failed to start service, scheduling WorkManager fallback", e)
                scheduleWorkerFallback(context)
            }
        }
    }

    private fun scheduleWorkerFallback(context: Context) {
        try {
            // Schedule a one-time worker to retry starting the service after a short delay
            // This gives the system time to allow foreground service starts (e.g., user interaction)
            val workRequest = OneTimeWorkRequestBuilder<SpeedServiceWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS)
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
        } catch (e: Exception) {
            android.util.Log.e("BootReceiver", "Failed to schedule worker fallback", e)
        }
    }
}