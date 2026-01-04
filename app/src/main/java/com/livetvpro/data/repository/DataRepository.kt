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
    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }
    
    // Native methods for encrypted field names
    private external fun nativeGetCategoriesKey(): String
    private external fun nativeGetChannelsKey(): String
    private external fun nativeGetLiveEventsKey(): String
    private external fun nativeGetDataKey(): String
    private external fun nativeGetListenerConfigKey(): String
    private external fun nativeGetEnableLinkKey(): String
    private external fun nativeGetLinkUrlKey(): String
    private external fun nativeGetAllowedPagesKey(): String
    
    // Initialize listener config in native memory
    private external fun nativeInitListenerConfig(
        enableDirectLink: Boolean,
        directLinkUrl: String?,
        allowedPages: Array<String>?
    )
    
    // In-memory cache
    private var cachedCategories: List<Category> = emptyList()
    private var cachedChannels: List<Channel> = emptyList()
    private var cachedLiveEvents: List<LiveEvent> = emptyList()
    private var cachedListenerConfig: ListenerConfig = ListenerConfig()
    
    private var isInitialized = false
    private val mutex = Mutex()

    suspend fun refreshData(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!securityManager.verifyIntegrity()) {
                    Timber.e("🚨 Integrity check failed!")
                    return@withContext false
                }
                
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

                    val data = try {
                        val jsonObject = gson.fromJson(jsonString, JsonObject::class.java)
                        
                        // Get "data" key from native code
                        val dataKey = nativeGetDataKey()
                        
                        if (jsonObject.has(dataKey)) {
                            Timber.d("📦 Detected wrapped API response")
                            val dataObject = jsonObject.getAsJsonObject(dataKey)
                            parseDataResponse(dataObject)
                        } else {
                            Timber.d("📄 Detected direct API response")
                            parseDataResponse(jsonObject)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "❌ Failed to parse JSON response")
                        null
                    }
                    
                    if (data != null) {
                        if (!verifyDataIntegrity(data)) {
                            Timber.e("🚨 Data integrity check failed!")
                            return@withContext false
                        }
                        
                        cachedCategories = data.categories.sortedBy { it.order }
                        cachedChannels = data.channels
                        cachedLiveEvents = data.liveEvents
                        cachedListenerConfig = data.listenerConfig
                        
                        // Initialize listener config in native memory
                        initializeNativeListenerConfig(data.listenerConfig)
                        
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
     * Parse JSON response using native encrypted field names
     */
    private fun parseDataResponse(jsonObject: JsonObject): DataResponse? {
        return try {
            // Get field names from native code
            val categoriesKey = nativeGetCategoriesKey()
            val channelsKey = nativeGetChannelsKey()
            val liveEventsKey = nativeGetLiveEventsKey()
            
            val categories = if (jsonObject.has(categoriesKey)) {
                gson.fromJson(jsonObject.getAsJsonArray(categoriesKey), 
                    Array<Category>::class.java).toList()
            } else emptyList()
            
            val channels = if (jsonObject.has(channelsKey)) {
                gson.fromJson(jsonObject.getAsJsonArray(channelsKey), 
                    Array<Channel>::class.java).toList()
            } else emptyList()
            
            val liveEvents = if (jsonObject.has(liveEventsKey)) {
                gson.fromJson(jsonObject.getAsJsonArray(liveEventsKey), 
                    Array<LiveEvent>::class.java).toList()
            } else emptyList()
            
            // Parse listener_config using native encrypted key
            val listenerConfig = parseListenerConfig(jsonObject)
            
            DataResponse(
                categories = categories,
                channels = channels,
                liveEvents = liveEvents,
                listenerConfig = listenerConfig
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse data response")
            null
        }
    }
    
    /**
     * Parse ListenerConfig using encrypted field names from native code
     */
    private fun parseListenerConfig(jsonObject: JsonObject): ListenerConfig {
        return try {
            val listenerConfigKey = nativeGetListenerConfigKey()
            
            if (!jsonObject.has(listenerConfigKey)) {
                return ListenerConfig()
            }
            
            val configObject = jsonObject.getAsJsonObject(listenerConfigKey)
            
            // Get encrypted field names from native
            val enableKey = nativeGetEnableLinkKey()
            val linkUrlKey = nativeGetLinkUrlKey()
            val allowedPagesKey = nativeGetAllowedPagesKey()
            
            val enableDirectLink = if (configObject.has(enableKey)) {
                configObject.get(enableKey).asBoolean
            } else false
            
            val directLinkUrl = if (configObject.has(linkUrlKey)) {
                configObject.get(linkUrlKey).asString
            } else ""
            
            val allowedPages = if (configObject.has(allowedPagesKey)) {
                gson.fromJson(configObject.getAsJsonArray(allowedPagesKey), 
                    Array<String>::class.java).toList()
            } else emptyList()
            
            ListenerConfig(
                enableDirectLink = enableDirectLink,
                directLinkUrl = directLinkUrl,
                allowedPages = allowedPages
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse listener config")
            ListenerConfig()
        }
    }
    
    /**
     * Initialize listener config in native memory (completely hidden)
     */
    private fun initializeNativeListenerConfig(config: ListenerConfig) {
        try {
            nativeInitListenerConfig(
                config.enableDirectLink,
                config.directLinkUrl,
                config.allowedPages.toTypedArray()
            )
            Timber.d("✅ Listener config initialized in native memory")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize native listener config")
        }
    }

    private fun getSecureDataUrl(): String {
        return remoteConfigManager.getDataUrl()
    }
    
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

    // --- PROTECTED Synchronous Getters ---

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
