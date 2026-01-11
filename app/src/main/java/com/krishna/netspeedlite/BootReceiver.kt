package com.krishna.netspeedlite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val showSpeed = prefs.getBoolean(Constants.PREF_SHOW_SPEED, true)

        if (showSpeed && (intent.action == Intent.ACTION_BOOT_COMPLETED ||
                    intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
                    intent.action == "com.krishna.netspeedlite.RESTART_SERVICE")) {

            val serviceIntent = Intent(context, SpeedService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android 12+ may throw ForegroundServiceStartNotAllowedException
                    // when started from broadcast receiver - catch and handle gracefully
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            context.startForegroundService(serviceIntent)
                        } catch (e: Exception) {
                            // On Android 12+, we can't start foreground service from background
                            // unless it qualifies for an exemption (like BOOT_COMPLETED).
                            // RESTART_SERVICE might fail here if app is in background.
                            android.util.Log.w("BootReceiver", "Cannot start service from boot receiver on Android 12+", e)
                        }
                    } else {
                        context.startForegroundService(serviceIntent)
                    }
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                // Log but don't crash - service will start when user opens app
                android.util.Log.e("BootReceiver", "Failed to start service on boot", e)
            }
        }
    }
}