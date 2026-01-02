// File: app/src/main/java/com/livetvpro/data/models/ListenerConfig.kt
package com.livetvpro.data.models

import com.google.gson.annotations.SerializedName

data class ListenerConfig(
    @SerializedName("enable_direct_link")
    val enableDirectLink: Boolean = false,
    
    @SerializedName("direct_link_url")
    val directLinkUrl: String = "",
    
    @SerializedName("allowed_pages")
    val allowedPages: List<String> = emptyList()
) {
    companion object {
        const val PAGE_HOME = "home"
        const val PAGE_CATEGORIES = "categories"
        const val PAGE_CHANNELS = "channels"
        const val PAGE_LIVE_EVENTS = "live_events"
        const val PAGE_FAVORITES = "favorites"
    }
    
    fun isEnabledForPage(pageId: String): Boolean {
        return enableDirectLink && allowedPages.contains(pageId)
    }
}

