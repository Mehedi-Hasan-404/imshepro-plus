package com.livetvpro.data.repository

import android.content.Context
import android.widget.Toast
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
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
                Timber.d("✅ native-lib loaded successfully")
            } catch (e: Throwable) {
                isNativeLibraryLoaded = false
                Timber.e(e, "❌ Failed to load native-lib")
            }
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
    
    // Safe wrappers
    private fun safeNativeGetCategories(): String {
        return try {
            if (!isNativeLibraryLoaded) return "{}"
            nativeGetCategories()
        } catch (e: Throwable) { "{}" }
    }
    
    private fun safeNativeGetChannels(): String {
        return try {
            if (!isNativeLibraryLoaded) return "{}"
            nativeGetChannels()
        } catch (e: Throwable) { "{}" }
    }
    
    private fun safeNativeGetLiveEvents(): String {
        return try {
            if (!isNativeLibraryLoaded) return "{}"
            nativeGetLiveEvents()
        } catch (e: Throwable) { "{}" }
    }

    // ... (Keep other safeNative methods from previous fix) ...
    // Note: I'm omitting the other safeNative methods for brevity, 
    // but ensure you keep the ones from the previous fix (catching Throwable).
    
    // ----------- CRITICAL FIX BELOW -----------

    private fun safeNativeStoreConfigUrl(url: String) {
         try {
             if (isNativeLibraryLoaded) nativeStoreConfigUrl(url)
         } catch (e: Throwable) { Timber.e(e) }
    }

    private fun safeNativeGetConfigKey(): String {
        return try {
             if (isNativeLibraryLoaded) nativeGetConfigKey() else "data_file_url"
        } catch (e: Throwable) { "data_file_url" }
    }
    
    private fun safeNativeGetConfigUrl(): String {
        return try {
             if (isNativeLibraryLoaded) nativeGetConfigUrl() else ""
        } catch (e: Throwable) { "" }
    }

    private fun safeNativeStoreData(jsonData: String): Boolean {
         return try {
             if (isNativeLibraryLoaded) nativeStoreData(jsonData) else false
         } catch (e: Throwable) { false }
    }
    
    private fun safeNativeIsDataLoaded(): Boolean {
         return try {
             if (isNativeLibraryLoaded) nativeIsDataLoaded() else false
         } catch (e: Throwable) { false }
    }

    private fun safeNativeValidateIntegrity(): Boolean {
         return try {
             if (isNativeLibraryLoaded) nativeValidateIntegrity() else true
         } catch (e: Throwable) { true }
    }

    // ------------------------------------------

    private val mutex = Mutex()
    private val remoteConfig = Firebase.remoteConfig
    
    init {
        // Initialize remote config (keep your existing logic here)
        try {
             val configSettings = remoteConfigSettings { minimumFetchIntervalInSeconds = 0 }
             remoteConfig.setConfigSettingsAsync(configSettings)
             try {
                remoteConfig.setDefaultsAsync(mapOf(safeNativeGetConfigKey() to ""))
             } catch(e: Exception){}
        } catch (e: Exception) {}
    }

    suspend fun fetchRemoteConfig(): Boolean = withContext(Dispatchers.IO) {
        try {
            remoteConfig.fetchAndActivate().await()
            val nativeKey = safeNativeGetConfigKey()
            val url = remoteConfig.getString(nativeKey)
            if (url.isNotEmpty()) {
                safeNativeStoreConfigUrl(url)
                return@withContext true
            }
            return@withContext false
        } catch (e: Exception) {
            return@withContext false
        }
    }

    suspend fun refreshData(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val url = safeNativeGetConfigUrl()
                if (url.isBlank()) return@withContext false

                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    val jsonString = response.body?.string() ?: return@withContext false
                    
                    // Store the raw JSON string
                    return@withContext safeNativeStoreData(jsonString)
                }
            } catch (e: Exception) {
                return@withContext false
            }
        }
    }

    // ================== FIXED PARSING LOGIC ==================

    fun getCategories(): List<Category> {
        return try {
            val json = safeNativeGetCategories()
            if (json.isEmpty() || json == "{}" || json == "[]") return emptyList()

            // FIX: Parse as JsonObject first, then look for "categories"
            val jsonObject = gson.fromJson(json, JsonObject::class.java)
            
            if (jsonObject.has("categories")) {
                val array = jsonObject.getAsJsonArray("categories")
                gson.fromJson(array, Array<Category>::class.java).toList()
            } else {
                // Fallback: If the JSON is just the array (rare)
                gson.fromJson(json, Array<Category>::class.java).toList()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing categories")
            emptyList()
        }
    }
    
    fun getChannels(): List<Channel> {
        return try {
            val json = safeNativeGetChannels()
            if (json.isEmpty() || json == "{}" || json == "[]") return emptyList()

            val jsonObject = gson.fromJson(json, JsonObject::class.java)
            
            if (jsonObject.has("channels")) {
                val array = jsonObject.getAsJsonArray("channels")
                gson.fromJson(array, Array<Channel>::class.java).toList()
            } else {
                gson.fromJson(json, Array<Channel>::class.java).toList()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing channels")
            emptyList()
        }
    }
    
    fun getLiveEvents(): List<LiveEvent> {
        return try {
            val json = safeNativeGetLiveEvents()
            if (json.isEmpty() || json == "{}" || json == "[]") return emptyList()

            val jsonObject = gson.fromJson(json, JsonObject::class.java)
            
            // Try "live_events" or "liveEvents"
            val array = when {
                jsonObject.has("live_events") -> jsonObject.getAsJsonArray("live_events")
                jsonObject.has("liveEvents") -> jsonObject.getAsJsonArray("liveEvents")
                else -> null
            }

            if (array != null) {
                gson.fromJson(array, Array<LiveEvent>::class.java).toList()
            } else {
                // Fallback attempt
                gson.fromJson(json, Array<LiveEvent>::class.java).toList()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing live events")
            emptyList()
        }
    }
    
    fun isDataLoaded(): Boolean = safeNativeIsDataLoaded()
    
    private fun isDebugBuild(): Boolean = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
}

