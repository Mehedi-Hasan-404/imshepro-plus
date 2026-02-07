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
        
        // Floating Player Preferences
        private const val KEY_FLOATING_PLAYER_ENABLED = "floating_player_enabled"
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
        return prefs.getInt(KEY_FLOATING_PLAYER_X, 50)
    }

    fun setFloatingPlayerX(x: Int) {
        prefs.edit().putInt(KEY_FLOATING_PLAYER_X, x).apply()
    }

    fun getFloatingPlayerY(): Int {
        return prefs.getInt(KEY_FLOATING_PLAYER_Y, 100)
    }

    fun setFloatingPlayerY(y: Int) {
        prefs.edit().putInt(KEY_FLOATING_PLAYER_Y, y).apply()
    }

    // Clear all preferences
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
