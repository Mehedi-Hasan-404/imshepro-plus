package com.livetvpro.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "live_tv_pro_prefs",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        const val THEME_AUTO = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }

    fun getThemeMode(): Int {
        return prefs.getInt(KEY_THEME_MODE, THEME_AUTO)
    }

    fun setThemeMode(mode: Int) {
        prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
        applyTheme(mode)
        recreateActivity()
    }

    fun applyTheme(mode: Int = getThemeMode()) {
        when (mode) {
            THEME_AUTO -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    private fun recreateActivity() {
        try {
            val activity = context as? AppCompatActivity
            activity?.recreate()
        } catch (e: Exception) {
        }
    }
}
