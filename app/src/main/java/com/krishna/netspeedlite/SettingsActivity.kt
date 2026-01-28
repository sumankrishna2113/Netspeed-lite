package com.krishna.netspeedlite

import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.krishna.netspeedlite.databinding.ActivitySettingsBinding

import androidx.activity.enableEdgeToEdge

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set toolbar as action bar (for proper back behavior)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        
        // Custom back button click handler
        binding.btnBack.setOnClickListener { finish() }

        prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        setupUI()
        setupPermissionControls()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
    }

    private fun setupUI() {
        val switchShowSpeed = binding.switchShowSpeed
        val switchShowUpDown = binding.switchShowUpDown
        val switchShowWifiSignal = binding.switchShowWifiSignal
        val switchDataAlert = binding.switchDataAlert
        val layoutDataLimitOptions = binding.layoutDataLimitOptions
        val etDataLimit = binding.etDataLimit
        val tvLimitError = binding.tvLimitError
        val tvUnitSelection = binding.tvUnitSelection
        val radioGroupTheme = binding.radioGroupTheme
        val btnStopExit = binding.btnStopExit
        val btnRateUs = binding.btnRateUs
        val btnPrivacy = binding.btnPrivacy

        // Initialize values
        switchShowSpeed.isChecked = prefs.getBoolean(Constants.PREF_SHOW_SPEED, true)
        switchShowUpDown.isChecked = prefs.getBoolean(Constants.PREF_SHOW_UP_DOWN, false)
        switchShowWifiSignal.isChecked = prefs.getBoolean(Constants.PREF_SHOW_WIFI_SIGNAL, false)
        
        // Data Alert Init
        val isAlertEnabled = prefs.getBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, false)
        switchDataAlert.isChecked = isAlertEnabled
        layoutDataLimitOptions.visibility = if (isAlertEnabled) View.VISIBLE else View.GONE

        val savedUnit = prefs.getString(Constants.PREF_SELECTED_UNIT, "MB") ?: "MB"
        val savedLimitMb = prefs.getFloat(Constants.PREF_DAILY_LIMIT_MB, 0f)
        
        // Convert to display value
        if (savedLimitMb > 0) {
            val displayValue = if (savedUnit == "GB") savedLimitMb / 1024.0 else savedLimitMb.toDouble()
            val limitText = if (displayValue % 1.0 == 0.0) displayValue.toLong().toString() else displayValue.toString()
            etDataLimit.setText(limitText)
        }
        
        tvUnitSelection.setText(savedUnit, false)

        // Theme Init
        val currentMode = prefs.getInt(Constants.PREF_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        when (currentMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> radioGroupTheme.check(R.id.radioThemeLight)
            AppCompatDelegate.MODE_NIGHT_YES -> radioGroupTheme.check(R.id.radioThemeDark)
            else -> radioGroupTheme.check(R.id.radioThemeSystem)
        }


        // Listeners
        // Helper to manage service state
        fun checkServiceState() {
             val showSpeed = prefs.getBoolean(Constants.PREF_SHOW_SPEED, true)
             val isAlert = prefs.getBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, false)
             val intent = Intent(this, SpeedService::class.java)
             if (showSpeed || isAlert) {
                 try {
                     startForegroundService(intent)
                 } catch (e: Exception) {
                     // Ignore foreground service start errors

                 }
             } else {
                 stopService(intent)
             }
        }

        switchShowSpeed.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(Constants.PREF_SHOW_SPEED, isChecked) }
            checkServiceState()
        }

        switchShowUpDown.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(Constants.PREF_SHOW_UP_DOWN, isChecked) }
        }

        switchShowWifiSignal.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // No permission needed anymore
            }
            prefs.edit { putBoolean(Constants.PREF_SHOW_WIFI_SIGNAL, isChecked) }
        }

        switchDataAlert.setOnCheckedChangeListener { _, isChecked ->
            layoutDataLimitOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
            
            // Reset all trigger flags and date to enable fresh testing
            prefs.edit {
                putBoolean(Constants.PREF_DAILY_LIMIT_ENABLED, isChecked)
                putBoolean(Constants.PREF_ALERT_80_TRIGGERED, false)
                putBoolean(Constants.PREF_ALERT_100_TRIGGERED, false)
                putString(Constants.PREF_LAST_ALERT_DATE, "") // Reset date to allow re-triggering
            }
            
            checkServiceState()
        }

        // Dropdown setup
        val units = arrayOf("MB", "GB")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, units)
        tvUnitSelection.setAdapter(adapter)

        val saveLimitLocally = {
            val limitStr = etDataLimit.text.toString()
            val selectedUnit = tvUnitSelection.text.toString()
            
            if (limitStr.isEmpty()) {
                if (switchDataAlert.isChecked) {
                    // Logic Fix: If alerts are ON but user clears text, treat it as "Invalid" or "Disable"?
                    // Better UX: Don't allow empty if ON. Show error.
                    tvLimitError.text = getString(R.string.data_limit_error)
                    tvLimitError.visibility = View.VISIBLE
                } else {
                    // If alerts are OFF and empty, safe to clear the pref to 0
                    prefs.edit { putFloat(Constants.PREF_DAILY_LIMIT_MB, 0f) }
                    tvLimitError.visibility = View.GONE
                }
            } else {
                try {
                    val limitVal = limitStr.toDouble()
                    if (limitVal <= 0) {
                         tvLimitError.text = getString(R.string.data_limit_error)
                         if (switchDataAlert.isChecked) tvLimitError.visibility = View.VISIBLE
                    } else {
                        val maxLimitMb = 10_000_000f // 10TB
                        val limitInMB = if (selectedUnit == "GB") limitVal * 1024 else limitVal
                        
                        if (limitInMB > maxLimitMb) {
                             tvLimitError.text = getString(R.string.data_limit_too_large)
                             tvLimitError.visibility = View.VISIBLE
                        } else {
                             prefs.edit {
                                putFloat(Constants.PREF_DAILY_LIMIT_MB, limitInMB.toFloat())
                                putString(Constants.PREF_SELECTED_UNIT, selectedUnit)
                                // Reset alerts when limit changes to allow re-triggering
                                putBoolean(Constants.PREF_ALERT_80_TRIGGERED, false)
                                putBoolean(Constants.PREF_ALERT_100_TRIGGERED, false)
                                putString(Constants.PREF_LAST_ALERT_DATE, "") // Reset for fresh testing
                             }
                             tvLimitError.visibility = View.GONE

                        }
                    }
                } catch (e: NumberFormatException) {
                     tvLimitError.text = getString(R.string.data_limit_error)
                     tvLimitError.visibility = View.VISIBLE
                }
            }
        }

        // Save only when user is "Done" typing or leaves the field
        etDataLimit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                saveLimitLocally()
                etDataLimit.clearFocus() // Hide keyboard and clear focus
                // Hide keyboard logic
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(etDataLimit.windowToken, 0)
                true
            } else {
                false
            }
        }

        etDataLimit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveLimitLocally()
            }
        }

        tvUnitSelection.setOnItemClickListener { _, _, _, _ -> 
            saveLimitLocally()
        }


        radioGroupTheme.setOnCheckedChangeListener { group, checkedId ->
            val mode = when (checkedId) {
                R.id.radioThemeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.radioThemeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }

            if (prefs.getInt(Constants.PREF_THEME_MODE, -100) != mode) {
                prefs.edit { putInt(Constants.PREF_THEME_MODE, mode) }
                // Post to avoid race condition during layout pass
                group.post { AppCompatDelegate.setDefaultNightMode(mode) }
            }
        }

        btnStopExit.setOnClickListener {
            // Show confirmation dialog before stopping
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.confirm_stop_exit_title))
                .setMessage(getString(R.string.confirm_stop_exit_message))
                .setPositiveButton(getString(R.string.stop_and_exit)) { _, _ ->
                    stopService(Intent(this, SpeedService::class.java))
                    finishAffinity() 
                    System.exit(0)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        btnRateUs.setOnClickListener {
             try {
                 val intent = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
                 intent.setPackage("com.android.vending")
                 startActivity(intent)
             } catch (e: ActivityNotFoundException) {
                 startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri()))
             }
        }

        btnPrivacy.setOnClickListener {
            val intent = Intent(this, TermsPrivacyActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupPermissionControls() {
        val layoutUsageAccess = binding.layoutUsageAccess
        val layoutBatteryOpt = binding.layoutBatteryOptimization
        
        // Usage Access click handler
        layoutUsageAccess.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.usage_access_error), Toast.LENGTH_SHORT).show()
            }
        }

        // Battery Optimization click handler
        layoutBatteryOpt.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.battery_optimization_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePermissionStatuses() {
        val tvUsageStatus = binding.tvUsageAccessStatus
        val tvBatteryStatus = binding.tvBatteryOptStatus
        val tvBatteryDesc = binding.tvBatteryOptDesc
        val tvUsageDesc = binding.tvUsageAccessDesc

        // Usage Access status
        val hasUsageAccess = hasUsageStatsPermission()
        tvUsageStatus.text = if (hasUsageAccess) getString(R.string.status_granted) else getString(R.string.status_not_granted)
        tvUsageStatus.setBackgroundResource(
            if (hasUsageAccess) R.drawable.bg_status_badge_success else R.drawable.bg_status_badge_error
        )
        // Dynamic description text
        tvUsageDesc.text = if (hasUsageAccess) getString(R.string.usage_access_desc) else getString(R.string.usage_access_desc_enable)

        // Battery Optimization status
        // Battery Optimization status
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnoringBatteryOpt = powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        
        tvBatteryStatus.text = if (isIgnoringBatteryOpt) getString(R.string.status_unrestricted) else getString(R.string.status_optimized)
        tvBatteryStatus.setBackgroundResource(
            if (isIgnoringBatteryOpt) R.drawable.bg_status_badge_success else R.drawable.bg_status_badge_error
        )
        
        // Dynamic description text
        tvBatteryDesc.text = if (isIgnoringBatteryOpt) getString(R.string.battery_opt_desc) else getString(R.string.battery_opt_desc_enable)
    }
    
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

}
