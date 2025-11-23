package com.livetvpro.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.livetvpro.data.models.LiveEvent
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveEventRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    suspend fun getLiveEvents(): List<LiveEvent> {
        return try {
            firestore.collection("live_events")
                .orderBy("startTime", Query.Direction.ASCENDING)
                .get()
                .await()
                .documents
                .mapNotNull { it.toObject(LiveEvent::class.java)?.copy(id = it.id) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getEventById(eventId: String): LiveEvent? {
        return try {
            firestore.collection("live_events")
                .document(eventId)
                .get()
                .await()
                .toObject(LiveEvent::class.java)
                ?.copy(id = eventId)
        } catch (e: Exception) {
            null
        }
    }
}
