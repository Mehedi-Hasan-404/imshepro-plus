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
        // Ensure this key exists in your Firebase Console
        const val KEY_DATA_URL = "data_file_url" 
        private const val DEFAULT_URL = ""
    }

    init {
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = if (isDebugBuild()) 0L else 3600L
        }
        remoteConfig.setConfigSettingsAsync(configSettings)
        remoteConfig.setDefaultsAsync(mapOf(KEY_DATA_URL to DEFAULT_URL))
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

    fun getDataUrl(): String {
        return remoteConfig.getString(KEY_DATA_URL)
    }

    private fun isDebugBuild(): Boolean {
        return context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}

