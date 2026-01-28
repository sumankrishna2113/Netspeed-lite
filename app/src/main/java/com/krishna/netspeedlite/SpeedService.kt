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
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.toColorInt
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
    // Fix VPN Double-Counting: Track mobile separate from total (which might include VPN tun)
    private var lastMobileRx = 0L
    private var lastMobileTx = 0L
    private var tickCount = 0
    
    // Real-time accumulator for immediate alerts
    private var approxMobileUsage = 0L

    private var lastNetworkStatsUpdate = 0L
    
    // Fix Race Condition: Track day locally to ensure reset even if MainActivity updates Prefs first
    private var lastDayTracker: String = ""

    // Optimization: Cache last notification content to avoid redundant updates
    private var lastNotificationContent: String = ""

    private val channelId = Constants.SPEED_CHANNEL_ID
    private val alertChannelId = Constants.ALERT_CHANNEL_ID
    private val notificationId = Constants.NOTIFICATION_ID

    private val serviceStartTime = System.currentTimeMillis()

    private lateinit var prefs: SharedPreferences

    private val serviceScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    // ===== PROFESSIONAL FIX: DENSITY-AWARE SIZING =====
    // Fixes Pixelation: Generates exact 1:1 pixels for the device.
    // S24 Ultra (3.0x/4.0x) gets high res (72px/96px).
    // Older phones (1.5x) get native res (36px).
    // NO DOWNSCALING = NO PIXELATION.
    private val optimalIconSize: Int by lazy {
        val density = resources?.displayMetrics?.density ?: 2.0f
        // Standard Android Status Bar Height is 24dp
        (24 * density).toInt().coerceAtLeast(36) // Minimum safety
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
            // Update notification UI only if screen is on to save battery
            if (isScreenOn) {
                serviceScope.launch {
                    try {
                        updateNotificationDataSuspend()
                    } catch (e: Exception) {
                        // Ignore errors during notification update
                    }
                }
            }

            // checkDataAlerts runs every 5th tick.
            // If screen is ON (interval 1s), checks every 5s.
            // If screen is OFF (interval 10s, see below), checks every 50s.
            // This might be too slow for high speed downloads.
            // Let's ensure we check frequently enough.
            if (isScreenOn) {
                 // Check alerts every tick (1s) for immediate feedback
                 checkDataAlerts()
                 tickCount++
                 if (tickCount > 1_000_000) tickCount = 0 // Prevent overflow
                 handler.postDelayed(this, Constants.UPDATE_INTERVAL_MS)
            } else {
                // Screen OFF: Check alerts every 10 seconds directly
                checkDataAlerts()
                handler.postDelayed(this, 10000L) 
            }
        }
    }

    // --- Screen State Handling ---
    private var isScreenOn = true
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    // Don't stop updates entirely, just slow them down (handled in runnable)
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    startUpdates() // Kickstart immediately to refresh UI
                }
            }
        }
    }

    // Listener for immediate response to settings changes
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Constants.PREF_SHOW_UP_DOWN,
            Constants.PREF_SHOW_WIFI_SIGNAL,
            Constants.PREF_SHOW_SPEED -> {
                // Immediately refresh notification when display settings change
                serviceScope.launch {
                    try {
                        lastNotificationContent = "" // Clear cache to force update
                        updateNotificationDataSuspend()
                    } catch (e: Exception) {
                        // Ignore errors
                    }
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

        // Initialize Mobile stats
        val initialMobileRx = TrafficStats.getMobileRxBytes()
        val initialMobileTx = TrafficStats.getMobileTxBytes()
        lastMobileRx = if (initialMobileRx == -1L) 0L else initialMobileRx
        lastMobileTx = if (initialMobileTx == -1L) 0L else initialMobileTx

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

        // Register SharedPreferences listener for immediate toggle response
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        // Initialize tracker with current date to avoid immediate reset on start
        lastDayTracker = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        } catch (e: Exception) {
            ""
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
        val isAlertEnabled = prefs.getBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, false)
        if (!isAlertEnabled) {
            return
        }

        serviceScope.launch {
            try {
                val limitMb = prefs.getFloat(Constants.PREF_DAILY_LIMIT_MB, 0f)
                if (limitMb <= 0f) {
                    // Log once every ~5 minutes
                    if (tickCount % 300 == 0) {
                        android.util.Log.w("SpeedService", "checkDataAlerts: Data limit not set (limitMb=$limitMb)")
                    }
                    return@launch
                }

                val todayStr = try {
                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                } catch (e: Exception) {
                    android.util.Log.e("SpeedService", "Error formatting date", e)
                    ""
                }
                val lastChecked = prefs.getString(Constants.PREF_LAST_ALERT_DATE, "")

                if (todayStr.isNotEmpty()) {
                    // Check 1: Local Midnight Reset (Service running across boundary)
                    if (todayStr != lastDayTracker) {

                        approxMobileUsage = 0L
                        lastDayTracker = todayStr
                    }
                    
                    // Check 2: SharedPrefs Reset (Sync with Main App or First Run)
                    if (todayStr != lastChecked) {

                        prefs.edit {
                            putString(Constants.PREF_LAST_ALERT_DATE, todayStr)
                            putBoolean(Constants.PREF_ALERT_80_TRIGGERED, false)
                            putBoolean(Constants.PREF_ALERT_100_TRIGGERED, false)
                        }
                        // Ensure accumulator reset if we hit this path (redundant but safe)
                        approxMobileUsage = 0L
                        lastDayTracker = todayStr
                    }
                }

                val alert80 = prefs.getBoolean(Constants.PREF_ALERT_80_TRIGGERED, false)
                val alert100 = prefs.getBoolean(Constants.PREF_ALERT_100_TRIGGERED, false)

                if (alert80 && alert100) {
                    // Both alerts already triggered today
                    return@launch
                }

                val limitBytes = (limitMb * 1024 * 1024).toLong()
                if (limitBytes <= 0) {
                    android.util.Log.w("SpeedService", "checkDataAlerts: Invalid limitBytes=$limitBytes")
                    return@launch
                }

                // Get mobile data usage - works with or without Usage Stats permission
                val hasPermission = hasUsageStatsPermission()
                val mobileUsage = if (hasPermission) {
                    // Use NetworkStats if permission is granted (more accurate)
                    val (mobile, _) = NetworkUsageHelper.getUsageForDate(applicationContext, System.currentTimeMillis())
                    mobile
                } else {
                    // Use manual tracking if permission is not granted
                    val todayKey = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                    val manualMobile = prefs.getLong(Constants.PREF_MANUAL_MOBILE_PREFIX + todayKey, 0L)
                    
                    // Log fallback mode once every ~5 minutes
                    if (tickCount % 300 == 0) {
                    }
                    
                    manualMobile
                }
                
                // Use the higher of tracked usage or our real-time estimate
                // This ensures we catch alerts even if tracking lags behind
                val effectiveUsage = maxOf(mobileUsage, approxMobileUsage)
                
                // Update approxMobileUsage to track from baseline
                if (mobileUsage > approxMobileUsage) {
                    approxMobileUsage = mobileUsage
                }

                val percentage = if (limitBytes > 0) {
                    (effectiveUsage.toDouble() / limitBytes.toDouble()) * 100
                } else {
                    0.0
                }
                
                // Log usage status periodically (every ~60 seconds when screen on)
                if (tickCount % 60 == 0) {
                    val permStatus = if (hasPermission) "NetworkStats" else "Manual"

                }

                if (percentage >= 100 && !alert100) {

                    // Removed Toast as per request
                    sendAlertNotification(
                        "Daily data limit reached!", 
                        "You've used ${formatUsage(effectiveUsage)} of your ${formatUsage(limitBytes)} daily limit."
                    )
                    prefs.edit { putBoolean(Constants.PREF_ALERT_100_TRIGGERED, true) }
                } else if (percentage >= 80 && !alert80 && !alert100) {

                     // Removed Toast as per request
                    sendAlertNotification(
                        "Data usage warning", 
                        "You've used ${formatUsage(effectiveUsage)} (80%) of your ${formatUsage(limitBytes)} daily limit."
                    )
                    prefs.edit { putBoolean(Constants.PREF_ALERT_80_TRIGGERED, true) }
                }
            } catch (e: Exception) {
                android.util.Log.e("SpeedService", "Error in checkDataAlerts", e)
            }
        }
    }

    private fun sendAlertNotification(title: String, message: String) {
        try {
            // Ensure the alert channel exists
            createAlertChannel()
            
            val notification = NotificationCompat.Builder(this, alertChannelId)
                .setSmallIcon(R.drawable.ic_speed)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setSortKey("B_ALERT") // Ensure it comes AFTER the speed notification
                .setDefaults(Notification.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(getOpenAppIntent())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
                
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (manager == null) {
                android.util.Log.e("SpeedService", "NotificationManager is null, cannot send alert")
                return
            }
            
            // Check if notification channel is enabled (Android 8+)
            // Check if notification channel is enabled (Android 8+)
            val channel = manager.getNotificationChannel(alertChannelId)
            if (channel == null) {
                android.util.Log.e("SpeedService", "Alert notification channel not found, recreating...")
                createAlertChannel()
            } else if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
                android.util.Log.w("SpeedService", "Alert notification channel is disabled by user")
            }
            
            // Use unique ID to force new notification each time
            val uniqueId = (System.currentTimeMillis() % 100000).toInt() + 100
            manager.notify(uniqueId, notification)

        } catch (e: Exception) {
            android.util.Log.e("SpeedService", "Failed to send alert notification", e)
        }
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

        // Real-Time Alert Triggering
        // Fix for VPN: Calculate Mobile-Specific Delta to avoid double-counting (Physical + Tun)
        val mobileRx = TrafficStats.getMobileRxBytes()
        val mobileTx = TrafficStats.getMobileTxBytes()
        
        val mobileRxDelta = if (mobileRx == -1L || lastMobileRx == -1L) {
             0L
        } else if (mobileRx < lastMobileRx) {
             0L 
        } else {
             mobileRx - lastMobileRx
        }
        
        val mobileTxDelta = if (mobileTx == -1L || lastMobileTx == -1L) {
             0L
        } else if (mobileTx < lastMobileTx) {
             0L
        } else {
             mobileTx - lastMobileTx
        }
        
        val totalMobileBytes = mobileRxDelta + mobileTxDelta
        
        // Update baseline
        if (mobileRx != -1L) lastMobileRx = mobileRx
        if (mobileTx != -1L) lastMobileTx = mobileTx

        // Check if we are on mobile data
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val isMobile = cm?.activeNetwork?.let { net ->
             cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } ?: false

        // Use strict mobile-specific bytes for accumulator
        if (isMobile && totalMobileBytes > 0) {
            approxMobileUsage += totalMobileBytes
            
            
            val limitMb = prefs.getFloat(Constants.PREF_DAILY_LIMIT_MB, 0f)
            if (limitMb > 0f) {
                val limitBytes = (limitMb * 1024 * 1024).toLong()
                
                // If accumulated estimated usage crosses 80%, force a check immediately
                val estPercentage = (approxMobileUsage.toDouble() / limitBytes.toDouble()) * 100
                val alert80 = prefs.getBoolean(Constants.PREF_ALERT_80_TRIGGERED, false)
                val alert100 = prefs.getBoolean(Constants.PREF_ALERT_100_TRIGGERED, false)
                
                if ((estPercentage >= 80 && !alert80) || (estPercentage >= 100 && !alert100)) {
                    // Force Check Now
                    checkDataAlerts()
                }
            }
        }

        val details = StringBuilder()
        // NetworkUsageHelper call moved to IO dispatcher
        if (hasUsageStatsPermission()) {
            val (mobile, wifi) = NetworkUsageHelper.getUsageForDate(applicationContext, System.currentTimeMillis())
            details.append("Mobile: ${formatUsage(mobile)}")
            
            // Show percentage if limit is set (Helpful for debugging alerts)
            // Show percentage if limit is set
            val limitMb = prefs.getFloat(Constants.PREF_DAILY_LIMIT_MB, 0f)
            if (limitMb > 0f) {
                 // Percentage display removed as per request
            }
            
            details.append(" | WiFi: ${formatUsage(wifi)}")
        } else {
             // Fallback: Use manually tracked data
             val todayKey = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
             val mobile = prefs.getLong(Constants.PREF_MANUAL_MOBILE_PREFIX + todayKey, 0L)
             val wifi = prefs.getLong(Constants.PREF_MANUAL_WIFI_PREFIX + todayKey, 0L)
             details.append("Mobile: ${formatUsage(mobile)} | WiFi: ${formatUsage(wifi)}")
             
             // Track current delta
             if (totalBytes > 0) {
                  trackManualUsage(rxDelta + txDelta, todayKey)
             }
        }

        val showSpeed = prefs.getBoolean(Constants.PREF_SHOW_SPEED, true)
        
        // Optimization: Generate a content key to check if update is needed
        val (speedVal, unitVal) = formatSpeed(totalBytes)
        val contentKey = if (showSpeed) {
            val simpleRx = formatSimple(rxDelta)
            val simpleTx = formatSimple(txDelta)
            val wifiSignal = if (prefs.getBoolean(Constants.PREF_SHOW_WIFI_SIGNAL, false)) getWifiSignal().toString() else ""
            "$speedVal|$unitVal|$simpleRx|$simpleTx|$wifiSignal|$details"
        } else {
            "HIDDEN|$details"
        }

        // Skip update if content hasn't changed (Battery Optimization)
        if (contentKey == lastNotificationContent) {
            return@withContext
        }
        lastNotificationContent = contentKey

        val notification = if (showSpeed) {
            var speedTitle = "$speedVal $unitVal"
            if (prefs.getBoolean(Constants.PREF_SHOW_UP_DOWN, false)) {
                speedTitle += "   ↓ ${formatSimple(rxDelta)}   ↑ ${formatSimple(txDelta)}"
            }
            if (prefs.getBoolean(Constants.PREF_SHOW_WIFI_SIGNAL, false)) {
                speedTitle += "   WiFi: ${getWifiSignal()}%"
            }
            buildNotification(speedTitle, speedVal, unitVal, details.toString())
        } else {
            // When speed is hidden, use the MONITOR channel with MIN importance (collapsed)
            // This satisfies the foreground service requirement while minimizing visibility
            NotificationCompat.Builder(this@SpeedService, Constants.MONITOR_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_speed)
                .setContentTitle("Data monitoring enabled")
                .setContentText(details.toString())
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_MIN) // Minimized
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(getOpenAppIntent())
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET) // Hide from lockscreen if possible
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        // Post notification update back to the Main thread (or directly if NotificationManager is thread-safe, which it is)
        // Post notification update back to the Main thread (or directly if NotificationManager is thread-safe, which it is)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager != null) {
            try {
                manager.notify(notificationId, notification)
            } catch (e: Exception) {
                // Catch sporadic SecurityException or "TransactionTooLargeException" on some devices
                android.util.Log.e("SpeedService", "Error updating notification", e)
            }
        }
    }

    // --- Helpers ---

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun getWifiSignal(): Int {
        try {
            val minRssi = -100
            val maxRssi = -55
            var rssi = -127

            // Method 1: ConnectivityManager (Android 10+ / API 29+)
            // Preferable as it doesn't strictly require Location Permission for RSSI on some versions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) {
                    val activeNetwork = cm.activeNetwork
                    val caps = cm.getNetworkCapabilities(activeNetwork)
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        val transportInfo = caps.transportInfo
                        if (transportInfo is android.net.wifi.WifiInfo) {
                            rssi = transportInfo.rssi
                        }
                    }
                }
            }

            // Method 2: WifiManager Fallback (Older Android or if Method 1 fails)
            if (rssi == -127) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wm != null) {
                    @Suppress("DEPRECATION")
                    val connectionInfo = wm.connectionInfo
                    if (connectionInfo != null) {
                        rssi = connectionInfo.rssi
                    }
                }
            }

            // If still invalid, return 0
            if (rssi == -127) return 0

            return when {
                rssi <= minRssi -> 0
                rssi >= maxRssi -> 100
                else -> ((rssi - minRssi) * 100) / (maxRssi - minRssi)
            }
        } catch (e: Exception) {
            return 0
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
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSortKey("A_SPEED") // Force Sort to Top behavior
            .setContentIntent(getOpenAppIntent())
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(serviceStartTime)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun getOpenAppIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun trackManualUsage(bytes: Long, todayKey: String) {
        try {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return
            
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isMobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            
            if (isMobile) {
                synchronized(prefs) {
                    val current = prefs.getLong(Constants.PREF_MANUAL_MOBILE_PREFIX + todayKey, 0L)
                    prefs.edit { putLong(Constants.PREF_MANUAL_MOBILE_PREFIX + todayKey, current + bytes) }
                }
            } else if (isWifi) {
                synchronized(prefs) {
                    val current = prefs.getLong(Constants.PREF_MANUAL_WIFI_PREFIX + todayKey, 0L)
                    prefs.edit { putLong(Constants.PREF_MANUAL_WIFI_PREFIX + todayKey, current + bytes) }
                }
            }
        } catch (e: Exception) {
            // Ignore errors in tracking
        }
    }

    private fun createSpeedIcon(speed: String, unit: String): IconCompat {
        // Check cache
        val cacheKey = "$speed|$unit"
        synchronized(iconCache) {
            iconCache[cacheKey]?.let { return it }
        }

        val size = optimalIconSize

        try {
            // Stacked Layout: Speed (Top) / Unit (Bottom)
            // Using "sans-serif-medium" for system status bar consistency

            
            val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                // Perfect Balance: "sans-serif-medium" matches the Status Bar Clock
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                isFilterBitmap = true
                isDither = true
                
                // Polish: Subtle Shadow (instead of lightning) for contrast & anti-aliasing help
                // Polish: Subtle Shadow (instead of lightning) for contrast & anti-aliasing help
                setShadowLayer(2f, 0f, 1f, "#40000000".toColorInt())
            }

            // Uniform Reference Scaling: Scale based on "888" to ensure consistent height/thickness

            
            // 1. Calculate Standardized Font Size using Reference "888"
            paint.textSize = size * 1.0f 
            val refRect = android.graphics.Rect()
            paint.getTextBounds("888", 0, 3, refRect)
            
            // Fixed Target: 56% of Canvas
            val targetVisualHeight = size * 0.56f
            val heightScale = targetVisualHeight / refRect.height().toFloat()
            val masterTextSize = paint.textSize * heightScale
            
            // Apply Master Size
            paint.textSize = masterTextSize
            
            // 2. Safety Check (Horizontal)
            // Only shrink if THIS specific number is too wide (e.g. "999")
            val textWidth = paint.measureText(speed)
            val maxSpeedWidth = size * 0.96f
            if (textWidth > maxSpeedWidth) {
                paint.textScaleX = maxSpeedWidth / textWidth
            }
            
            // 3. Draw Speed
            // Vertical Alignment: Align based on reference top to keep baseline stable

            val reMeasuredRefRect = android.graphics.Rect()
            paint.getTextBounds("888", 0, 3, reMeasuredRefRect)
            
            // Align Top of "888" to top of canvas
            val speedY = -reMeasuredRefRect.top.toFloat() + (size * 0.00f) // Keep tight top
            canvas.drawText(speed, size / 2f, speedY, paint)

            // 4. Draw Unit (With Safety Scaling)
            paint.textScaleX = 1.0f 
            paint.textSize = size * 0.45f // Slightly reduced from 0.48f to prevent vertical overflow
            
            val unitRect = android.graphics.Rect()
            paint.getTextBounds(unit, 0, unit.length, unitRect)
            
            // Safety Check: Scale down if unit text is too wide
            val unitRefWidth = paint.measureText(unit)
            val maxUnitWidth = size * 0.96f
            if (unitRefWidth > maxUnitWidth) {
                 paint.textScaleX = maxUnitWidth / unitRefWidth
            }
            
            // Restore tiny padding to prevent anti-aliasing clipping at the bottom
            val unitY = size.toFloat() - unitRect.bottom - (size * 0.02f) 
            canvas.drawText(unit, size / 2f, unitY, paint)

            val iconCompat = IconCompat.createWithBitmap(bitmap)
            synchronized(iconCache) {
                iconCache[cacheKey] = iconCompat
            }
            
            return iconCompat
        } catch (e: Exception) {
            android.util.Log.e("SpeedService", "Error creating speed icon", e)
            // Fallback to static icon
            return IconCompat.createWithResource(this, R.drawable.ic_speed)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        
        // Channel 1: Internet Speed (Low Importance - shows icon)
        val speedChannel = NotificationChannel(channelId, "Internet Speed", NotificationManager.IMPORTANCE_LOW)
        speedChannel.setSound(null, null)
        speedChannel.enableVibration(false)
        speedChannel.setShowBadge(false)
        speedChannel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        manager?.createNotificationChannel(speedChannel)
        
        // Channel 2: Data Monitor (Min Importance - collapsed, no icon)
        val monitorChannel = NotificationChannel(Constants.MONITOR_CHANNEL_ID, "Data Monitor", NotificationManager.IMPORTANCE_MIN)
        monitorChannel.setSound(null, null)
        monitorChannel.enableVibration(false)
        monitorChannel.setShowBadge(false)
        monitorChannel.lockscreenVisibility = Notification.VISIBILITY_SECRET
        manager?.createNotificationChannel(monitorChannel)
    }

    private fun createAlertChannel() {
        val channel = NotificationChannel(alertChannelId, "Data Usage Alerts", NotificationManager.IMPORTANCE_HIGH)
        channel.enableVibration(true)
        channel.setVibrationPattern(longArrayOf(0, 500, 200, 500))
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
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

        // Unregister SharedPreferences listener
        if (::prefs.isInitialized) {
            try {
                prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Restart service if it was killed system-side but user wants it on
        if (::prefs.isInitialized) {
            try {
                val showSpeed = prefs.getBoolean(Constants.PREF_SHOW_SPEED, true)
                val isAlertEnabled = prefs.getBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, false)
                // Safety Check: Only restart if the service has lived for at least 2 seconds.
                // This prevents an infinite crash loop if the service crashes immediately upon startup.
                val livedLongEnough = (System.currentTimeMillis() - serviceStartTime) > 2000

                // Restart if either speed display OR alerts are enabled
                if ((showSpeed || isAlertEnabled) && livedLongEnough) {
                    // Send broadcast to restart service
                    val restartIntent = Intent(applicationContext, BootReceiver::class.java)
                    restartIntent.action = "com.krishna.netspeedlite.RESTART_SERVICE"
                    sendBroadcast(restartIntent)
                }
            } catch (e: Exception) {
                android.util.Log.e("SpeedService", "Error in restart logic", e)
            }
        }

        super.onDestroy()
    }
}