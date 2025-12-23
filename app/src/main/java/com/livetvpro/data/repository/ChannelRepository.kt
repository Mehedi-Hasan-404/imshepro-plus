package com.livetvpro.data.repository

import com.livetvpro.data.api.ApiService
import com.livetvpro.data.models.Channel
import com.livetvpro.utils.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelRepository @Inject constructor(
    private val apiService: ApiService,
    private val categoryRepository: CategoryRepository
) {
    /**
     * Gets channels for a category
     * 1. Fetch manual channels from API (Firestore/JSON)
     * 2. Get category to check for m3uUrl
     * 3. If m3uUrl exists, use YOUR M3uParser.kt to parse it!
     * 4. Merge both sources
     */
    suspend fun getChannelsByCategory(categoryId: String): List<Channel> = withContext(Dispatchers.IO) {
        try {
            Timber.d("╔═══════════════════════════════════════════════════════╗")
            Timber.d("   📡 FETCHING CHANNELS FOR CATEGORY: $categoryId")
            Timber.d("╚═══════════════════════════════════════════════════════╝")
            
            // 1. Fetch manual/Firestore channels from API
            val manualChannels = try {
                val response = apiService.getChannels(categoryId)
                if (response.isSuccessful && response.body()?.success == true) {
                    val channels = response.body()?.data ?: emptyList()
                    Timber.d("✅ Manual channels from API: ${channels.size}")
                    channels
                } else {
                    Timber.w("⚠️ No manual channels: ${response.message()}")
                    emptyList()
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Error loading manual channels")
                emptyList()
            }
            
            // 2. Get category to check for M3U URL
            val category = try {
                categoryRepository.getCategories().firstOrNull { it.id == categoryId }
            } catch (e: Exception) {
                Timber.e(e, "❌ Error loading category")
                null
            }
            
            // 3. If category has m3uUrl, use YOUR M3uParser.kt!
            val m3uChannels = if (!category?.m3uUrl.isNullOrEmpty()) {
                Timber.d("╔═══════════════════════════════════════════════════════╗")
                Timber.d("   🔗 CATEGORY HAS M3U URL!")
                Timber.d("   📋 URL: ${category?.m3uUrl}")
                Timber.d("   🚀 USING YOUR M3uParser.kt TO PARSE...")
                Timber.d("╚═══════════════════════════════════════════════════════╝")
                
                try {
                    // YOUR M3uParser.kt handles EVERYTHING:
                    // - M3U/M3U8 parsing
                    // - JSON playlists
                    // - DRM (ClearKey, Widevine, PlayReady)
                    // - HTTP headers (User-Agent, Referer, Cookie)
                    // - Inline parameters with pipe separator
                    val m3uData = M3uParser.parseM3uFromUrl(category!!.m3uUrl!!)
                    val channels = M3uParser.convertToChannels(
                        m3uData,
                        category.id,
                        category.name
                    )
                    
                    Timber.d("╔═══════════════════════════════════════════════════════╗")
                    Timber.d("   ✅ M3U PARSING COMPLETE!")
                    Timber.d("   📊 Parsed channels: ${channels.size}")
                    Timber.d("   🔐 DRM channels: ${channels.count { it.streamUrl.contains("drmScheme") }}")
                    Timber.d("   📦 With headers: ${channels.count { it.streamUrl.contains("|") }}")
                    Timber.d("╚═══════════════════════════════════════════════════════╝")
                    
                    channels
                } catch (e: Exception) {
                    Timber.e(e, "❌ Error parsing M3U from: ${category?.m3uUrl}")
                    emptyList()
                }
            } else {
                Timber.d("ℹ️ Category has no M3U URL - only manual channels")
                emptyList()
            }
            
            // 4. Merge and deduplicate by ID
            val allChannels = (manualChannels + m3uChannels).distinctBy { it.id }
            
            Timber.d("╔═══════════════════════════════════════════════════════╗")
            Timber.d("   📊 FINAL RESULT")
            Timber.d("   Total channels: ${allChannels.size}")
            Timber.d("   - Manual/API: ${manualChannels.size}")
            Timber.d("   - From M3U: ${m3uChannels.size}")
            Timber.d("╚═══════════════════════════════════════════════════════╝")
            
            return@withContext allChannels
            
        } catch (e: Exception) {
            Timber.e(e, "❌ CRITICAL ERROR in getChannelsByCategory")
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
