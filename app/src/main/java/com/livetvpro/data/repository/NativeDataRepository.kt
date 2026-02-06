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
    private val client: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private var isNativeLibraryLoaded = false

        init {
            try {
                System.loadLibrary("native-lib")
                isNativeLibraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                isNativeLibraryLoaded = false
            } catch (e: Exception) {
                isNativeLibraryLoaded = false
            }
        }
    }

    private external fun nativeValidateIntegrity(): Boolean
    private external fun nativeGetConfigKey(): String
    private external fun nativeStoreConfigUrl(configUrl: String)
    private external fun nativeGetConfigUrl(): String
    private external fun nativeStoreData(jsonData: String): Boolean
    private external fun nativeGetCategories(): String
    private external fun nativeGetChannels(): String
    private external fun nativeGetLiveEvents(): String
    private external fun nativeIsDataLoaded(): Boolean
    private external fun nativeGetEventCategories(): String
    private external fun nativeGetSports(): String

    private fun safeNativeValidateIntegrity(): Boolean {
        return try {
            if (!isNativeLibraryLoaded) return true
            nativeValidateIntegrity()
        } catch (e: Throwable) {
            true
        }
    }

    private fun safeNativeGetConfigKey(): String {
        return try {
            if (!isNativeLibraryLoaded) return "data_file_url"
            nativeGetConfigKey()
        } catch (e: Throwable) {
            "data_file_url"
        }
    }

    private fun safeNativeStoreConfigUrl(url: String) {
        try {
            if (!isNativeLibraryLoaded) return
            nativeStoreConfigUrl(url)
        } catch (e: Throwable) {
        }
    }

    private fun safeNativeGetConfigUrl(): String {
        return try {
            if (!isNativeLibraryLoaded) return ""
            nativeGetConfigUrl()
        } catch (e: Throwable) {
            ""
        }
    }

    private fun safeNativeStoreData(jsonData: String): Boolean {
        return try {
            if (!isNativeLibraryLoaded) return false
            nativeStoreData(jsonData)
        } catch (e: Throwable) {
            false
        }
    }

    private fun safeNativeGetCategories(): String {
        return try {
            if (!isNativeLibraryLoaded) return "[]"
            nativeGetCategories()
        } catch (e: Throwable) {
            "[]"
        }
    }

    private fun safeNativeGetChannels(): String {
        return try {
            if (!isNativeLibraryLoaded) return "[]"
            nativeGetChannels()
        } catch (e: Throwable) {
            "[]"
        }
    }

    private fun safeNativeGetLiveEvents(): String {
        return try {
            if (!isNativeLibraryLoaded) return "[]"
            nativeGetLiveEvents()
        } catch (e: Throwable) {
            "[]"
        }
    }

    private fun safeNativeIsDataLoaded(): Boolean {
        return try {
            if (!isNativeLibraryLoaded) return false
            nativeIsDataLoaded()
        } catch (e: Throwable) {
            false
        }
    }

    private fun safeNativeGetEventCategories(): String {
        return try {
            if (!isNativeLibraryLoaded) return "[]"
            nativeGetEventCategories()
        } catch (e: Throwable) {
            "[]"
        }
    }

    private fun safeNativeGetSports(): String {
        return try {
            if (!isNativeLibraryLoaded) return "[]"
            nativeGetSports()
        } catch (e: Throwable) {
            "[]"
        }
    }

    private val mutex = Mutex()
    private val remoteConfig = Firebase.remoteConfig

    init {
        try {
            val configSettings = remoteConfigSettings {
                minimumFetchIntervalInSeconds = if (isDebugBuild()) 0L else 3600L
            }
            remoteConfig.setConfigSettingsAsync(configSettings)
            
            try {
                val nativeKey = safeNativeGetConfigKey()
                remoteConfig.setDefaultsAsync(mapOf(nativeKey to ""))
            } catch (e: Exception) {
            }
        } catch (e: Exception) {
        }
    }

    suspend fun fetchRemoteConfig(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = remoteConfig.fetchAndActivate().await()
            val nativeKey = safeNativeGetConfigKey()
            val url = remoteConfig.getString(nativeKey)
            
            if (url.isNotEmpty()) {
                safeNativeStoreConfigUrl(url)
                return@withContext true
            } else {
                return@withContext false
            }
        } catch (e: Exception) {
            return@withContext false
        }
    }

    suspend fun refreshData(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!safeNativeValidateIntegrity()) {
                    return@withContext false
                }
                
                val remoteConfigUrl = safeNativeGetConfigUrl()
                if (remoteConfigUrl.isBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Configuration URL not found", Toast.LENGTH_LONG).show()
                    }
                    return@withContext false
                }
                
                val request = Request.Builder().url(remoteConfigUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Server error: ${response.code}", Toast.LENGTH_LONG).show()
                        }
                        return@withContext false
                    }
                    
                    val jsonString = response.body?.string()
                    if (jsonString.isNullOrBlank()) {
                        return@withContext false
                    }
                    
                    val stored = safeNativeStoreData(jsonString)
                    if (stored) {
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
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@withContext false
            }
        }
    }

    fun getCategories(): List<Category> {
        return try {
            val json = safeNativeGetCategories()
            if (json.isEmpty() || json == "[]") return emptyList()
            gson.fromJson(json, Array<Category>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getChannels(): List<Channel> {
        return try {
            val json = safeNativeGetChannels()
            if (json.isEmpty() || json == "[]") return emptyList()
            gson.fromJson(json, Array<Channel>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getLiveEvents(): List<LiveEvent> {
        return try {
            val json = safeNativeGetLiveEvents()
            if (json.isEmpty() || json == "[]") return emptyList()
            gson.fromJson(json, Array<LiveEvent>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getEventCategories(): List<EventCategory> {
        return try {
            val json = safeNativeGetEventCategories()
            if (json.isEmpty() || json == "[]") return emptyList()
            gson.fromJson(json, Array<EventCategory>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getSports(): List<Channel> {
        return try {
            val json = safeNativeGetSports()
            if (json.isEmpty() || json == "[]") return emptyList()
            // Sports have the same structure as channels
            gson.fromJson(json, Array<Channel>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isDataLoaded(): Boolean {
        return safeNativeIsDataLoaded()
    }

    private fun isDebugBuild(): Boolean {
        return context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
