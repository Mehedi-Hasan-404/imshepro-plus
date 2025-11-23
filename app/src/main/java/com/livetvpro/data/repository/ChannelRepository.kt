package com.livetvpro.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.livetvpro.data.models.Channel
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    suspend fun getChannelsByCategory(categoryId: String): List<Channel> {
        return try {
            firestore.collection("channels")
                .whereEqualTo("categoryId", categoryId)
                .get()
                .await()
                .documents
                .mapNotNull { it.toObject(Channel::class.java)?.copy(id = it.id) }
        } catch (e: Exception) {
            emptyList()
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
            null
        }
    }
}
