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
import android.content.pm.ServiceInfo
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
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SpeedService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastRx = 0L
    private var lastTx = 0L
    private var tickCount = 0

    private val channelId = Constants.SPEED_CHANNEL_ID
    private val alertChannelId = Constants.ALERT_CHANNEL_ID
    private val notificationId = Constants.NOTIFICATION_ID

    private val serviceStartTime = System.currentTimeMillis()

    private lateinit var prefs: SharedPreferences

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    // ===== DENSITY-INDEPENDENT ICON SIZE: Uses dp instead of fixed pixels =====
    private val optimalIconSize: Int by lazy {
        try {
            val density = resources?.displayMetrics?.density ?: 2.0f
            val baseDp = 64 // Use 64dp as base for large notification icons (Android guideline)

            // Convert dp to pixels using device density
            // This ensures proper scaling across all screen densities:
            // MDPI (1.0x): 64px, HDPI (1.5x): 96px, XHDPI (2.0x): 128px
            // XXHDPI (3.0x): 192px, XXXHDPI (4.0x): 256px
            val baseSize = (baseDp * density).toInt()

            // Apply manufacturer-specific adjustments as multipliers (not fixed values)
            // This maintains density independence while accounting for rendering differences
            val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
            val adjustmentFactor = when {
                manufacturer.contains("samsung") -> 1.1f  // Samsung devices benefit from slightly larger icons

                // OnePlus and Vivo devices need significantly larger icons to display properly
                manufacturer.contains("oneplus") -> 1.5f  // Increased from 1.2f for better visibility
                manufacturer.contains("vivo") -> 1.5f     // Increased from 1.2f for better visibility
                manufacturer.contains("oppo") -> 1.4f    // Slightly less than OnePlus/Vivo
                manufacturer.contains("realme") -> 1.4f  // Slightly less than OnePlus/Vivo

                manufacturer.contains("xiaomi") ||
                        manufacturer.contains("redmi") ||
                        manufacturer.contains("poco") -> 1.15f

                else -> 1.0f
            }

            // Calculate adjusted size
            val adjustedSize = (baseSize * adjustmentFactor).toInt()

            // For OnePlus and Vivo, ensure minimum size is larger to prevent small icons
            // Ensure reasonable bounds: minimum 64px (1x MDPI), maximum 600px (increased for OnePlus/Vivo)
            val finalSize = if (manufacturer.contains("oneplus") || manufacturer.contains("vivo")) {
                adjustedSize.coerceIn(96, 600)  // Higher minimum and maximum for OnePlus/Vivo
            } else {
                adjustedSize.coerceIn(64, 512)
            }

            android.util.Log.d("SpeedService", "Icon size calculation: manufacturer=$manufacturer, density=$density, baseSize=$baseSize, adjustmentFactor=$adjustmentFactor, finalSize=$finalSize")

            finalSize
        } catch (e: Exception) {
            android.util.Log.e("SpeedService", "Error calculating optimal icon size", e)
            128 // Fallback to safe default (XHDPI)
        }
    }

    // Icon cache for performance - using LinkedHashMap for LRU behavior
    // Fix: Limit cache size to prevent unbounded growth
    private val iconCache = object : LinkedHashMap<String, IconCompat>(Constants.MAX_ICON_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IconCompat>?): Boolean {
            return size > Constants.MAX_ICON_CACHE_SIZE
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val runnable = object : Runnable {
        override fun run() {
            serviceScope.launch {
                try {
                    updateNotificationDataSuspend()
                } catch (e: Exception) {
                    // Log potential errors from data fetching or notification update
                    e.printStackTrace()
                }
            }
            if (tickCount % 5 == 0) checkDataAlerts()
            tickCount++
            handler.postDelayed(this, Constants.UPDATE_INTERVAL_MS)
        }
    }

    // --- Screen State Handling ---
    private var isScreenOn = true
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    stopUpdates()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    startUpdates()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)

        createNotificationChannel()
        createAlertChannel()

        // Initialize with current stats, handling -1 (unavailable)
        val initialRx = TrafficStats.getTotalRxBytes()
        val initialTx = TrafficStats.getTotalTxBytes()
        lastRx = if (initialRx == -1L) 0L else initialRx
        lastTx = if (initialTx == -1L) 0L else initialTx

        val notification = buildNotification("Initializing...", "0", "KB/s", "Starting...")

        try {
            // Pass foregroundServiceType to support Android 14+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Use UPSIDE_DOWN_CAKE for API 34
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is android.app.ForegroundServiceStartNotAllowedException) {
                android.util.Log.e("SpeedService", "Foreground service start not allowed", e)
                stopSelf()
            } else {
                // Log other exceptions but don't crash if possible, or rethrow
                android.util.Log.e("SpeedService", "Error starting foreground service", e)
                stopSelf() // Safer to stop service than crash
            }
            return // Prevent further initialization if functionality failed
        }

        // Register Screen On/Off receiver
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(screenStateReceiver, filter)
        } catch (e: Exception) {
            android.util.Log.e("SpeedService", "Error registering screen receiver", e)
        }

        startUpdates()
    }

    private fun startUpdates() {
        handler.removeCallbacks(runnable) // Prevent duplicates
        handler.post(runnable)
    }

    private fun stopUpdates() {
        handler.removeCallbacks(runnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun checkDataAlerts() {
        if (!prefs.getBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, false)) return

        serviceScope.launch {
            if (!hasUsageStatsPermission()) return@launch

            val limitMb = prefs.getFloat(Constants.PREF_DAILY_LIMIT_MB, 0f)
            if (limitMb <= 0f) return@launch

            val todayStr = try {
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            } catch (e: Exception) {
                android.util.Log.e("SpeedService", "Error formatting date", e)
                ""
            }
            val lastChecked = prefs.getString(Constants.PREF_LAST_ALERT_DATE, "")

            if (todayStr.isNotEmpty() && todayStr != lastChecked) {
                prefs.edit().putString(Constants.PREF_LAST_ALERT_DATE, todayStr)
                    .putBoolean(Constants.PREF_ALERT_80_TRIGGERED, false)
                    .putBoolean(Constants.PREF_ALERT_100_TRIGGERED, false).apply()
            }

            val alert80 = prefs.getBoolean(Constants.PREF_ALERT_80_TRIGGERED, false)
            val alert100 = prefs.getBoolean(Constants.PREF_ALERT_100_TRIGGERED, false)

            if (alert80 && alert100) return@launch

            val limitBytes = (limitMb * 1024 * 1024).toLong()
            if (limitBytes <= 0) return@launch

            val (mobileUsage, _) = NetworkUsageHelper.getUsageForDate(applicationContext, System.currentTimeMillis())

            val percentage = if (limitBytes > 0) {
                (mobileUsage.toDouble() / limitBytes.toDouble()) * 100
            } else {
                0.0
            }

            if (percentage >= 100 && !alert100) {
                sendAlertNotification("Daily limit reached", "You have reached your daily data limit.")
                prefs.edit().putBoolean(Constants.PREF_ALERT_100_TRIGGERED, true).apply()
            } else if (percentage >= 80 && !alert80 && !alert100) {
                sendAlertNotification("Data Warning", "You've used 80% of your daily data limit.")
                prefs.edit().putBoolean(Constants.PREF_ALERT_80_TRIGGERED, true).apply()
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        // Use a fixed notification ID for alerts to avoid collisions
        manager?.notify(Constants.NOTIFICATION_ID + 2, notification)
    }

    // Refactored to be a suspend function to run network operations on a background thread
    private suspend fun updateNotificationDataSuspend() = withContext(Dispatchers.IO) {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()

        // Detect TrafficStats reset (e.g. reboot, airplane mode toggle, or overflow)
        // If current stats are LESS than previous, it means the counter reset.
        // In that case, we can't calculate a delta for *this* second, but we MUST
        // update lastRx/lastTx to the new lower value so the *next* second is correct.
        val rxDelta = if (rx == -1L || lastRx == -1L) {
            0L
        } else if (rx < lastRx) {
            // Counter reset: Don't show confusing large negative/positive spike.
            // Just treat this tick as 0 and re-sync baseline.
            0L
        } else {
            rx - lastRx
        }

        val txDelta = if (tx == -1L || lastTx == -1L) {
            0L
        } else if (tx < lastTx) {
            0L
        } else {
            tx - lastTx
        }

        val totalBytes = rxDelta + txDelta

        // Always update baseline for next tick (unless unavailable)
        // This is crucial: if rx < lastRx (reset), we MUST update lastRx to the new smaller rx
        if (rx != -1L) lastRx = rx
        if (tx != -1L) lastTx = tx

        val (speedVal, unitVal) = formatSpeed(totalBytes)
        val details = StringBuilder()

        if (hasUsageStatsPermission()) {
            // NetworkUsageHelper call moved to IO dispatcher
            val (mobile, wifi) = NetworkUsageHelper.getUsageForDate(applicationContext, System.currentTimeMillis())
            details.append("Mobile: ${formatUsage(mobile)} | WiFi: ${formatUsage(wifi)}")
        } else {
            details.append("Tap to grant permission")
        }

        var speedTitle = "$speedVal $unitVal"
        if (prefs.getBoolean(Constants.PREF_SHOW_UP_DOWN, false)) {
            speedTitle += "   ↓ ${formatSimple(rxDelta)}   ↑ ${formatSimple(txDelta)}"
        }
        if (prefs.getBoolean(Constants.PREF_SHOW_WIFI_SIGNAL, false)) {
            speedTitle += "   \uD83D\uDCF6 ${getWifiSignal()}%"
        }

        val notification = buildNotification(speedTitle, speedVal, unitVal, details.toString())

        // Post notification update back to the Main thread (or directly if NotificationManager is thread-safe, which it is)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            manager?.notify(notificationId, notification)
        } catch (e: Exception) {
            // Catch sporadic SecurityException or "TransactionTooLargeException" on some devices
            android.util.Log.e("SpeedService", "Error updating notification", e)
        }
    }

    // --- Helpers ---

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun getWifiSignal(): Int {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return 0
            val connectionInfo = wm.connectionInfo
            val rssi = connectionInfo?.rssi ?: return 0
            WifiManager.calculateSignalLevel(rssi, 100)
        } catch (e: SecurityException) {
            // Android 10+ requires location permission for WiFi info
            0
        } catch (e: Exception) {
            0
        }
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
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
            else -> "$bytes B"
        }
    }

    private fun buildNotification(title: String, speed: String, unit: String, details: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(createSpeedIcon(speed, unit))
            .setContentTitle(title)
            .setContentText(details)
            .setOngoing(true)
            .setAutoCancel(false)
            .setNumber(0)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
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
            ?: Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun createSpeedIcon(speed: String, unit: String): IconCompat {
        // Check cache
        val cacheKey = "$speed|$unit"
        iconCache[cacheKey]?.let { return it }

        // Fix: LRU cache automatically removes eldest entries when size exceeds limit
        // No need to manually clear the entire cache

        val size = optimalIconSize

        // Check if device is OnePlus or Vivo for additional adjustments
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val isOnePlusOrVivo = manufacturer.contains("oneplus") || manufacturer.contains("vivo")

        try {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // ===== KEY FIX: Use higher quality settings =====
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                isFilterBitmap = true  // Important for scaling
                isDither = true        // Better quality
                // For OnePlus/Vivo, use LCD rendering hint for better text clarity
                if (isOnePlusOrVivo) {
                    isLinearText = false  // Use subpixel rendering when available
                }
            }

            // Adjust text size proportions for OnePlus/Vivo to ensure visibility
            val speedTextSizeMultiplier = if (isOnePlusOrVivo) 0.75f else 0.72f
            val unitTextSizeMultiplier = if (isOnePlusOrVivo) 0.40f else 0.38f

            // Keep your original proportions - adjusted for OnePlus/Vivo
            paint.textSize = size * speedTextSizeMultiplier
            val textWidth = paint.measureText(speed)
            if (textWidth > size * 0.94f) {
                paint.textScaleX = (size * 0.94f) / textWidth
            }
            canvas.drawText(speed, size / 2f, size * 0.58f, paint)

            paint.textScaleX = 1.0f
            paint.textSize = size * unitTextSizeMultiplier
            canvas.drawText(unit, size / 2f, size * 0.95f, paint)

            val iconCompat = IconCompat.createWithBitmap(bitmap)
            iconCache[cacheKey] = iconCompat

            return iconCompat
        } catch (e: Exception) {
            android.util.Log.e("SpeedService", "Error creating speed icon", e)
            // Fallback to static icon
            return IconCompat.createWithResource(this, R.drawable.ic_speed)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Internet Speed", NotificationManager.IMPORTANCE_LOW)
            channel.setSound(null, null)
            channel.enableVibration(false)
            channel.setShowBadge(false)
            channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(alertChannelId, "Data Usage Alerts", NotificationManager.IMPORTANCE_HIGH)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(runnable)
        serviceScope.cancel()

        // Unregister receiver to prevent leaks
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }

        // Restart service if it was killed system-side but user wants it on
        try {
            val showSpeed = prefs.getBoolean(Constants.PREF_SHOW_SPEED, true)
            // Safety Check: Only restart if the service has lived for at least 2 seconds.
            // This prevents an infinite crash loop if the service crashes immediately upon startup.
            val livedLongEnough = (System.currentTimeMillis() - serviceStartTime) > 2000

            if (showSpeed && livedLongEnough) {
                // Send broadcast to restart service
                val restartIntent = Intent(applicationContext, BootReceiver::class.java)
                restartIntent.action = "com.krishna.netspeedlite.RESTART_SERVICE"
                sendBroadcast(restartIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("SpeedService", "Error in restart logic", e)
        }

        super.onDestroy()
    }
}