package com.livetvpro.data.api

import com.livetvpro.data.models.Category
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.ListenerConfig
import com.livetvpro.data.models.LiveEvent
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null
)

interface ApiService {
    
    @GET("categories")
    suspend fun getCategories(): Response<ApiResponse<List<Category>>>
    
    @GET("categories/{id}")
    suspend fun getCategory(@Path("id") id: String): Response<ApiResponse<Category>>
    
    @GET("channels")
    suspend fun getChannels(
        @Query("categoryId") categoryId: String? = null
    ): Response<ApiResponse<List<Channel>>>
    
    @GET("channels/{id}")
    suspend fun getChannel(@Path("id") id: String): Response<ApiResponse<Channel>>
    
    @GET("live-events")
    suspend fun getLiveEvents(): Response<ApiResponse<List<LiveEvent>>>
    
    @GET("live-events/{id}")
    suspend fun getLiveEvent(@Path("id") id: String): Response<ApiResponse<LiveEvent>>

    /**
     * Listener Configuration (Merged from ListenerService)
     * Same base URL as other endpoints
     */
    @GET("listener-config")
    suspend fun getListenerConfig(): Response<ListenerConfig>
}

