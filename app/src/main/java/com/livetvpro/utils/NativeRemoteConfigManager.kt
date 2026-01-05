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

/**
 * Ultra-thin wrapper - only fetches from Firebase
 * All storage and logic in native code
 */
@Singleton
class NativeRemoteConfigManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }
    
    // Native methods - all logic in C++
    private external fun nativeGetConfigKey(): String
    private external fun nativeStoreConfigUrl(configUrl: String)
    private external fun nativeGetConfigUrl(): String
    private external fun nativeIsConfigReady(): Boolean
    
    private val remoteConfig: FirebaseRemoteConfig = Firebase.remoteConfig

    init {
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = if (isDebugBuild()) 0L else 3600L
        }
        remoteConfig.setConfigSettingsAsync(configSettings)
        
        // Get the key from native code for defaults
        try {
            val nativeKey = nativeGetConfigKey()
            remoteConfig.setDefaultsAsync(mapOf(nativeKey to ""))
        } catch (e: Exception) {
            Timber.e(e, "Failed to set native defaults")
        }
    }

    /**
     * Fetch from Firebase and store in native memory
     */
    suspend fun fetchAndActivate(): Boolean {
        return try {
            val result = remoteConfig.fetchAndActivate().await()
            Timber.d("Remote Config updated: $result")
            
            // Get URL from Firebase and store in native memory
            val nativeKey = nativeGetConfigKey()
            val url = remoteConfig.getString(nativeKey)
            
            if (url.isNotEmpty()) {
                nativeStoreConfigUrl(url)
                Timber.d("✅ Config URL stored in native memory")
            }
            
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch Remote Config")
            false
        }
    }

    /**
     * Get data URL from native memory
     */
    fun getDataUrl(): String {
        return nativeGetConfigUrl()
    }
    
    /**
     * Check if config is ready (from native memory)
     */
    fun isConfigReady(): Boolean {
        return nativeIsConfigReady()
    }

    private fun isDebugBuild(): Boolean {
        return context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
