package com.livetvpro.data.repository

import com.livetvpro.data.api.ApiService
import com.livetvpro.data.models.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelRepository @Inject constructor(
    private val apiService: ApiService
) {
    /**
     * Gets channels for a category from the API
     * The API automatically merges Firestore + M3U channels
     */
    suspend fun getChannelsByCategory(categoryId: String): List<Channel> = withContext(Dispatchers.IO) {
        try {
            Timber.d("📡 Fetching channels for category: $categoryId")
            val response = apiService.getChannels(categoryId)
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true && body.data != null) {
                    Timber.d("✅ Successfully loaded ${body.data.size} channels (Firestore + M3U)")
                    return@withContext body.data
                }
            }
            
            Timber.e("❌ Failed to load channels: ${response.message()}")
            emptyList()
        } catch (e: Exception) {
            Timber.e(e, "❌ Error loading channels from API")
            emptyList()
        }
    }

    suspend fun getChannels(categoryId: String? = null): List<Channel> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getChannels(categoryId)
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true && body.data != null) {
                    return@withContext body.data
                }
            }
            
            emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Error loading channels")
            emptyList()
        }
    }

    suspend fun getChannelById(channelId: String): Channel? = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getChannel(channelId)
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true) {
                    return@withContext body.data
                }
            }
            
            null
        } catch (e: Exception) {
            Timber.e(e, "Error loading channel: $channelId")
            null
        }
    }
}
