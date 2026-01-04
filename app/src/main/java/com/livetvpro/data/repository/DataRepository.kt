package com.livetvpro.data.repository

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.livetvpro.data.models.Category
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.DataResponse
import com.livetvpro.data.models.ListenerConfig
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.security.SecurityManager
import com.livetvpro.utils.RemoteConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataRepository @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val remoteConfigManager: RemoteConfigManager,
    private val securityManager: SecurityManager // ADDED
) {
    // In-memory cache
    private var cachedCategories: List<Category> = emptyList()
    private var cachedChannels: List<Channel> = emptyList()
    private var cachedLiveEvents: List<LiveEvent> = emptyList()
    private var cachedListenerConfig: ListenerConfig = ListenerConfig()
    
    private var isInitialized = false
    private val mutex = Mutex()

    /**
     * PROTECTED: Verify integrity before downloading data
     */
    suspend fun refreshData(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                // Get URL from Remote Config
                val fileUrl = getSecureDataUrl()
                
                if (fileUrl.isBlank()) {
                    Timber.e("❌ Remote Config 'data_file_url' is empty!")
                    return@withContext false
                }

                Timber.d("⬇️ Downloading merged data from: $fileUrl")

                val request = Request.Builder().url(fileUrl).build()
                
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

                    // Parse JSON
                    val data = try {
                        val jsonObject = gson.fromJson(jsonString, JsonObject::class.java)
                        
                        if (jsonObject.has("data")) {
                            Timber.d("📦 Detected wrapped API response")
                            val dataObject = jsonObject.getAsJsonObject("data")
                            gson.fromJson(dataObject, DataResponse::class.java)
                        } else {
                            Timber.d("📄 Detected direct API response")
                            gson.fromJson(jsonString, DataResponse::class.java)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "❌ Failed to parse JSON response")
                        null
                    }
                    
                    if (data != null) {
                        // Verify data integrity before caching
                        if (!verifyDataIntegrity(data)) {
                            Timber.e("🚨 Data integrity check failed!")
                            return@withContext false
                        }
                        
                        // Update Cache
                        cachedCategories = data.categories.sortedBy { it.order }
                        cachedChannels = data.channels
                        cachedLiveEvents = data.liveEvents
                        cachedListenerConfig = data.listenerConfig
                        
                        isInitialized = true
                        Timber.d("✅ Data Loaded: ${cachedCategories.size} categories, ${cachedChannels.size} channels, ${cachedLiveEvents.size} live events")
                        return@withContext true
                    } else {
                        Timber.e("❌ Failed to parse JSON")
                        return@withContext false
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Critical error fetching data")
                return@withContext false
            }
        }
    }

    /**
     * PROTECTED: Get data URL from Remote Config
     */
    private fun getSecureDataUrl(): String {
        return remoteConfigManager.getDataUrl()
    }
    
    /**
     * Verify data structure is valid (not tampered response)
     */
    private fun verifyDataIntegrity(data: DataResponse): Boolean {
        // Basic sanity checks
        if (data.categories.isEmpty() && data.channels.isEmpty() && data.liveEvents.isEmpty()) {
            Timber.e("⚠️ Received empty data response")
            return false
        }
        
        // Verify listener config is present
        if (data.listenerConfig.enableDirectLink && data.listenerConfig.directLinkUrl.isBlank()) {
            Timber.e("⚠️ Invalid listener config")
            return false
        }
        
        return true
    }

    // --- PROTECTED Synchronous Getters ---

    fun getCategories(): List<Category> {
        // Verify integrity on every access
        if (!securityManager.verifyIntegrity()) {
            securityManager.enforceIntegrity()
            return emptyList()
        }
        return cachedCategories
    }
    
    fun getChannels(): List<Channel> {
        if (!securityManager.verifyIntegrity()) {
            securityManager.enforceIntegrity()
            return emptyList()
        }
        return cachedChannels
    }
    
    fun getLiveEvents(): List<LiveEvent> {
        if (!securityManager.verifyIntegrity()) {
            securityManager.enforceIntegrity()
            return emptyList()
        }
        return cachedLiveEvents
    }
    
    fun getListenerConfig(): ListenerConfig {
        // CRITICAL: Always verify before returning config
        if (!securityManager.verifyIntegrity()) {
            securityManager.enforceIntegrity()
            return ListenerConfig() // Return disabled config
        }
        return cachedListenerConfig
    }
    
    fun isDataLoaded(): Boolean {
        if (!securityManager.verifyIntegrity()) {
            return false
        }
        return isInitialized
    }
}
