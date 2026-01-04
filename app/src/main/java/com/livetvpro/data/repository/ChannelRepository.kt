package com.livetvpro.data.repository

import com.livetvpro.data.models.Channel
import com.livetvpro.utils.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelRepository @Inject constructor(
    private val dataRepository: DataRepository,
    private val categoryRepository: CategoryRepository
) {
    suspend fun getChannelsByCategory(categoryId: String): List<Channel> = withContext(Dispatchers.IO) {
        if (!dataRepository.isDataLoaded()) dataRepository.refreshData()

        val allChannels = mutableListOf<Channel>()

        // 1. Static channels from JSON
        val staticChannels = dataRepository.getChannels().filter { it.categoryId == categoryId }
        allChannels.addAll(staticChannels)

        // 2. Dynamic M3U channels
        try {
            val category = categoryRepository.getCategories().find { it.id == categoryId }
            if (category?.m3uUrl != null && category.m3uUrl.isNotEmpty()) {
                val m3uChannelsRaw = M3uParser.parseM3uFromUrl(category.m3uUrl)
                val m3uChannels = M3uParser.convertToChannels(m3uChannelsRaw, category.id, category.name)
                allChannels.addAll(m3uChannels)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing M3U")
        }

        return@withContext allChannels
    }
}

