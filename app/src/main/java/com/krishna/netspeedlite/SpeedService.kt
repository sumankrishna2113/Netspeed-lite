package com.krishna.netspeedlite

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SpeedService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastRx = 0L
    private var lastTx = 0L
    private val interval = 1000L
    private var tickCount = 0

    // Channels
    private val channelId = "speed_channel_v5"
    private val alertChannelId = "data_alert_channel"
    private val notificationId = 1
    
    // ⭐ Fixed timestamp: marks this notification as "older" than new events (like Downloads)
    private val serviceStartTime = System.currentTimeMillis()

    private lateinit var prefs: SharedPreferences
    private lateinit var networkStatsManager: NetworkStatsManager
    
    // Coroutine Scope for background tasks
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    // 🔁 UPDATE LOOP
    private val runnable = object : Runnable {
        override fun run() {
            updateNotificationData()
            
            // OPTIMIZED ALERT CHECK
            // Check every 5 seconds instead of 60 seconds for faster feedback
            // NetworkStatsManager query is relatively lightweight but we still respect battery
            if (tickCount % 5 == 0) {
                checkDataAlerts()
            }
            tickCount++
            
            handler.postDelayed(this, interval)
        }
    }

    override fun onCreate() {
        super.onCreate()

        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        networkStatsManager = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

        createNotificationChannel()
        createAlertChannel()

        lastRx = TrafficStats.getTotalRxBytes()
        lastTx = TrafficStats.getTotalTxBytes()

        // 🚨 MUST START FOREGROUND IMMEDIATELY
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Internet Speed")
            .setContentText("Starting...")
            .setSmallIcon(R.drawable.ic_speed)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(getOpenAppIntent())
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) 
            .setWhen(serviceStartTime)
            .setShowWhen(false)
            .build()

        startForeground(notificationId, notification)

        handler.post(runnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // We handle resets in checkDataAlerts now
        return START_STICKY
    }

    private fun checkDataAlerts() {
        // Use "daily_limit_enabled" to match MainActivity
        if (!prefs.getBoolean("daily_limit_enabled", false)) return
        
        // Launch in background to avoid blocking UI thread (update loop)
        serviceScope.launch {
            if (!hasUsageStatsPermission()) return@launch

            val limitMb = prefs.getFloat("daily_limit_mb", 0f)
            if (limitMb <= 0f) return@launch
            
            // Shared Preference Reset Logic (Check reset before querying to ensure correct day)
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val lastChecked = prefs.getString("last_alert_date", "")

            if (todayStr != lastChecked) {
                // It's a new day, reset flags
                prefs.edit()
                    .putString("last_alert_date", todayStr)
                    .putBoolean("alert_80_triggered", false)
                    .putBoolean("alert_100_triggered", false)
                    .apply()
            }
            
            val alert80 = prefs.getBoolean("alert_80_triggered", false)
            val alert100 = prefs.getBoolean("alert_100_triggered", false)

            // OPTIMIZATION: If both alerts are already triggered, stop querying NetworkStatsManager
            // This saves battery once the daily limit is reached and acknowledged.
            if (alert80 && alert100) return@launch

            val limitBytes = (limitMb * 1024 * 1024).toLong()
            val (mobileUsage, _) = getTodayUsageSync() // This calls NetworkStatsManager

            val percentage = (mobileUsage.toDouble() / limitBytes.toDouble()) * 100
            
            if (percentage >= 100 && !alert100) {
                 sendAlertNotification(
                    "Daily data limit reached",
                    "Daily data limit reached."
                )
                prefs.edit().putBoolean("alert_100_triggered", true).apply()
            } else if (percentage >= 80 && !alert80 && !alert100) {
                 sendAlertNotification(
                    "Data Warning",
                    "You've used 80% of your daily data limit."
                )
                prefs.edit().putBoolean("alert_80_triggered", true).apply()
            }
        }
    }

    private fun sendAlertNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, alertChannelId)
            .setSmallIcon(R.drawable.ic_speed)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(getOpenAppIntent())
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun updateNotificationData() {

        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()

        val rxDelta = rx - lastRx
        val txDelta = tx - lastTx
        val totalBytes = rxDelta + txDelta

        lastRx = rx
        lastTx = tx

        // 🔢 AUTO UNIT SWITCH
        val (speedVal, unitVal) =
            if (totalBytes >= 1_024_000) {
                val mb = totalBytes / 1_048_576f
                if (mb >= 10) {
                    Pair(String.format(Locale.US, "%.0f", mb), "MB/s")
                } else {
                    Pair(String.format(Locale.US, "%.1f", mb), "MB/s")
                }
            } else {
                Pair((totalBytes / 1024).toString(), "KB/s")
            }

        // 📋 DETAILS TEXT
        val details = StringBuilder()

        // ALWAYS Show today's usage if permission granted
        if (hasUsageStatsPermission()) {
            // Need to run getTodayUsage, but it's slow-ish. 
            // Running it on main thread here every second might cause jank.
            // However, typical implementations do this. 
            // If optimization is needed, we can cache usage and update every minute.
            // For now, to keep "Real Time", we call it.
            // But getTodayUsage calls NetworkStatsManager which is IPC.
            // Let's optimize: Only update usage text every 5 seconds?
            // Or just do it. Modern phones handle it okay.
            
            // optimization: only update usage string every 5th tick
            // But users like real-time updates.
            // We'll keep it for now.
            
            // NOTE: We should execute this carefully.
            // For "Lite" version, maybe only update usage text every minute? 
            // The prompt didn't ask to change this behavior, so I'll leave it but use the helper.
             
            // We need a synchronous version for the notification since run() is on main thread
            val (mobileUsage, wifiUsage) = getTodayUsageSync()
            details.append("Mobile: ${formatUsage(mobileUsage)} | WiFi: ${formatUsage(wifiUsage)}")
        } else {
             details.append("Tap to grant permission for usage stats")
        }

        var speedTitle = "$speedVal $unitVal"

        if (prefs.getBoolean("show_up_down", false)) {
             speedTitle += "   ↓ ${formatSimple(rxDelta)}   ↑ ${formatSimple(txDelta)}"
        }

        if (prefs.getBoolean("show_wifi_signal", false)) {
            val signal = getWifiSignal()
            speedTitle += "   \uD83D\uDCF6 $signal%"
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            notificationId,
            buildNotification(speedTitle, speedVal, unitVal, details.toString())
        )
    }

    private fun getTodayUsage(): Pair<Long, Long> {
        // This is now just a wrapper for the sync version or could be suspend
        // Since we call it from coroutine in checkDataAlerts, it's fine.
        return getTodayUsageSync()
    }

    private fun getTodayUsageSync(): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        // Respect Reset Timestamp
        val resetTimestamp = prefs.getLong("reset_timestamp", 0L)
        val queryStartTime = if (startTime < resetTimestamp) resetTimestamp else startTime
        
        // If we reset today, and now is before reset, usage is 0?
        // Actually queryStartTime handles it.
        
        if (endTime <= resetTimestamp) return Pair(0L, 0L)

        val mobile = getUsage(ConnectivityManager.TYPE_MOBILE, queryStartTime, endTime)
        val wifi = getUsage(ConnectivityManager.TYPE_WIFI, queryStartTime, endTime)

        return Pair(mobile, wifi)
    }

    private fun getUsage(networkType: Int, startTime: Long, endTime: Long): Long {
        return try {
            val bucket = networkStatsManager.querySummaryForDevice(
                networkType,
                null,
                startTime,
                endTime
            )
            bucket.rxBytes + bucket.txBytes
        } catch (e: Exception) {
            0L
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun getWifiSignal(): Int {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return try {
             // Manual calculation for granular result (0-100)
             val rssi = wm.connectionInfo.rssi
             val minRssi = -100
             val maxRssi = -50
             
             when {
                 rssi <= minRssi -> 0
                 rssi >= maxRssi -> 100
                 else -> ((rssi - minRssi) * 100) / (maxRssi - minRssi)
             }
        } catch (e: Exception) {
            0
        }
    }

    private fun formatSimple(b: Long): String =
        if (b >= 1_024_000)
            String.format(Locale.US, "%.1f MB/s", b / 1_048_576f)
        else
            "${b / 1024} KB/s"

    private fun formatUsage(bytes: Long): String {
        return when {
            bytes >= 1073741824 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824f)
            bytes >= 1048576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576f)
            else -> String.format(Locale.US, "%.1f MB", bytes / 1048576f)
        }
    }

    private fun buildNotification(
        title: String,
        speed: String,
        unit: String,
        details: String
    ): Notification {

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(createSpeedIcon(speed, unit))
            .setContentTitle(title)
            .setContentText(details)
            .setOngoing(true)
            .setSilent(true)
            .setNumber(0)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(getOpenAppIntent())
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) 
            .setWhen(serviceStartTime)
            .setShowWhen(false)
            .build()
    }

    private fun getOpenAppIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createSpeedIcon(speed: String, unit: String): IconCompat {

        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        paint.textSize = size * 0.72f
        
        val textWidth = paint.measureText(speed)
        val maxWidth = size * 0.94f
        
        if (textWidth > maxWidth) {
            paint.textScaleX = maxWidth / textWidth
        }
        
        canvas.drawText(speed, size / 2f, size * 0.58f, paint)

        paint.textScaleX = 1.0f
        paint.textSize = size * 0.38f
        canvas.drawText(unit, size / 2f, size * 0.95f, paint)

        return IconCompat.createWithBitmap(bitmap)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Internet Speed",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET 
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
    
    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                alertChannelId,
                "Data Usage Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when daily data limit is reached"
                enableVibration(true)
                setShowBadge(true)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(runnable)
        serviceScope.cancel()
        super.onDestroy()
    }
}
