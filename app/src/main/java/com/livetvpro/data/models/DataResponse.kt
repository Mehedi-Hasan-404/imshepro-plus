package com.livetvpro.data.models

import com.google.gson.annotations.SerializedName

/**
 * Minimal data class - field names come from native code
 * This is just for JSON parsing
 */
data class DataResponse(
    @SerializedName("categories")
    val categories: List<Category> = emptyList(),
    
    @SerializedName("channels")
    val channels: List<Channel> = emptyList(),
    
    @SerializedName("live_events")
    val liveEvents: List<LiveEvent> = emptyList(),
    
    @SerializedName("listener_config")
    val listenerConfig: ListenerConfig = ListenerConfig()
)
