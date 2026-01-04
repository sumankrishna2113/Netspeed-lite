package com.krishna.netspeedlite

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.play.core.review.ReviewManagerFactory
import com.krishna.netspeedlite.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var networkStatsManager: NetworkStatsManager
    private lateinit var prefs: SharedPreferences
    private lateinit var usageAdapter: UsageAdapter

    private val checkAlertsHandler = Handler(Looper.getMainLooper())
    private val checkAlertsRunnable = object : Runnable {
        override fun run() {
            checkDataAlerts()
            checkAlertsHandler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        applyThemeFromPrefs()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        networkStatsManager = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

        setupPermissions()
        setupUI()
        binding.root.post {
            try {
                setupSidePanel()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        createNotificationChannel()

        refreshData()

        val showSpeed = prefs.getBoolean("show_speed", true)
        if (showSpeed) {
            startSpeedService()
        }

        recordAppOpen()
    }

    private fun applyThemeFromPrefs() {
        try {
            val themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            if (AppCompatDelegate.getDefaultNightMode() != themeMode) {
                AppCompatDelegate.setDefaultNightMode(themeMode)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startSpeedService() {
        val intent = Intent(this, SpeedService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                openSidePanel()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasUsageStatsPermission()) {
            binding.btnPermission.visibility = View.GONE
            refreshData()
            checkDataAlerts()
            checkAlertsHandler.removeCallbacks(checkAlertsRunnable)
            checkAlertsHandler.post(checkAlertsRunnable)
        } else {
            binding.btnPermission.visibility = View.VISIBLE
        }
    }

    override fun onPause() {
        super.onPause()
        checkAlertsHandler.removeCallbacks(checkAlertsRunnable)
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.END)) {
            binding.drawerLayout.closeDrawer(GravityCompat.END)
        } else {
            super.onBackPressed()
        }
    }

    private fun setupPermissions() {
        binding.btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
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

    private fun setupUI() {
        usageAdapter = UsageAdapter()
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = usageAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun openSidePanel() {
        binding.drawerLayout.openDrawer(GravityCompat.END)
    }

    private fun setupSidePanel() {
        val navView = binding.navView
        if (navView.childCount == 0) return

        val switchShowSpeed = navView.findViewById<MaterialSwitch>(R.id.switchShowSpeed) ?: return
        val switchShowUpDown = navView.findViewById<MaterialSwitch>(R.id.switchShowUpDown) ?: return
        val switchShowWifiSignal = navView.findViewById<MaterialSwitch>(R.id.switchShowWifiSignal) ?: return
        val btnResetData = navView.findViewById<TextView>(R.id.btnResetData) ?: return
        val btnStopExit = navView.findViewById<TextView>(R.id.btnStopExit) ?: return
        val btnClose = navView.findViewById<TextView>(R.id.btnClose) ?: return
        val btnRateUs = navView.findViewById<View>(R.id.btnRateUs) ?: return

        val switchDataAlert = navView.findViewById<MaterialSwitch>(R.id.switchDataAlert) ?: return
        val layoutDataLimitOptions = navView.findViewById<View>(R.id.layoutDataLimitOptions) ?: return
        val etDataLimit = navView.findViewById<TextInputEditText>(R.id.etDataLimit) ?: return
        val tvUnitSelection = navView.findViewById<AutoCompleteTextView>(R.id.tvUnitSelection) ?: return
        val tvLimitError = navView.findViewById<TextView>(R.id.tvLimitError) ?: return

        val radioGroupTheme = navView.findViewById<RadioGroup>(R.id.radioGroupTheme) ?: return

        val units = arrayOf("MB", "GB")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, units)
        tvUnitSelection.setAdapter(adapter)

        switchShowSpeed.isChecked = prefs.getBoolean("show_speed", true)
        switchShowUpDown.isChecked = prefs.getBoolean("show_up_down", false)
        switchShowWifiSignal.isChecked = prefs.getBoolean("show_wifi_signal", false)

        val isAlertEnabled = prefs.getBoolean("daily_limit_enabled", false)
        switchDataAlert.isChecked = isAlertEnabled
        layoutDataLimitOptions.visibility = if (isAlertEnabled) View.VISIBLE else View.GONE

        val savedUnit = prefs.getString("selected_unit", "MB") ?: "MB"
        tvUnitSelection.setText(savedUnit, false)

        val savedLimitMb = prefs.getFloat("daily_limit_mb", 0f)
        if (savedLimitMb > 0) {
            val displayValue = if (savedUnit == "GB") savedLimitMb / 1024f else savedLimitMb
            val text = if (displayValue % 1.0 == 0.0) displayValue.toInt().toString() else displayValue.toString()
            etDataLimit.setText(text)
        }

        radioGroupTheme.setOnCheckedChangeListener(null)

        val currentTheme = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        when (currentTheme) {
            AppCompatDelegate.MODE_NIGHT_NO -> radioGroupTheme.check(R.id.radioThemeLight)
            AppCompatDelegate.MODE_NIGHT_YES -> radioGroupTheme.check(R.id.radioThemeDark)
            else -> radioGroupTheme.check(R.id.radioThemeSystem)
        }

        switchShowSpeed.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_speed", isChecked).apply()
            if (isChecked) startSpeedService() else stopService(Intent(this, SpeedService::class.java))
        }

        switchShowUpDown.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_up_down", isChecked).apply()
            if (switchShowSpeed.isChecked) startSpeedService()
        }

        switchShowWifiSignal.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_wifi_signal", isChecked).apply()
            if (switchShowSpeed.isChecked) startSpeedService()
        }

        switchDataAlert.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("daily_limit_enabled", isChecked).apply()
            layoutDataLimitOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                checkDataAlerts()
            }
        }

        fun saveLimit() {
            val rawText = etDataLimit.text.toString()
            val unit = tvUnitSelection.text.toString()

            if (rawText.isBlank()) {
                if (switchDataAlert.isChecked) tvLimitError.visibility = View.VISIBLE
                return
            }

            val rawValue = rawText.toFloatOrNull()
            if (rawValue == null || rawValue <= 0) {
                if (switchDataAlert.isChecked) tvLimitError.visibility = View.VISIBLE
                return
            }

            tvLimitError.visibility = View.GONE

            val limitMb = if (unit == "GB") rawValue * 1024f else rawValue

            prefs.edit()
                .putFloat("daily_limit_mb", limitMb)
                .putString("selected_unit", unit)
                .putBoolean("alert_80_triggered", false)
                .putBoolean("alert_100_triggered", false)
                .apply()
        }

        etDataLimit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { saveLimit() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        etDataLimit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                checkDataAlerts()
            }
        }

        tvUnitSelection.setOnItemClickListener { _, _, _, _ ->
            saveLimit()
            checkDataAlerts()
        }

        radioGroupTheme.setOnCheckedChangeListener { group, checkedId ->
            val mode = when (checkedId) {
                R.id.radioThemeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.radioThemeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }

            if (prefs.getInt("theme_mode", -100) != mode) {
                prefs.edit().putInt("theme_mode", mode).apply()
                group.post { AppCompatDelegate.setDefaultNightMode(mode) }
            }
        }

        btnResetData.setOnClickListener {
            prefs.edit().putLong("reset_timestamp", System.currentTimeMillis()).apply()
            Toast.makeText(this, "Data Usage Reset", Toast.LENGTH_SHORT).show()
            refreshData()
            binding.drawerLayout.closeDrawer(GravityCompat.END)
        }

        btnStopExit.setOnClickListener {
            stopService(Intent(this, SpeedService::class.java))
            finishAffinity()
        }

        btnClose.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.END)
        }

        btnRateUs.setOnClickListener {
            showRateUsFlow()
        }
    }

    private fun refreshData() {
        if (!hasUsageStatsPermission()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val usageList = ArrayList<DailyUsage>()
            val calendar = Calendar.getInstance()
            val resetTimestamp = prefs.getLong("reset_timestamp", 0L)

            var totalMobile = 0L
            var totalWifi = 0L
            var last7DaysMobile = 0L
            var last7DaysWifi = 0L

            for (i in 0 until 30) {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                val startTime = calendar.timeInMillis

                calendar.set(Calendar.HOUR_OF_DAY, 23)
                calendar.set(Calendar.MINUTE, 59)
                calendar.set(Calendar.SECOND, 59)
                val endTime = calendar.timeInMillis

                val queryStartTime = if (startTime < resetTimestamp) resetTimestamp else startTime

                var mobile = 0L
                var wifi = 0L

                if (endTime > resetTimestamp) {
                    mobile = getUsage(ConnectivityManager.TYPE_MOBILE, queryStartTime, endTime)
                    wifi = getUsage(ConnectivityManager.TYPE_WIFI, queryStartTime, endTime)
                }

                usageList.add(DailyUsage(startTime, mobile, wifi, mobile + wifi))

                totalMobile += mobile
                totalWifi += wifi
                if (i < 7) {
                    last7DaysMobile += mobile
                    last7DaysWifi += wifi
                }

                calendar.add(Calendar.DAY_OF_YEAR, -1)
            }

            withContext(Dispatchers.Main) {
                usageAdapter.updateData(usageList)
                updateUIStats(last7DaysMobile, last7DaysWifi, totalMobile, totalWifi)
            }
        }
    }

    private fun getTodayMobileUsage(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val resetTimestamp = prefs.getLong("reset_timestamp", 0L)
        val queryStartTime = if (startTime < resetTimestamp) resetTimestamp else startTime

        if (endTime <= resetTimestamp) return 0L

        return getUsage(ConnectivityManager.TYPE_MOBILE, queryStartTime, endTime)
    }

    private fun checkDataAlerts() {
        val isEnabled = prefs.getBoolean("daily_limit_enabled", false)
        if (!isEnabled) return

        val limitMb = prefs.getFloat("daily_limit_mb", 0f)
        if (limitMb <= 0f) return

        lifecycleScope.launch(Dispatchers.IO) {
            val todayUsageBytes = getTodayMobileUsage()
            val usageMb = todayUsageBytes / (1024f * 1024f)

            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val lastChecked = prefs.getString("last_alert_date", "")

            if (todayStr != lastChecked) {
                prefs.edit()
                    .putString("last_alert_date", todayStr)
                    .putBoolean("alert_80_triggered", false)
                    .putBoolean("alert_100_triggered", false)
                    .apply()
            }

            val alert80 = prefs.getBoolean("alert_80_triggered", false)
            val alert100 = prefs.getBoolean("alert_100_triggered", false)

            withContext(Dispatchers.Main) {
                if (!alert100 && usageMb >= limitMb) {
                    showNotification("Daily Limit Reached",
                        "You have reached your daily limit of ${String.format(Locale.US, "%.0f", limitMb)} MB.")
                    prefs.edit().putBoolean("alert_100_triggered", true).apply()
                } else if (!alert80 && !alert100 && usageMb >= (limitMb * 0.8)) {
                    showNotification("Daily Limit Warning",
                        "You have used 80% of your daily limit (${String.format(Locale.US, "%.1f", usageMb)} MB).")
                    prefs.edit().putBoolean("alert_80_triggered", true).apply()
                }
            }
        }
    }

    private fun showNotification(title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, "data_alert_channel")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Data Usage Alerts"
            val descriptionText = "Notifications for daily data limits"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel("data_alert_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
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

    /**
     * ⭐ FIXED FUNCTION: Uses querySummary (with bucket iteration) + Sanity Check
     * Replaces broken querySummaryForDevice.
     */
    private fun getUsage(networkType: Int, startTime: Long, endTime: Long): Long {
        var totalBytes = 0L
        try {
            val bucket = NetworkStats.Bucket()
            // USE querySummary to iterate over buckets
            val networkStats = networkStatsManager.querySummary(networkType, null, startTime, endTime)

            while (networkStats.hasNextBucket()) {
                networkStats.getNextBucket(bucket)
                val bytes = bucket.rxBytes + bucket.txBytes

                // 🛡️ SANITY CHECK: Filter out Garbage Data (anything > 100 TB is a glitch)
                if (bytes > 0 && bytes < 100L * 1024 * 1024 * 1024 * 1024) {
                    totalBytes += bytes
                }
            }
            networkStats.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return totalBytes
    }

    private fun formatData(bytes: Long): String {
        val showInMbOnly = prefs.getBoolean("unit_in_mb", false)
        if (showInMbOnly) {
            return String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f))
        }

        return when {
            bytes >= 1073741824 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824f)
            bytes >= 1048576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576f)
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
            else -> "$bytes B"
        }
    }

    private fun recordAppOpen() {
        val openCount = prefs.getInt("app_open_count", 0) + 1
        prefs.edit().putInt("app_open_count", openCount).apply()
    }

    private fun showRateUsFlow() {
        val manager = ReviewManagerFactory.create(this)
        val openCount = prefs.getInt("app_open_count", 0)
        val lastReview = prefs.getLong("last_review_prompt", 0L)

        val isEligible = openCount >= 5 &&
                (lastReview == 0L || System.currentTimeMillis() - lastReview > TimeUnit.DAYS.toMillis(30))

        if (isEligible) {
            val request = manager.requestReviewFlow()
            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val reviewInfo = task.result
                    val flow = manager.launchReviewFlow(this, reviewInfo)
                    flow.addOnCompleteListener {
                        prefs.edit().putLong("last_review_prompt", System.currentTimeMillis()).apply()
                    }
                } else {
                    openPlayStoreForRating()
                }
            }
        } else {
            openPlayStoreForRating()
        }
    }

    private fun openPlayStoreForRating() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
            startActivity(intent)
        }
    }
}