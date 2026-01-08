package com.livetvpro.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
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
        private const val KEY_THEME_MODE = "theme_mode" // Changed from boolean to Int
    }

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

    // New method: Get stored mode (Default to System)
    fun getThemeMode(): Int {
        return prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    // New method: Save mode
    fun setThemeMode(mode: Int) {
        prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
    }
}
