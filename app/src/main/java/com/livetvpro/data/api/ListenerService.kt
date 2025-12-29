// app/src/main/java/com/livetvpro/data/api/ListenerService.kt
package com.livetvpro.data.api

import com.livetvpro.data.models.ListenerConfig
import retrofit2.Response
import retrofit2.http.GET

/**
 * API service for fetching listener configuration (direct link ads)
 * Separate from main API because it uses /public/ path
 */
interface ListenerService {
    
    @GET("listener-config")
    suspend fun getListenerConfig(): Response<ListenerConfig>
}
