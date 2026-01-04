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
    private val securityManager: SecurityManager
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
            // CRITICAL: Verify app integrity before proceeding
            if (!securityManager.verifyIntegrity()) {
                Timber.e("🚨 Integrity check failed - blocking data refresh")
                securityManager.enforceIntegrity()
                return@withContext false
            }
            
            try {
                // Get URL from Remote Config (which uses native code)
                val fileUrl = getSecureDataUrl()
                
                if (fileUrl.isBlank()) {
                    Timber.e("❌ Remote Config URL is empty!")
                    return@withContext false
                }

                Timber.d("⬇️ Downloading data from: $fileUrl")

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

                    // Parse JSON with native key extraction
                    val data = parseDataResponse(jsonString)
                    
                    if (data != null) {
                        // Verify data integrity
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
                        Timber.d("✅ Data Loaded: ${cachedCategories.size} categories")
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
     * Parse JSON using native-protected field keys
     */
    private fun parseDataResponse(jsonString: String): DataResponse? {
        return try {
            val jsonObject = gson.fromJson(jsonString, JsonObject::class.java)
            
            // Handle wrapped response
            val dataObject = if (jsonObject.has("data")) {
                Timber.d("📦 Detected wrapped API response")
                jsonObject.getAsJsonObject("data")
            } else {
                Timber.d("📄 Detected direct API response")
                jsonObject
            }
            
            // Parse main sections
            val categories = if (dataObject.has("categories")) {
                gson.fromJson(
                    dataObject.getAsJsonArray("categories"),
                    Array<Category>::class.java
                ).toList()
            } else emptyList()
            
            val channels = if (dataObject.has("channels")) {
                gson.fromJson(
                    dataObject.getAsJsonArray("channels"),
                    Array<Channel>::class.java
                ).toList()
            } else emptyList()
            
            val liveEvents = if (dataObject.has("live_events")) {
                gson.fromJson(
                    dataObject.getAsJsonArray("live_events"),
                    Array<LiveEvent>::class.java
                ).toList()
            } else emptyList()
            
            // CRITICAL: Parse listener config using NATIVE field keys
            val listenerConfig = parseListenerConfig(dataObject)
            
            DataResponse(
                categories = categories,
                channels = channels,
                liveEvents = liveEvents,
                listenerConfig = listenerConfig
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ JSON parsing failed")
            null
        }
    }
    
    /**
     * Parse listener config using standard field names
     */
    private fun parseListenerConfig(dataObject: JsonObject): ListenerConfig {
        try {
            // Standard field names (not using native code for now)
            val listenerConfigKey = "listener_config"
            val enableLinkKey = "enable_direct_link"
            val linkUrlKey = "direct_link_url"
            val allowedPagesKey = "allowed_pages"
            
            // Check if listener_config exists
            if (!dataObject.has(listenerConfigKey)) {
                Timber.w("⚠️ No listener config in response")
                return ListenerConfig()
            }
            
            val configObject = dataObject.getAsJsonObject(listenerConfigKey)
            
            // Extract fields
            val enableDirectLink = if (configObject.has(enableLinkKey)) {
                configObject.get(enableLinkKey).asBoolean
            } else false
            
            val directLinkUrl = if (configObject.has(linkUrlKey)) {
                configObject.get(linkUrlKey).asString
            } else ""
            
            val allowedPages = if (configObject.has(allowedPagesKey)) {
                val array = configObject.getAsJsonArray(allowedPagesKey)
                mutableListOf<String>().apply {
                    array.forEach { add(it.asString) }
                }
            } else emptyList()
            
            return ListenerConfig(
                enableDirectLink = enableDirectLink,
                directLinkUrl = directLinkUrl,
                allowedPages = allowedPages
            )
            
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to parse listener config")
            return ListenerConfig()
        }
    }

    /**
     * Get data URL securely
     */
    private fun getSecureDataUrl(): String {
        if (!securityManager.verifyIntegrity()) {
            securityManager.enforceIntegrity()
            return ""
        }
        return remoteConfigManager.getDataUrl()
    }
    
    /**
     * Verify data structure integrity
     */
    private fun verifyDataIntegrity(data: DataResponse): Boolean {
        if (data.categories.isEmpty() && data.channels.isEmpty() && data.liveEvents.isEmpty()) {
            Timber.e("⚠️ Received empty data response")
            return false
        }
        
        if (data.listenerConfig.enableDirectLink && data.listenerConfig.directLinkUrl.isBlank()) {
            Timber.e("⚠️ Invalid listener config")
            return false
        }
        
        return true
    }

    // --- PROTECTED Getters ---

    fun getCategories(): List<Category> {
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
            return ListenerConfig()
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
