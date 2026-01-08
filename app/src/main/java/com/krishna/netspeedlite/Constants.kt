package com.krishna.netspeedlite

object Constants {
    // SharedPreferences
    const val PREFS_NAME = "settings"
    const val PREF_DAILY_LIMIT_ENABLED = "daily_limit_enabled"
    const val PREF_LAST_ALERT_DATE = "last_alert_date"
    const val PREF_ALERT_80_TRIGGERED = "alert_80_triggered"
    const val PREF_ALERT_100_TRIGGERED = "alert_100_triggered"
    const val PREF_DAILY_LIMIT_MB = "daily_limit_mb"
    const val PREF_SHOW_UP_DOWN = "show_up_down"
    const val PREF_SHOW_WIFI_SIGNAL = "show_wifi_signal"
    const val PREF_THEME_MODE = "theme_mode"
    const val PREF_SHOW_SPEED = "show_speed"
    const val PREF_SELECTED_UNIT = "selected_unit"

    const val PREF_UNIT_IN_MB = "unit_in_mb"
    const val PREF_APP_OPEN_COUNT = "app_open_count"
    const val PREF_LAST_REVIEW_PROMPT = "last_review_prompt"
    const val PREF_BATTERY_OPT_ASKED = "battery_opt_asked"
    const val PREF_BATTERY_OPT_DISMISSED = "battery_opt_dismissed"

    // Notifications
    const val SPEED_CHANNEL_ID = "speed_channel_v7"
    const val ALERT_CHANNEL_ID = "data_alert_channel"
    const val NOTIFICATION_ID = 1

    // Service Intervals
    const val UPDATE_INTERVAL_MS = 1000L // 1 second
    const val ALERT_CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

    // Icon Cache
    const val MAX_ICON_CACHE_SIZE = 15
}