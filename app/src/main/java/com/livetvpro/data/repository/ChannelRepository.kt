package com.livetvpro.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.Category
import com.livetvpro.utils.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    /**
     * Updated: Now fetches BOTH Firestore channels and M3U channels 
     * by first looking up the category's M3U URL.
     */
    suspend fun getChannelsByCategory(categoryId: String): List<Channel> = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch manual channels from Firestore
            val manualChannelsTask = firestore.collection("channels")
                .whereEqualTo("categoryId", categoryId)
                .get()

            // 2. Fetch Category details to see if it has an M3U URL
            val categoryTask = firestore.collection("categories")
                .document(categoryId)
                .get()

            val manualSnapshot = manualChannelsTask.await()
            val categorySnapshot = categoryTask.await()

            val manualChannels = manualSnapshot.documents.mapNotNull { 
                it.toObject(Channel::class.java)?.copy(id = it.id) 
            }

            val category = categorySnapshot.toObject(Category::class.java)
            val m3uUrl = category?.m3uUrl
            val categoryName = category?.name ?: "Unknown"

            // 3. If M3U URL exists, parse and merge
            if (!m3uUrl.isNullOrEmpty()) {
                val m3uChannels = getChannelsFromM3u(m3uUrl, categoryId, categoryName)
                Timber.d("Merged ${manualChannels.size} manual + ${m3uChannels.size} M3U channels for $categoryId")
                manualChannels + m3uChannels
            } else {
                manualChannels
            }
        } catch (e: Exception) {
            Timber.e(e, "Error loading combined channels for category: $categoryId")
            emptyList()
        }
    }

    suspend fun getChannelsFromM3u(
        m3uUrl: String,
        categoryId: String,
        categoryName: String
    ): List<Channel> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("Fetching channels from M3U: $m3uUrl")
                val m3uChannels = M3uParser.parseM3uFromUrl(m3uUrl)
                val channels = M3uParser.convertToChannels(m3uChannels, categoryId, categoryName)
                Timber.d("Fetched ${channels.size} channels from M3U")
                channels
            } catch (e: Exception) {
                Timber.e(e, "Error fetching channels from M3U: $m3uUrl")
                emptyList()
            }
        }
    }

    suspend fun getChannelById(channelId: String): Channel? {
        return try {
            firestore.collection("channels")
                .document(channelId)
                .get()
                .await()
                .toObject(Channel::class.java)
                ?.copy(id = channelId)
        } catch (e: Exception) {
            Timber.e(e, "Error loading channel: $channelId")
            null
        }
    }
}

