package com.livetvpro.data.repository

import android.content.Context
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.google.gson.Gson
import com.livetvpro.data.models.Category
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All-in-one repository: Remote Config + Data Storage
 * Everything delegated to native code for security
 */
@Singleton
class NativeDataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }
    
    // Native methods
    private external fun nativeValidateIntegrity(): Boolean
    private external fun nativeGetConfigKey(): String
    private external fun nativeStoreConfigUrl(configUrl: String)
    private external fun nativeGetConfigUrl(): String
    private external fun nativeStoreData(jsonData: String): Boolean
    private external fun nativeGetCategories(): String
    private external fun nativeGetChannels(): String
    private external fun nativeGetLiveEvents(): String
    private external fun nativeIsDataLoaded(): Boolean
    
    private val mutex = Mutex()
    private val remoteConfig = Firebase.remoteConfig
    
    init {
        // Initialize Remote Config
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = if (isDebugBuild()) 0L else 3600L
        }
        remoteConfig.setConfigSettingsAsync(configSettings)
        
        try {
            val nativeKey = nativeGetConfigKey()
            remoteConfig.setDefaultsAsync(mapOf(nativeKey to ""))
        } catch (e: Exception) {
            Timber.e(e, "Failed to set native defaults")
        }
    }

    /**
     * Fetch Remote Config and store URL in native memory
     */
    suspend fun fetchRemoteConfig(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = remoteConfig.fetchAndActivate().await()
            Timber.d("Remote Config updated: $result")
            
            // Get URL from Firebase and store in native memory
            val nativeKey = nativeGetConfigKey()
            val url = remoteConfig.getString(nativeKey)
            
            if (url.isNotEmpty()) {
                nativeStoreConfigUrl(url)
                Timber.d("✅ Config URL stored in native memory")
                return@withContext true
            }
            
            return@withContext false
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch Remote Config")
            return@withContext false
        }
    }

    /**
     * Download data from the URL stored in native memory
     */
    suspend fun refreshData(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                // Validate integrity first
                if (!nativeValidateIntegrity()) {
                    Timber.e("🚨 Integrity check failed!")
                    return@withContext false
                }
                
                // Get URL from native memory
                val remoteConfigUrl = nativeGetConfigUrl()
                
                if (remoteConfigUrl.isBlank()) {
                    Timber.e("❌ Remote Config URL is empty!")
                    return@withContext false
                }

                Timber.d("⬇️ Downloading data from: $remoteConfigUrl")

                val request = Request.Builder().url(remoteConfigUrl).build()
                
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.e("❌ HTTP Error: ${response.code}")
                        return@withContext false
                    }

                    val jsonString = response.body?.string()
                    if (jsonString.isNullOrBlank()) {
                        Timber.e("❌ Empty response body")
                        return@withContext false
                    }

                    // Store data in native memory
                    val stored = nativeStoreData(jsonString)
                    
                    if (stored) {
                        Timber.d("✅ Data loaded successfully")
                        return@withContext true
                    } else {
                        Timber.e("❌ Failed to store data in native memory")
                        return@withContext false
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Error fetching data")
                return@withContext false
            }
        }
    }

    fun getCategories(): List<Category> {
        return try {
            val json = nativeGetCategories()
            if (json == "[]") return emptyList()
            gson.fromJson(json, Array<Category>::class.java).toList()
        } catch (e: Exception) {
            Timber.e(e, "Error parsing categories")
            emptyList()
        }
    }
    
    fun getChannels(): List<Channel> {
        return try {
            val json = nativeGetChannels()
            if (json == "[]") return emptyList()
            gson.fromJson(json, Array<Channel>::class.java).toList()
        } catch (e: Exception) {
            Timber.e(e, "Error parsing channels")
            emptyList()
        }
    }
    
    fun getLiveEvents(): List<LiveEvent> {
        return try {
            val json = nativeGetLiveEvents()
            if (json == "[]") return emptyList()
            gson.fromJson(json, Array<LiveEvent>::class.java).toList()
        } catch (e: Exception) {
            Timber.e(e, "Error parsing live events")
            emptyList()
        }
    }
    
    fun isDataLoaded(): Boolean {
        return nativeIsDataLoaded()
    }
    
    private fun isDebugBuild(): Boolean {
        return context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
