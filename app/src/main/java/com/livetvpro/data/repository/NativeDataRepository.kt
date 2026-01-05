package com.livetvpro.data.repository

import com.google.gson.Gson
import com.livetvpro.data.models.Category
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around native implementation
 * All security logic is in native code
 */
@Singleton
class NativeDataRepository @Inject constructor(
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
    private external fun nativeStoreData(jsonData: String): Boolean
    private external fun nativeGetCategories(): String
    private external fun nativeGetChannels(): String
    private external fun nativeGetLiveEvents(): String
    private external fun nativeIsDataLoaded(): Boolean
    
    private val mutex = Mutex()

    suspend fun refreshData(remoteConfigUrl: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                // Validate integrity first
                if (!nativeValidateIntegrity()) {
                    Timber.e("🚨 Integrity check failed!")
                    return@withContext false
                }
                
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
    
    fun getConfigKey(): String {
        return nativeGetConfigKey()
    }
}
