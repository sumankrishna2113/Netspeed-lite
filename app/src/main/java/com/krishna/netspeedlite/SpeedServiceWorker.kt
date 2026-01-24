package com.krishna.netspeedlite

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters

class SpeedServiceWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val showSpeed = prefs.getBoolean(Constants.PREF_SHOW_SPEED, true)
        val isAlertEnabled = prefs.getBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, false)

        if (showSpeed || isAlertEnabled) {
            val serviceIntent = Intent(applicationContext, SpeedService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        applicationContext.startForegroundService(serviceIntent)
                    } catch (e: Exception) {
                        // Background launch restrictions on Android 12+ (API 31+)
                        // We can't force it if the system says no.
                        android.util.Log.w("SpeedServiceWorker", "Could not restart service (BG restricted): ${e.message}")
                    } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
                         android.util.Log.w("SpeedServiceWorker", "BG launch restricted (Android 12+): ${e.message}")
                    }
                } else {
                    applicationContext.startForegroundService(serviceIntent)
                }
            } catch (e: Exception) {
                android.util.Log.e("SpeedServiceWorker", "Failed to start service", e)
                return Result.failure()
            }
        }
        return Result.success()
    }
}
