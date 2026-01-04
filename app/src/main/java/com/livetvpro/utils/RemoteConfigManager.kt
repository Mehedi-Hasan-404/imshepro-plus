package com.livetvpro.utils

import android.content.Context
import android.util.Log
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteConfigManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val remoteConfig: FirebaseRemoteConfig = Firebase.remoteConfig

    companion object {
        const val KEY_BASE_URL = "base_api_url"
        
        // IMPORTANT: Set your actual backend URL here as fallback
        private const val DEFAULT_BASE_URL = ""
        
        private const val FETCH_INTERVAL_SECONDS = 3600L // 1 hour
        private const val FETCH_INTERVAL_DEBUG = 60L // 1 minute for debug
    }

    init {
        setupRemoteConfig()
    }

    private fun setupRemoteConfig() {
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = if (isDebugBuild()) {
                FETCH_INTERVAL_DEBUG
            } else {
                FETCH_INTERVAL_SECONDS
            }
        }

        remoteConfig.setConfigSettingsAsync(configSettings)
        
        // Set default values
        val defaults = mapOf(KEY_BASE_URL to DEFAULT_BASE_URL)
        remoteConfig.setDefaultsAsync(defaults)
        
        Log.d("RemoteConfig", "Setup complete. Default URL: $DEFAULT_BASE_URL")
    }

    suspend fun fetchAndActivate(): Boolean {
        return try {
            Log.d("RemoteConfig", "🔄 Fetching Remote Config...")
            
            val fetchResult = remoteConfig.fetchAndActivate().await()
            
            if (fetchResult) {
                val url = remoteConfig.getString(KEY_BASE_URL)
                Log.d("RemoteConfig", "✅ Remote Config activated!")
                Log.d("RemoteConfig", "📡 Base URL: $url")
                true
            } else {
                val url = remoteConfig.getString(KEY_BASE_URL)
                Log.d("RemoteConfig", "ℹ️ Using cached Remote Config")
                Log.d("RemoteConfig", "📡 Base URL: $url")
                true // Still return true since we have cached values
            }
        } catch (e: Exception) {
            Log.e("RemoteConfig", "❌ Fetch failed, using defaults", e)
            Timber.e(e, "Failed to fetch Remote Config")
            true // Return true to use default values
        }
    }

    fun getBaseUrl(): String {
        val url = remoteConfig.getString(KEY_BASE_URL)
        val finalUrl = if (url.isNotEmpty()) {
            ensureTrailingSlash(url)
        } else {
            DEFAULT_BASE_URL
        }
        
        Log.d("RemoteConfig", "📍 Getting Base URL: $finalUrl")
        return finalUrl
    }

    private fun ensureTrailingSlash(url: String): String {
        return if (url.endsWith("/")) url else "$url/"
    }

    private fun isDebugBuild(): Boolean {
        return context.applicationInfo.flags and 
               android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
