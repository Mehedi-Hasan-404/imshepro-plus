package com.livetvpro.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.livetvpro.data.models.FavoriteChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "live_tv_pro_prefs",
        Context.MODE_PRIVATE
    )
    private val gson = Gson()

    companion object {
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_PIP_ACTION_MODE = "pip_action_mode"
        
        // PIP Action Modes
        const val PIP_ACTION_MODE_SKIP = 0  // Skip Forward/Backward (10s)
        const val PIP_ACTION_MODE_NEXT_PREV = 1  // Next/Previous track
    }

    // ===== Favorites Management =====
    
    fun getFavorites(): List<FavoriteChannel> {
        val json = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<FavoriteChannel>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveFavorites(favorites: List<FavoriteChannel>) {
        val json = gson.toJson(favorites)
        prefs.edit().putString(KEY_FAVORITES, json).apply()
    }
    
    // ===== PIP Settings =====
    
    /**
     * Get the current PIP action mode
     * @return PIP_ACTION_MODE_SKIP or PIP_ACTION_MODE_NEXT_PREV
     */
    fun getPipActionMode(): Int {
        return prefs.getInt(KEY_PIP_ACTION_MODE, PIP_ACTION_MODE_SKIP)
    }
    
    /**
     * Set the PIP action mode
     * @param mode PIP_ACTION_MODE_SKIP or PIP_ACTION_MODE_NEXT_PREV
     */
    fun setPipActionMode(mode: Int) {
        prefs.edit().putInt(KEY_PIP_ACTION_MODE, mode).apply()
    }
    
    /**
     * Check if using skip mode (backward/forward 10s)
     */
    fun isSkipMode(): Boolean {
        return getPipActionMode() == PIP_ACTION_MODE_SKIP
    }
    
    /**
     * Check if using next/previous mode
     */
    fun isNextPrevMode(): Boolean {
        return getPipActionMode() == PIP_ACTION_MODE_NEXT_PREV
    }
}
