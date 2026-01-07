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
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.GravityCompat
import androidx.core.view.isEmpty
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
    private var networkStatsManager: NetworkStatsManager? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var usageAdapter: UsageAdapter

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

        networkStatsManager = getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager

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

        val showSpeed = prefs.getBoolean(Constants.PREF_SHOW_SPEED, true)
        if (showSpeed) {
            startSpeedService()
        }

        // Reset dismissed flag on app start so prompt can show again in new session
        prefs.edit().putBoolean(Constants.PREF_BATTERY_OPT_DISMISSED, false).apply()

        checkBatteryOptimization()
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

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return

            // Only prompt if not ignoring and we haven't asked before
            if (!powerManager.isIgnoringBatteryOptimizations(packageName) &&
                !prefs.getBoolean(Constants.PREF_BATTERY_OPT_ASKED, false)) {

                // Cancel any existing runnable to prevent duplicates
                batteryOptRunnable?.let { binding.root.removeCallbacks(it) }

                // Delay the prompt to not interrupt user flow - show after 3 seconds
                // This gives users time to see the app first
                batteryOptRunnable = Runnable {
                    // Check if activity is still valid before showing dialog
                    if (!isFinishing && !isDestroyed && !prefs.getBoolean(Constants.PREF_BATTERY_OPT_DISMISSED, false)) {
                        showBatteryOptimizationDialog()
                    }
                }
                binding.root.postDelayed(batteryOptRunnable!!, 3000) // 3 second delay
            }
        }
    }

    private fun showBatteryOptimizationDialog() {
        try {
            val dialogView = layoutInflater.inflate(R.layout.dialog_battery_optimization, null)
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create()

            dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOpenSettings)
                ?.setOnClickListener {
                    try {
                        val intent = Intent().apply {
                            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                        prefs.edit().putBoolean(Constants.PREF_BATTERY_OPT_ASKED, true).apply()
                        dialog.dismiss()
                    } catch (e: ActivityNotFoundException) {
                        Log.e("MainActivity", "Could not open battery optimization settings.", e)
                        Toast.makeText(this, getString(R.string.battery_optimization_error), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error opening battery optimization settings.", e)
                        e.printStackTrace()
                    }
                }

            dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNotNow)
                ?.setOnClickListener {
                    // User dismissed - don't show again in this session
                    prefs.edit().putBoolean(Constants.PREF_BATTERY_OPT_DISMISSED, true).apply()
                    dialog.dismiss()
                }

            dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDontShow)
                ?.setOnClickListener {
                    // User doesn't want to see this prompt
                    prefs.edit()
                        .putBoolean(Constants.PREF_BATTERY_OPT_ASKED, true)
                        .putBoolean(Constants.PREF_BATTERY_OPT_DISMISSED, true)
                        .apply()
                    dialog.dismiss()
                }

            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error showing battery optimization dialog", e)
            // Fallback to simple dialog if custom layout fails
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.battery_optimization_title))
                .setMessage(getString(R.string.battery_optimization_message))
                .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                    try {
                        val intent = Intent().apply {
                            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                        prefs.edit().putBoolean(Constants.PREF_BATTERY_OPT_ASKED, true).apply()
                    } catch (ex: Exception) {
                        Log.e("MainActivity", "Error opening battery optimization settings.", ex)
                        Toast.makeText(this, getString(R.string.battery_optimization_error), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.not_now)) { _, _ ->
                    prefs.edit().putBoolean(Constants.PREF_BATTERY_OPT_DISMISSED, true).apply()
                }
                .setNeutralButton(getString(R.string.dont_show_again)) { _, _ ->
                    prefs.edit()
                        .putBoolean(Constants.PREF_BATTERY_OPT_ASKED, true)
                        .putBoolean(Constants.PREF_BATTERY_OPT_DISMISSED, true)
                        .apply()
                }
                .setCancelable(true)
                .show()
        }
    }

    private fun applyThemeFromPrefs() {
        try {
            val themeMode = prefs.getInt(Constants.PREF_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
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
            try {
                startForegroundService(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start foreground service", e)
            }
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

    override fun onDestroy() {
        super.onDestroy()
        // Fix memory leak: Cancel battery optimization runnable
        batteryOptRunnable?.let { binding.root.removeCallbacks(it) }
        batteryOptRunnable = null

        // Fix memory leak: Clean up handler callbacks
        checkAlertsHandler.removeCallbacksAndMessages(null)
    }

    // Replaced with OnBackPressedCallback
    // override fun onBackPressed() { ... }

    private fun setupPermissions() {
        binding.btnPermission.setOnClickListener {
            showPermissionDialog()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun showPermissionDialog() {
        try {
            val dialogView = layoutInflater.inflate(R.layout.dialog_permission, null)
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create()

            dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGrantPermission)
                ?.setOnClickListener {
                    try {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        dialog.dismiss()
                    } catch (e: ActivityNotFoundException) {
                        Log.e("MainActivity", "Could not open usage access settings.", e)
                        Toast.makeText(this, "Could not open settings. Please enable Usage Access manually.", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error opening usage access settings.", e)
                        Toast.makeText(this, "Could not open settings. Please enable Usage Access manually.", Toast.LENGTH_SHORT).show()
                    }
                }

            dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelPermission)
                ?.setOnClickListener {
                    dialog.dismiss()
                }

            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error showing permission dialog", e)
            // Fallback to simple dialog if custom layout fails
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.permission_required))
                .setMessage(getString(R.string.permission_explanation))
                .setPositiveButton(getString(R.string.grant_usage_permission)) { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    } catch (ex: Exception) {
                        Log.e("MainActivity", "Error opening usage access settings.", ex)
                        Toast.makeText(this, "Could not open settings. Please enable Usage Access manually.", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
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
        if (navView.isEmpty()) {
            Log.w("MainActivity", "NavigationView is empty, skipping setup")
            return
        }

        // Safely find all views with null checks
        val switchShowSpeed = navView.findViewById<MaterialSwitch>(R.id.switchShowSpeed)
        val switchShowUpDown = navView.findViewById<MaterialSwitch>(R.id.switchShowUpDown)
        val switchShowWifiSignal = navView.findViewById<MaterialSwitch>(R.id.switchShowWifiSignal)
        val btnResetData = navView.findViewById<TextView>(R.id.btnResetData)
        val btnStopExit = navView.findViewById<TextView>(R.id.btnStopExit)
        val btnClose = navView.findViewById<TextView>(R.id.btnClose)
        val btnRateUs = navView.findViewById<View>(R.id.btnRateUs)

        val switchDataAlert = navView.findViewById<MaterialSwitch>(R.id.switchDataAlert)
        val layoutDataLimitOptions = navView.findViewById<View>(R.id.layoutDataLimitOptions)
        val etDataLimit = navView.findViewById<TextInputEditText>(R.id.etDataLimit)
        val tvUnitSelection = navView.findViewById<AutoCompleteTextView>(R.id.tvUnitSelection)
        val tvLimitError = navView.findViewById<TextView>(R.id.tvLimitError)

        val radioGroupTheme = navView.findViewById<RadioGroup>(R.id.radioGroupTheme)

        // Validate critical views exist
        if (switchShowSpeed == null || switchShowUpDown == null || switchShowWifiSignal == null ||
            btnResetData == null || btnStopExit == null || btnClose == null || btnRateUs == null ||
            switchDataAlert == null || layoutDataLimitOptions == null || etDataLimit == null ||
            tvUnitSelection == null || tvLimitError == null || radioGroupTheme == null) {
            Log.e("MainActivity", "Critical views not found in side panel, setup incomplete")
            return
        }

        val units = arrayOf("MB", "GB")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, units)
        tvUnitSelection.setAdapter(adapter)

        switchShowSpeed.isChecked = prefs.getBoolean(Constants.PREF_SHOW_SPEED, true)
        switchShowUpDown.isChecked = prefs.getBoolean(Constants.PREF_SHOW_UP_DOWN, false)
        switchShowWifiSignal.isChecked = prefs.getBoolean(Constants.PREF_SHOW_WIFI_SIGNAL, false)

        val isAlertEnabled = prefs.getBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, false)
        switchDataAlert.isChecked = isAlertEnabled
        layoutDataLimitOptions.visibility = if (isAlertEnabled) View.VISIBLE else View.GONE

        val savedUnit = prefs.getString(Constants.PREF_SELECTED_UNIT, "MB") ?: "MB"
        tvUnitSelection.setText(savedUnit, false)

        val savedLimitMb = prefs.getFloat(Constants.PREF_DAILY_LIMIT_MB, 0f)
        if (savedLimitMb > 0) {
            val displayValue = if (savedUnit == "GB") savedLimitMb / 1024f else savedLimitMb
            val text = if (displayValue % 1.0 == 0.0) displayValue.toInt().toString() else displayValue.toString()
            etDataLimit.setText(text)
        }

        radioGroupTheme.setOnCheckedChangeListener(null)

        val currentTheme = prefs.getInt(Constants.PREF_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        when (currentTheme) {
            AppCompatDelegate.MODE_NIGHT_NO -> radioGroupTheme.check(R.id.radioThemeLight)
            AppCompatDelegate.MODE_NIGHT_YES -> radioGroupTheme.check(R.id.radioThemeDark)
            else -> radioGroupTheme.check(R.id.radioThemeSystem)
        }

        switchShowSpeed.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(Constants.PREF_SHOW_SPEED, isChecked).apply()
            if (isChecked) startSpeedService() else stopService(Intent(this, SpeedService::class.java))
        }

        switchShowUpDown.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(Constants.PREF_SHOW_UP_DOWN, isChecked).apply()
            if (switchShowSpeed.isChecked) startSpeedService()
        }

        switchShowWifiSignal.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(Constants.PREF_SHOW_WIFI_SIGNAL, isChecked).apply()
            if (switchShowSpeed.isChecked) startSpeedService()
        }

        switchDataAlert.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, isChecked).apply()
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
                .putFloat(Constants.PREF_DAILY_LIMIT_MB, limitMb)
                .putString(Constants.PREF_SELECTED_UNIT, unit)
                .putBoolean(Constants.PREF_ALERT_80_TRIGGERED, false)
                .putBoolean(Constants.PREF_ALERT_100_TRIGGERED, false)
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

            if (prefs.getInt(Constants.PREF_THEME_MODE, -100) != mode) {
                prefs.edit().putInt(Constants.PREF_THEME_MODE, mode).apply()
                group.post { AppCompatDelegate.setDefaultNightMode(mode) }
            }
        }

        btnResetData.setOnClickListener {
            // Show confirmation dialog before resetting
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.confirm_reset_title))
                .setMessage(getString(R.string.confirm_reset_message))
                .setPositiveButton(getString(R.string.confirm_reset)) { _, _ ->
                    prefs.edit().putLong(Constants.PREF_RESET_TIMESTAMP, System.currentTimeMillis()).apply()
                    Toast.makeText(this, getString(R.string.data_usage_reset), Toast.LENGTH_SHORT).show()
                    refreshData()
                    binding.drawerLayout.closeDrawer(GravityCompat.END)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        btnStopExit.setOnClickListener {
            // Show confirmation dialog before stopping
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.confirm_stop_exit_title))
                .setMessage(getString(R.string.confirm_stop_exit_message))
                .setPositiveButton(getString(R.string.stop_and_exit)) { _, _ ->
                    stopService(Intent(this, SpeedService::class.java))
                    finish()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        btnClose.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.END)
        }

        btnRateUs.setOnClickListener {
            showRateUsFlow()
        }
    }

    private fun refreshData() {
        if (!hasUsageStatsPermission()) {
            // Show helpful message if permission not granted
            if (binding.btnPermission.visibility != View.VISIBLE) {
                binding.btnPermission.visibility = View.VISIBLE
            }
            return
        }

        // Hide permission button if visible
        binding.btnPermission.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val usageList = ArrayList<DailyUsage>()
                val calendar = Calendar.getInstance()
                val resetTimestamp = prefs.getLong(Constants.PREF_RESET_TIMESTAMP, 0L)

                var totalMobile = 0L
                var totalWifi = 0L
                var last7DaysMobile = 0L
                var last7DaysWifi = 0L

                for (i in 0 until 30) {
                    try {
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
                    Toast.makeText(this@MainActivity, getString(R.string.error_loading_data), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getTodayMobileUsage(): Long {
        return try {
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            val startTime = calendar.timeInMillis
            val endTime = System.currentTimeMillis()

            val resetTimestamp = prefs.getLong(Constants.PREF_RESET_TIMESTAMP, 0L)
            val queryStartTime = if (startTime < resetTimestamp) resetTimestamp else startTime

            if (endTime <= resetTimestamp) return 0L

            getUsage(ConnectivityManager.TYPE_MOBILE, queryStartTime, endTime)
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
        if (showSpeed) {
            // SpeedService is running, let it handle alerts
            return
        }

        // Only check alerts if SpeedService is not running
        val isEnabled = prefs.getBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, false)
        if (!isEnabled) return

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
                prefs.edit()
                    .putString(Constants.PREF_LAST_ALERT_DATE, todayStr)
                    .putBoolean(Constants.PREF_ALERT_80_TRIGGERED, false)
                    .putBoolean(Constants.PREF_ALERT_100_TRIGGERED, false)
                    .apply()
            }

            val alert80 = prefs.getBoolean(Constants.PREF_ALERT_80_TRIGGERED, false)
            val alert100 = prefs.getBoolean(Constants.PREF_ALERT_100_TRIGGERED, false)

            withContext(Dispatchers.Main) {
                if (!alert100 && usageMb >= limitMb) {
                    val message = getString(R.string.limit_reached_message, String.format(Locale.US, "%.0f", limitMb))
                    showNotification(getString(R.string.daily_limit_reached), message)
                    prefs.edit().putBoolean(Constants.PREF_ALERT_100_TRIGGERED, true).apply()
                } else if (!alert80 && !alert100 && usageMb >= (limitMb * 0.8)) {
                    val message = getString(R.string.limit_warning_message, String.format(Locale.US, "%.1f", usageMb))
                    showNotification(getString(R.string.daily_limit_warning), message)
                    prefs.edit().putBoolean(Constants.PREF_ALERT_80_TRIGGERED, true).apply()
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

        val builder = NotificationCompat.Builder(this, Constants.ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(Constants.NOTIFICATION_ID + 1, builder.build())
        } catch (e: Exception) {
            Log.e("MainActivity", "Error showing notification.", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Data Usage Alerts"
            val descriptionText = "Notifications for daily data limits"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(Constants.ALERT_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
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
        if (networkStatsManager == null) return 0L

        var totalBytes = 0L
        var networkStats: NetworkStats? = null
        try {
            val bucket = NetworkStats.Bucket()
            // USE querySummary to iterate over buckets
            networkStats = networkStatsManager?.querySummary(networkType, null, startTime, endTime)

            // Fix: Add null check for querySummary result
            if (networkStats == null) {
                Log.w("MainActivity", "querySummary returned null for networkType: $networkType")
                return 0L
            }

            while (networkStats.hasNextBucket()) {
                networkStats.getNextBucket(bucket)
                val bytes = bucket.rxBytes + bucket.txBytes

                // 🛡️ SANITY CHECK: Filter out Garbage Data (anything > 100 TB is a glitch)
                if (bytes > 0 && bytes < 100L * 1024 * 1024 * 1024 * 1024) {
                    totalBytes += bytes
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting network usage.", e)
        } finally {
            networkStats?.close()
        }
        return totalBytes
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
        prefs.edit().putInt(Constants.PREF_APP_OPEN_COUNT, openCount).apply()
    }

    private fun showRateUsFlow() {
        val manager = ReviewManagerFactory.create(this)
        val openCount = prefs.getInt(Constants.PREF_APP_OPEN_COUNT, 0)
        val lastReview = prefs.getLong(Constants.PREF_LAST_REVIEW_PROMPT, 0L)

        val isEligible = openCount >= 5 &&
                (lastReview == 0L || System.currentTimeMillis() - lastReview > TimeUnit.DAYS.toMillis(30))

        if (isEligible) {
            val request = manager.requestReviewFlow()
            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val reviewInfo = task.result
                    val flow = manager.launchReviewFlow(this, reviewInfo)
                    flow.addOnCompleteListener {
                        prefs.edit().putLong(Constants.PREF_LAST_REVIEW_PROMPT, System.currentTimeMillis()).apply()
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