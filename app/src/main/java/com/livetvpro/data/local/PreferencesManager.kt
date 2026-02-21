package com.livetvpro.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "live_tv_pro_prefs"
        
        // Existing preferences keys (add your existing ones here)
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_LAST_UPDATE = "last_update"
        
        // Data Cache
        private const val KEY_CACHED_JSON = "cached_data_json"
        private const val KEY_CACHED_CONFIG_URL = "cached_config_url"
        private const val KEY_CACHE_TIMESTAMP = "cache_timestamp"

        // Floating Player Preferences
        private const val KEY_FLOATING_PLAYER_ENABLED = "floating_player_enabled"
        private const val KEY_FLOATING_PLAYER_AUTO_START = "floating_player_auto_start"
        private const val KEY_MAX_FLOATING_WINDOWS = "max_floating_windows"
        private const val KEY_FLOATING_PLAYER_WIDTH = "floating_player_width"
        private const val KEY_FLOATING_PLAYER_HEIGHT = "floating_player_height"
        private const val KEY_FLOATING_PLAYER_X = "floating_player_x"
        private const val KEY_FLOATING_PLAYER_Y = "floating_player_y"
    }

    // Existing preference methods (keep your existing methods here)
    fun isFirstLaunch(): Boolean {
        return prefs.getBoolean(KEY_FIRST_LAUNCH, true)
    }

    fun setFirstLaunchComplete() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
    }

    fun getLastUpdate(): Long {
        return prefs.getLong(KEY_LAST_UPDATE, 0L)
    }

    fun setLastUpdate(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_UPDATE, timestamp).apply()
    }

    // Floating Player Settings
    fun isFloatingPlayerEnabled(): Boolean {
        return prefs.getBoolean(KEY_FLOATING_PLAYER_ENABLED, false)
    }

    fun setFloatingPlayerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING_PLAYER_ENABLED, enabled).apply()
    }

    fun isFloatingPlayerAutoStart(): Boolean {
        return prefs.getBoolean(KEY_FLOATING_PLAYER_AUTO_START, false)
    }

    fun setFloatingPlayerAutoStart(autoStart: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING_PLAYER_AUTO_START, autoStart).apply()
    }

    fun getMaxFloatingWindows(): Int {
        return prefs.getInt(KEY_MAX_FLOATING_WINDOWS, 1)
    }

    fun setMaxFloatingWindows(max: Int) {
        prefs.edit().putInt(KEY_MAX_FLOATING_WINDOWS, max).apply()
    }

    fun getFloatingPlayerWidth(): Int {
        return prefs.getInt(KEY_FLOATING_PLAYER_WIDTH, 0)
    }

    fun setFloatingPlayerWidth(width: Int) {
        prefs.edit().putInt(KEY_FLOATING_PLAYER_WIDTH, width).apply()
    }

    fun getFloatingPlayerHeight(): Int {
        return prefs.getInt(KEY_FLOATING_PLAYER_HEIGHT, 0)
    }

    fun setFloatingPlayerHeight(height: Int) {
        prefs.edit().putInt(KEY_FLOATING_PLAYER_HEIGHT, height).apply()
    }

    fun getFloatingPlayerX(): Int {
        return prefs.getInt(KEY_FLOATING_PLAYER_X, Int.MIN_VALUE)
    }

    fun setFloatingPlayerX(x: Int) {
        prefs.edit().putInt(KEY_FLOATING_PLAYER_X, x).apply()
    }

    fun getFloatingPlayerY(): Int {
        return prefs.getInt(KEY_FLOATING_PLAYER_Y, Int.MIN_VALUE)
    }

    fun setFloatingPlayerY(y: Int) {
        prefs.edit().putInt(KEY_FLOATING_PLAYER_Y, y).apply()
    }

    // Data Cache
    fun getCachedJson(): String {
        return prefs.getString(KEY_CACHED_JSON, "") ?: ""
    }

    fun setCachedJson(json: String) {
        prefs.edit().putString(KEY_CACHED_JSON, json).apply()
    }

    fun getCachedConfigUrl(): String {
        return prefs.getString(KEY_CACHED_CONFIG_URL, "") ?: ""
    }

    fun setCachedConfigUrl(url: String) {
        prefs.edit().putString(KEY_CACHED_CONFIG_URL, url).apply()
    }

    fun getCacheTimestamp(): Long {
        return prefs.getLong(KEY_CACHE_TIMESTAMP, 0L)
    }

    fun setCacheTimestamp(timestamp: Long) {
        prefs.edit().putLong(KEY_CACHE_TIMESTAMP, timestamp).apply()
    }

    fun hasFreshCache(): Boolean {
        val timestamp = getCacheTimestamp()
        if (timestamp == 0L) return false
        val ageMs = System.currentTimeMillis() - timestamp
        // Cache valid for 24 hours
        return ageMs < 24 * 60 * 60 * 1000L
    }

    // Clear all preferences
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
