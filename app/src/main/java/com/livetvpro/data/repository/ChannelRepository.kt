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
     * Fetches both manual Firestore channels and M3U channels.
     * It looks up the category document first to find the m3uUrl.
     */
    suspend fun getChannelsByCategory(categoryId: String): List<Channel> = withContext(Dispatchers.IO) {
        try {
            Timber.d("Starting load for category: $categoryId")

            // 1. Fetch manual channels from Firestore
            val manualTask = firestore.collection("channels")
                .whereEqualTo("categoryId", categoryId)
                .get()

            // 2. Fetch Category details for M3U URL discovery
            val categoryTask = firestore.collection("categories")
                .document(categoryId)
                .get()

            val manualSnapshot = manualTask.await()
            val categorySnapshot = categoryTask.await()

            val manualChannels = manualSnapshot.documents.mapNotNull { 
                it.toObject(Channel::class.java)?.copy(id = it.id) 
            }

            val category = categorySnapshot.toObject(Category::class.java)
            val m3uUrl = category?.m3uUrl
            val categoryName = category?.name ?: "Unknown"

            // 3. Merge M3U channels if URL is present
            if (!m3uUrl.isNullOrEmpty()) {
                Timber.d("M3U URL found: $m3uUrl. Parsing...")
                val m3uChannels = getChannelsFromM3u(m3uUrl, categoryId, categoryName)
                manualChannels + m3uChannels
            } else {
                Timber.d("No M3U URL found for category $categoryId")
                manualChannels
            }
        } catch (e: Exception) {
            Timber.e(e, "Error loading channels for category: $categoryId")
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
                val m3uChannels = M3uParser.parseM3uFromUrl(m3uUrl)
                M3uParser.convertToChannels(m3uChannels, categoryId, categoryName)
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

