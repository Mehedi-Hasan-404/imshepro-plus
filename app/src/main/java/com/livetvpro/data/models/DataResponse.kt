package com.livetvpro.data.models

import com.google.gson.annotations.SerializedName

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
