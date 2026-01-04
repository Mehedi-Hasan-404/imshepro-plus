// app/src/main/java/com/livetvpro/utils/RemoteConfigManager.kt
package com.livetvpro.utils

import android.content.Context
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
        // Only ONE parameter - your base API URL
        const val KEY_BASE_URL = "base_api_url"
        
        // Default fallback if Firebase fails
        private const val DEFAULT_BASE_URL = "https://livetvpro.vercel.com/"
        
        // Cache duration
        private const val FETCH_INTERVAL_SECONDS = 3600L // 1 hour
        private const val FETCH_INTERVAL_DEBUG = 0L // Instant for debug
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
        
        // Set default value
        remoteConfig.setDefaultsAsync(
            mapOf(KEY_BASE_URL to DEFAULT_BASE_URL)
        )
    }

    /**
     * Fetch and activate remote config
     */
    suspend fun fetchAndActivate(): Boolean {
        return try {
            Timber.d("Fetching Remote Config...")
            val result = remoteConfig.fetchAndActivate().await()
            
            if (result) {
                Timber.d("Remote Config activated: ${getBaseUrl()}")
            }
            
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch Remote Config")
            false
        }
    }

    /**
     * Get base API URL from Firebase
     */
    fun getBaseUrl(): String {
        val url = remoteConfig.getString(KEY_BASE_URL)
        return if (url.isNotEmpty()) {
            ensureTrailingSlash(url)
        } else {
            DEFAULT_BASE_URL
        }
    }

    private fun ensureTrailingSlash(url: String): String {
        return if (url.endsWith("/")) url else "$url/"
    }

    private fun isDebugBuild(): Boolean {
        return context.applicationInfo.flags and 
               android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
