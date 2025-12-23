package com.livetvpro.data.repository

import com.livetvpro.data.api.ApiService
import com.livetvpro.data.models.LiveEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveEventRepository @Inject constructor(
    private val apiService: ApiService
) {
    suspend fun getLiveEvents(): List<LiveEvent> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getLiveEvents()
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true && body.data != null) {
                    return@withContext body.data
                }
            }
            
            Timber.e("Failed to load live events: ${response.message()}")
            emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Error loading live events")
            emptyList()
        }
    }

    suspend fun getEventById(eventId: String): LiveEvent? = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getLiveEvent(eventId)
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true) {
                    return@withContext body.data
                }
            }
            
            null
        } catch (e: Exception) {
            Timber.e(e, "Error loading event: $eventId")
            null
        }
    }
}

