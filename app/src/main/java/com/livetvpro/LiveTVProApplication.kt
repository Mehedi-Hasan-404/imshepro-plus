package com.livetvpro

import android.app.Application
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber

@HiltAndroidApp
class LiveTVProApplication : Application() {
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Native methods - all logic in C++
    private external fun nativeGetConfigKey(): String
    private external fun nativeStoreConfigUrl(configUrl: String)

    companion object {
        init {
            try {
                System.loadLibrary("native-lib")
                Timber.d("✅ Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Timber.e(e, "❌ Native library failed to load")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.d("Application Started")

        // Initialize Remote Config and store in native memory
        applicationScope.launch {
            fetchAndStoreRemoteConfig()
        }
    }
    
    /**
     * Fetch from Firebase and store directly in native memory
     * No intermediate Kotlin storage
     */
    private suspend fun fetchAndStoreRemoteConfig() {
        try {
            val remoteConfig = Firebase.remoteConfig
            
            val configSettings = remoteConfigSettings {
                minimumFetchIntervalInSeconds = if (isDebugBuild()) 0L else 3600L
            }
            remoteConfig.setConfigSettingsAsync(configSettings).await()
            
            // Get key from native code
            val nativeKey = nativeGetConfigKey()
            remoteConfig.setDefaultsAsync(mapOf(nativeKey to "")).await()
            
            // Fetch and activate
            val result = remoteConfig.fetchAndActivate().await()
            Timber.d("Remote Config updated: $result")
            
            // Get URL and store directly in native memory
            val url = remoteConfig.getString(nativeKey)
            if (url.isNotEmpty()) {
                nativeStoreConfigUrl(url)
                Timber.d("✅ Config URL stored in native memory")
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch Remote Config")
        }
    }
    
    private fun isDebugBuild(): Boolean {
        return applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
