package com.krishna.netspeedlite

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
import java.util.Date
import java.util.Locale

class SpeedService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastRx = 0L
    private var lastTx = 0L
    private val interval = 1000L
    private var tickCount = 0

    // Updated channel ID to force re-creation with no-badge settings
    private val channelId = "speed_channel_v7"
    private val alertChannelId = "data_alert_channel"
    private val notificationId = 1
    
    // Fixed timestamp to prevent notification sorting jumps
    private val serviceStartTime = System.currentTimeMillis()

    private lateinit var prefs: SharedPreferences

    // Coroutine Scope
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    private val runnable = object : Runnable {
        override fun run() {
            updateNotificationData()
            if (tickCount % 5 == 0) checkDataAlerts()
            tickCount++
            handler.postDelayed(this, interval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        createNotificationChannel()
        createAlertChannel()

        lastRx = TrafficStats.getTotalRxBytes()
        lastTx = TrafficStats.getTotalTxBytes()

        val notification = buildNotification("Initializing...", "0", "KB/s", "Starting...")
        startForeground(notificationId, notification)
        handler.post(runnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun checkDataAlerts() {
        if (!prefs.getBoolean("daily_limit_enabled", false)) return

        serviceScope.launch {
            if (!hasUsageStatsPermission()) return@launch

            val limitMb = prefs.getFloat("daily_limit_mb", 0f)
            if (limitMb <= 0f) return@launch

            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val lastChecked = prefs.getString("last_alert_date", "")

            if (todayStr != lastChecked) {
                prefs.edit().putString("last_alert_date", todayStr)
                    .putBoolean("alert_80_triggered", false)
                    .putBoolean("alert_100_triggered", false).apply()
            }

            val alert80 = prefs.getBoolean("alert_80_triggered", false)
            val alert100 = prefs.getBoolean("alert_100_triggered", false)

            if (alert80 && alert100) return@launch

            val limitBytes = (limitMb * 1024 * 1024).toLong()

            val (mobileUsage, _) = NetworkUsageHelper.getUsageForDate(applicationContext, System.currentTimeMillis())

            val percentage = (mobileUsage.toDouble() / limitBytes.toDouble()) * 100

            if (percentage >= 100 && !alert100) {
                sendAlertNotification("Daily limit reached", "You have reached your daily data limit.")
                prefs.edit().putBoolean("alert_100_triggered", true).apply()
            } else if (percentage >= 80 && !alert80 && !alert100) {
                sendAlertNotification("Data Warning", "You've used 80% of your daily data limit.")
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

        val (speedVal, unitVal) = formatSpeed(totalBytes)
        val details = StringBuilder()

        if (hasUsageStatsPermission()) {
            val (mobile, wifi) = NetworkUsageHelper.getUsageForDate(this, System.currentTimeMillis())
            details.append("Mobile: ${formatUsage(mobile)} | WiFi: ${formatUsage(wifi)}")
        } else {
            details.append("Tap to grant permission")
        }

        var speedTitle = "$speedVal $unitVal"
        if (prefs.getBoolean("show_up_down", false)) {
            speedTitle += "   ↓ ${formatSimple(rxDelta)}   ↑ ${formatSimple(txDelta)}"
        }
        if (prefs.getBoolean("show_wifi_signal", false)) {
            speedTitle += "   \uD83D\uDCF6 ${getWifiSignal()}%"
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, buildNotification(speedTitle, speedVal, unitVal, details.toString()))
    }

    // --- Helpers ---

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
            val rssi = wm.connectionInfo.rssi
            val level = WifiManager.calculateSignalLevel(rssi, 100)
            level
        } catch (e: Exception) { 0 }
    }

    private fun formatSpeed(bytes: Long): Pair<String, String> {
        return if (bytes >= 1_024_000) {
            val mb = bytes / 1_048_576f
            Pair(String.format(Locale.US, if (mb >= 10) "%.0f" else "%.1f", mb), "MB/s")
        } else {
            Pair((bytes / 1024).toString(), "KB/s")
        }
    }

    private fun formatSimple(b: Long): String =
        if (b >= 1_024_000) String.format(Locale.US, "%.1f MB/s", b / 1_048_576f) else "${b / 1024} KB/s"

    private fun formatUsage(bytes: Long): String {
        return when {
            bytes >= 1073741824 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824f)
            bytes >= 1048576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576f)
            else -> String.format(Locale.US, "%.1f MB", bytes / 1048576f)
        }
    }

    private fun buildNotification(title: String, speed: String, unit: String, details: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(createSpeedIcon(speed, unit))
            .setContentTitle(title)
            .setContentText(details)
            .setOngoing(true)
            .setAutoCancel(false) // Fix: Prevent implicit cancellation
            .setNumber(0) // Fix: Explicitly set count to 0
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE) // Fix: Do not show badge icon
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(getOpenAppIntent())
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setWhen(serviceStartTime)
            .setShowWhen(false)
            .build()
    }

    private fun getOpenAppIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
        if (textWidth > size * 0.94f) paint.textScaleX = (size * 0.94f) / textWidth
        canvas.drawText(speed, size / 2f, size * 0.58f, paint)
        paint.textScaleX = 1.0f
        paint.textSize = size * 0.38f
        canvas.drawText(unit, size / 2f, size * 0.95f, paint)
        return IconCompat.createWithBitmap(bitmap)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Internet Speed", NotificationManager.IMPORTANCE_LOW)
            channel.setSound(null, null)
            channel.enableVibration(false)
            channel.setShowBadge(false) // Fix: Explicitly disable notification dot/badge
            channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(alertChannelId, "Data Usage Alerts", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(runnable)
        serviceScope.cancel()
        super.onDestroy()
    }
}