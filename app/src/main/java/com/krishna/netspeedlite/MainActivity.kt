package com.krishna.netspeedlite

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.krishna.netspeedlite.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var networkStatsManager: NetworkStatsManager? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var usageAdapter: UsageAdapter

    // Auto-refresh timer for "Live" updates in the app
    private val refreshHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isRefreshing = false
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!isRefreshing) {
                refreshData()
            }
            // Schedule next refresh in 5 seconds
            refreshHandler.postDelayed(this, 5000)
        }
    }

    private val checkAlertsHandler = Handler(Looper.getMainLooper())
    private val checkAlertsRunnable = object : Runnable {
        override fun run() {
            checkDataAlerts()
            checkAlertsHandler.postDelayed(this, Constants.ALERT_CHECK_INTERVAL_MS)
        }
    }

    // Store battery optimization runnable to prevent memory leak
    private var batteryOptRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        applyThemeFromPrefs()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        networkStatsManager =
            getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager

        setupPermissions()
        setupUI()

        createNotificationChannel()

        refreshData()

        val showSpeed = prefs.getBoolean(Constants.PREF_SHOW_SPEED, true)
        val isAlertEnabled = prefs.getBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, false)
        if (showSpeed || isAlertEnabled) {
            startSpeedService()
        }

        // Reset dismissed flag on app start so prompt can show again in new session
        // Reset dismissed flag on app start so prompt can show again in new session
        prefs.edit { putBoolean(Constants.PREF_BATTERY_OPT_DISMISSED, false) }

        recordAppOpen()

        // Handle back presses using the modern API
        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.END)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.END)
                } else {
                    // Default back behavior if drawer is not open
                    isEnabled = false // Disable this callback to allow default behavior
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }


    private fun applyThemeFromPrefs() {
        try {
            val themeMode =
                prefs.getInt(Constants.PREF_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            if (AppCompatDelegate.getDefaultNightMode() != themeMode) {
                AppCompatDelegate.setDefaultNightMode(themeMode)
            }
        } catch (e: Exception) {
            // Ignore theme application errors

        }
    }

    private fun startSpeedService() {
        val intent = Intent(this, SpeedService::class.java)
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start foreground service", e)
        }
        
        // Schedule the watchdog worker
        scheduleSpeedServiceWorker()
    }

    private fun scheduleSpeedServiceWorker() {
        try {
            val workRequest = PeriodicWorkRequestBuilder<SpeedServiceWorker>(15, TimeUnit.MINUTES)
                .build()
            
            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                "SpeedServiceWatchdog",
                ExistingPeriodicWorkPolicy.KEEP, // Keep existing if already scheduled
                workRequest
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to schedule worker", e)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()

        // Start auto-refresh
        refreshHandler.post(refreshRunnable)

        if (hasUsageStatsPermission()) {
            checkDataAlerts()
            checkAlertsHandler.removeCallbacks(checkAlertsRunnable)
            checkAlertsHandler.post(checkAlertsRunnable)
        }


    }


    override fun onPause() {
        super.onPause()
        checkAlertsHandler.removeCallbacks(checkAlertsRunnable)

        // Stop auto-refresh to save battery
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Fix memory leak: Cancel battery optimization runnable
        batteryOptRunnable?.let { binding.root.removeCallbacks(it) }
        batteryOptRunnable = null

        // Fix memory leak: Clean up all handler callbacks
        checkAlertsHandler.removeCallbacksAndMessages(null)
        refreshHandler.removeCallbacksAndMessages(null)
    }

    // Replaced with OnBackPressedCallback
    // override fun onBackPressed() { ... }

    private val requestPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permission granted

            } else {
                // Permission denied
                Log.w("MainActivity", "Notification permission denied")
                // Check if we should show a rationale (user denied but didn't check "Don't ask again")
                // Note: In modern Android, if the user denies twice, shouldShowRequestPermissionRationale returns false (same as "Don't ask again")
                // So if it's false here, it implies permanent denial OR it's the first time (but we just asked, so it's permanent)
                if (!shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                    showSettingsDialog()
                }
            }
        }

    private fun setupPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission is already granted
                }
                shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Show rationale
                    showPermissionRationale()
                }
                else -> {
                    // Request permission directly
                    requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun showPermissionRationale() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_title)
            .setMessage(R.string.permission_rationale)
            .setPositiveButton(R.string.grant) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            }
            .setNegativeButton(R.string.deny, null)
            .show()
    }

    private fun showSettingsDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_required)
            .setMessage(R.string.permission_settings_message)
            .setPositiveButton(R.string.go_to_settings) { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error opening settings", e)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }


    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun setupUI() {
        usageAdapter = UsageAdapter()
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = usageAdapter
            isNestedScrollingEnabled = false
        }
    }


    private fun refreshData() {
        if (isRefreshing) return
        isRefreshing = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val usageList = ArrayList<DailyUsage>()
                val calendar = Calendar.getInstance()

                var totalMobile = 0L
                var totalWifi = 0L
                var last7DaysMobile = 0L
                var last7DaysWifi = 0L

                for (i in 0 until 30) {
                    try {
                        calendar.set(Calendar.HOUR_OF_DAY, 0)
                        calendar.set(Calendar.MINUTE, 0)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        val startTime = calendar.timeInMillis

                        calendar.set(Calendar.HOUR_OF_DAY, 23)
                        calendar.set(Calendar.MINUTE, 59)
                        calendar.set(Calendar.SECOND, 59)
                        calendar.set(Calendar.MILLISECOND, 999)
                        val endTime = calendar.timeInMillis

                        val (m, w) = if (hasUsageStatsPermission()) {
                            val (mob, wifi) = NetworkUsageHelper.getUsageInRange(applicationContext, startTime, endTime)
                            
                            // SYNC: Persist system data to manual cache so it remains available if permission is revoked
                            try {
                                val dateKey = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(startTime))
                                prefs.edit { 
                                    putLong(Constants.PREF_MANUAL_MOBILE_PREFIX + dateKey, mob)
                                    putLong(Constants.PREF_MANUAL_WIFI_PREFIX + dateKey, wifi)
                                }
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Error syncing data to cache", e)
                            }
                            
                            Pair(mob, wifi)
                        } else {
                            val manualData = fetchManualData(calendar)
                            Pair(manualData.mobileBytes, manualData.wifiBytes)
                        }
                        val mobile = m
                        val wifi = w

                        usageList.add(DailyUsage(startTime, mobile, wifi, mobile + wifi))

                        totalMobile += mobile
                        totalWifi += wifi
                        if (i < 7) {
                            last7DaysMobile += mobile
                            last7DaysWifi += wifi
                        }

                        calendar.add(Calendar.DAY_OF_YEAR, -1)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error processing day $i", e)
                        // Continue with next day even if one fails
                        calendar.add(Calendar.DAY_OF_YEAR, -1)
                    }
                }

                withContext(Dispatchers.Main) {
                    usageAdapter.updateData(usageList)
                    updateUIStats(last7DaysMobile, last7DaysWifi, totalMobile, totalWifi)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error refreshing data", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.error_loading_data),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                isRefreshing = false
            }
        }
    }

    // Helper to fetch manual data if permission is missing
    private fun fetchManualData(calendar: Calendar): DailyUsage {
        val todayKey = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(calendar.time)
        val mobile = prefs.getLong(Constants.PREF_MANUAL_MOBILE_PREFIX + todayKey, 0L)
        val wifi = prefs.getLong(Constants.PREF_MANUAL_WIFI_PREFIX + todayKey, 0L)
        return DailyUsage(calendar.timeInMillis, mobile, wifi, mobile + wifi)
    }

    private fun getTodayMobileUsage(): Long {
        return try {
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startTime = calendar.timeInMillis
            val endTime = System.currentTimeMillis()

            NetworkUsageHelper.getUsageInRange(applicationContext, startTime, endTime).first
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting today mobile usage", e)
            0L
        }
    }

    // Fix race condition: Only check alerts in MainActivity when app is in foreground
    // SpeedService handles background alerts, so we skip here to avoid duplicates
    private fun checkDataAlerts() {
        // Skip alert checking in MainActivity - SpeedService handles it to avoid race conditions
        // This prevents duplicate notifications when both MainActivity and SpeedService check simultaneously
        // MainActivity will only show alerts if SpeedService is not running
        val showSpeed = prefs.getBoolean(Constants.PREF_SHOW_SPEED, true)
        val isAlertEnabled = prefs.getBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, false)
        
        // If SpeedService is running (either speed or alerts enabled), let it handle alerts
        if (showSpeed || isAlertEnabled) {
            return
        }

        if (!isAlertEnabled) return

        val limitMb = prefs.getFloat(Constants.PREF_DAILY_LIMIT_MB, 0f)
        if (limitMb <= 0f) return

        lifecycleScope.launch(Dispatchers.IO) {
            val todayUsageBytes = getTodayMobileUsage()
            val usageMb = todayUsageBytes / (1024f * 1024f)

            val todayStr = try {
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            } catch (e: Exception) {
                Log.e("MainActivity", "Error formatting date", e)
                ""
            }
            val lastChecked = prefs.getString(Constants.PREF_LAST_ALERT_DATE, "")

            if (todayStr.isNotEmpty() && todayStr != lastChecked) {
                prefs.edit {
                    putString(Constants.PREF_LAST_ALERT_DATE, todayStr)
                    putBoolean(Constants.PREF_ALERT_80_TRIGGERED, false)
                    putBoolean(Constants.PREF_ALERT_100_TRIGGERED, false)
                }
            }

            val alert80 = prefs.getBoolean(Constants.PREF_ALERT_80_TRIGGERED, false)
            val alert100 = prefs.getBoolean(Constants.PREF_ALERT_100_TRIGGERED, false)

            withContext(Dispatchers.Main) {
                if (!alert100 && usageMb >= limitMb) {
                    val message = getString(
                        R.string.limit_reached_message,
                        String.format(Locale.US, "%.0f", limitMb)
                    )
                    showNotification(getString(R.string.daily_limit_reached), message)
                    prefs.edit { putBoolean(Constants.PREF_ALERT_100_TRIGGERED, true) }
                } else if (!alert80 && !alert100 && usageMb >= (limitMb * 0.8)) {
                    val message = getString(
                        R.string.limit_warning_message,
                        String.format(Locale.US, "%.1f", usageMb)
                    )
                    showNotification(getString(R.string.daily_limit_warning), message)
                    prefs.edit { putBoolean(Constants.PREF_ALERT_80_TRIGGERED, true) }
                }
            }
        }
    }

    private fun showNotification(title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent =
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, Constants.ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speed)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(Constants.NOTIFICATION_ID + 1, builder.build())
        } catch (e: Exception) {
            Log.e("MainActivity", "Error showing notification.", e)
        }
    }

    private fun createNotificationChannel() {
        val name = "Data Usage Alerts"
        val descriptionText = "Notifications for daily data limits"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(Constants.ALERT_CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.createNotificationChannel(channel)
    }

    private fun updateUIStats(m7: Long, w7: Long, m30: Long, w30: Long) {
        binding.apply {
            tv7DaysMobile.text = formatData(m7)
            tv7DaysWifi.text = formatData(w7)
            tv7DaysTotal.text = formatData(m7 + w7)

            tv30DaysMobile.text = formatData(m30)
            tv30DaysWifi.text = formatData(w30)
            tv30DaysTotal.text = formatData(m30 + w30)

            tvTotalMobile.text = formatData(m30)
            tvTotalWifi.text = formatData(w30)
            tvGrandTotal.text = formatData(m30 + w30)
        }
    }


    private fun formatData(bytes: Long): String {
        return try {
            val showInMbOnly = prefs.getBoolean(Constants.PREF_UNIT_IN_MB, false)
            if (showInMbOnly) {
                return String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f))
            }

            when {
                bytes >= 1073741824 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824f)
                bytes >= 1048576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576f)
                bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
                else -> "$bytes B"
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error formatting data", e)
            "0 B"
        }
    }

    private fun recordAppOpen() {
        val openCount = prefs.getInt(Constants.PREF_APP_OPEN_COUNT, 0) + 1
        prefs.edit { putInt(Constants.PREF_APP_OPEN_COUNT, openCount) }
    }


}