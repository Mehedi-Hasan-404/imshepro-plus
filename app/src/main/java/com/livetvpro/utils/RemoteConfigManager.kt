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
    
    // Native method to get config key (obfuscated)
    private external fun getNativeConfigKey(): String
    
    private val remoteConfig: FirebaseRemoteConfig = Firebase.remoteConfig

    companion object {
        // This constant is now just a fallback - real key comes from native
        private const val FALLBACK_KEY = "data_file_url"
        private const val DEFAULT_URL = ""
    }

    init {
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = if (isDebugBuild()) 0L else 3600L
        }
        remoteConfig.setConfigSettingsAsync(configSettings)
        
        // Get the actual key from native code for defaults
        try {
            val nativeKey = getNativeConfigKey()
            remoteConfig.setDefaultsAsync(mapOf(nativeKey to DEFAULT_URL))
        } catch (e: Exception) {
            Timber.e(e, "Failed to set native defaults, using fallback")
            remoteConfig.setDefaultsAsync(mapOf(FALLBACK_KEY to DEFAULT_URL))
        }
    }

    suspend fun fetchAndActivate(): Boolean {
        return try {
            val result = remoteConfig.fetchAndActivate().await()
            Timber.d("Remote Config updated: $result")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch Remote Config")
            false
        }
    }

    /**
     * PROTECTED: Get data URL using native config key
     */
    fun getDataUrl(): String {
        return try {
            // Get the actual key from native code
            val nativeKey = getNativeConfigKey()
            
            // Try native key first
            val url = remoteConfig.getString(nativeKey)
            
            // Fallback to hardcoded key if native fails
            if (url.isBlank()) {
                Timber.w("⚠️ Native key returned empty, trying fallback")
                remoteConfig.getString(FALLBACK_KEY)
            } else {
                url
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get data URL from native key")
            // Last resort: use fallback key
            try {
                remoteConfig.getString(FALLBACK_KEY)
            } catch (e2: Exception) {
                Timber.e(e2, "Fallback also failed")
                ""
            }
        }
    }

    private fun isDebugBuild(): Boolean {
        return context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
