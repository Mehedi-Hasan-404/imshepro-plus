package com.livetvpro.data.repository

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.livetvpro.data.models.Category
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.DataResponse
import com.livetvpro.data.models.ListenerConfig
import com.livetvpro.data.models.LiveEvent
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
    private val remoteConfigManager: RemoteConfigManager
    // REMOVED: securityManager parameter
) {
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
                val fileUrl = remoteConfigManager.getDataUrl()
                
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

    fun getCategories(): List<Category> = cachedCategories
    fun getChannels(): List<Channel> = cachedChannels
    fun getLiveEvents(): List<LiveEvent> = cachedLiveEvents
    fun getListenerConfig(): ListenerConfig = cachedListenerConfig
    fun isDataLoaded(): Boolean = isInitialized
}
