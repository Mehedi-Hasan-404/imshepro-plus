package com.livetvpro.data.repository

import android.content.Context
import android.widget.Toast
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.google.gson.Gson
import com.livetvpro.data.models.Category
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.data.models.EventCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeDataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private var isNativeLibLoaded = false
        init {
            try {
                System.loadLibrary("native-lib")
                isNativeLibLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                isNativeLibLoaded = false
            } catch (e: Exception) {
                isNativeLibLoaded = false
            }
        }
    }

    private external fun isEnabled(): Boolean
    private external fun getConfigKey(): String
    private external fun saveConfigUrl(url: String)
    private external fun getDataUrl(): String
    private external fun saveData(jsonData: String): Boolean
    private external fun getCategoriesJson(): String
    private external fun getChannelsJson(): String
    private external fun getLiveEventsJson(): String
    private external fun isDataAvailable(): Boolean
    private external fun getEventCategoriesJson(): String
    private external fun getSportsJson(): String

    private fun checkEnabled(): Boolean {
        return try {
            if (!isNativeLibLoaded) return true
            isEnabled()
        } catch (error: Throwable) {
            true
        }
    }

    private fun getRemoteConfigKey(): String {
        return try {
            if (!isNativeLibLoaded) return "data_file_url"
            getConfigKey()
        } catch (error: Throwable) {
            "data_file_url"
        }
    }

    private fun storeConfigUrl(url: String) {
        try {
            if (!isNativeLibLoaded) return
            saveConfigUrl(url)
        } catch (error: Throwable) {
        }
    }

    private fun retrieveDataUrl(): String {
        return try {
            if (!isNativeLibLoaded) return ""
            getDataUrl()
        } catch (error: Throwable) {
            ""
        }
    }

    private fun storeJsonData(jsonData: String): Boolean {
        return try {
            if (!isNativeLibLoaded) return false
            saveData(jsonData)
        } catch (error: Throwable) {
            false
        }
    }

    private fun retrieveCategoriesJson(): String {
        return try {
            if (!isNativeLibLoaded) return "[]"
            getCategoriesJson()
        } catch (error: Throwable) {
            "[]"
        }
    }

    private fun retrieveChannelsJson(): String {
        return try {
            if (!isNativeLibLoaded) return "[]"
            getChannelsJson()
        } catch (error: Throwable) {
            "[]"
        }
    }

    private fun retrieveLiveEventsJson(): String {
        return try {
            if (!isNativeLibLoaded) return "[]"
            getLiveEventsJson()
        } catch (error: Throwable) {
            "[]"
        }
    }

    private fun checkDataAvailable(): Boolean {
        return try {
            if (!isNativeLibLoaded) return false
            isDataAvailable()
        } catch (error: Throwable) {
            false
        }
    }

    private fun retrieveEventCategoriesJson(): String {
        return try {
            if (!isNativeLibLoaded) return "[]"
            getEventCategoriesJson()
        } catch (error: Throwable) {
            "[]"
        }
    }

    private fun retrieveSportsJson(): String {
        return try {
            if (!isNativeLibLoaded) return "[]"
            getSportsJson()
        } catch (error: Throwable) {
            "[]"
        }
    }

    private val refreshMutex = Mutex()
    private val remoteConfig = Firebase.remoteConfig

    init {
        try {
            val configSettings = remoteConfigSettings {
                minimumFetchIntervalInSeconds = if (isDebugBuild()) 0L else 3600L
            }
            remoteConfig.setConfigSettingsAsync(configSettings)
            
            try {
                val configKey = getRemoteConfigKey()
                remoteConfig.setDefaultsAsync(mapOf(configKey to ""))
            } catch (error: Exception) {
            }
        } catch (error: Exception) {
        }
    }

    suspend fun fetchRemoteConfig(): Boolean = withContext(Dispatchers.IO) {
        try {
            val activated = remoteConfig.fetchAndActivate().await()
            val configKey = getRemoteConfigKey()
            val configUrl = remoteConfig.getString(configKey)
            
            if (configUrl.isNotEmpty()) {
                storeConfigUrl(configUrl)
                return@withContext true
            } else {
                return@withContext false
            }
        } catch (error: Exception) {
            return@withContext false
        }
    }

    suspend fun refreshData(): Boolean = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            try {
                if (!checkEnabled()) {
                    return@withContext false
                }
                
                val dataUrl = retrieveDataUrl()
                if (dataUrl.isBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Configuration URL not found", Toast.LENGTH_LONG).show()
                    }
                    return@withContext false
                }
                
                val request = Request.Builder().url(dataUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Server error: ${response.code}", Toast.LENGTH_LONG).show()
                        }
                        return@withContext false
                    }
                    
                    val responseBody = response.body?.string()
                    if (responseBody.isNullOrBlank()) {
                        return@withContext false
                    }
                    
                    val success = storeJsonData(responseBody)
                    if (success) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Data loaded successfully", Toast.LENGTH_SHORT).show()
                        }
                        return@withContext true
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Failed to process data", Toast.LENGTH_LONG).show()
                        }
                        return@withContext false
                    }
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Network error: ${error.message}", Toast.LENGTH_LONG).show()
                }
                return@withContext false
            }
        }
    }

    fun getCategories(): List<Category> {
        return try {
            val json = retrieveCategoriesJson()
            if (json.isEmpty() || json == "[]") return emptyList()
            gson.fromJson(json, Array<Category>::class.java).toList()
        } catch (error: Exception) {
            emptyList()
        }
    }

    fun getChannels(): List<Channel> {
        return try {
            val json = retrieveChannelsJson()
            if (json.isEmpty() || json == "[]") return emptyList()
            gson.fromJson(json, Array<Channel>::class.java).toList()
        } catch (error: Exception) {
            emptyList()
        }
    }

    fun getLiveEvents(): List<LiveEvent> {
        return try {
            val json = retrieveLiveEventsJson()
            if (json.isEmpty() || json == "[]") return emptyList()
            gson.fromJson(json, Array<LiveEvent>::class.java).toList()
        } catch (error: Exception) {
            emptyList()
        }
    }

    fun getEventCategories(): List<EventCategory> {
        return try {
            val json = retrieveEventCategoriesJson()
            if (json.isEmpty() || json == "[]") return emptyList()
            gson.fromJson(json, Array<EventCategory>::class.java).toList()
        } catch (error: Exception) {
            emptyList()
        }
    }

    fun getSports(): List<Channel> {
        return try {
            val json = retrieveSportsJson()
            if (json.isEmpty() || json == "[]") return emptyList()
            gson.fromJson(json, Array<Channel>::class.java).toList()
        } catch (error: Exception) {
            emptyList()
        }
    }

    fun isDataLoaded(): Boolean {
        return checkDataAvailable()
    }

    private fun isDebugBuild(): Boolean {
        return context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
