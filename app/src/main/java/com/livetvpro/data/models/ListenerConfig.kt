// app/src/main/java/com/livetvpro/data/models/ListenerConfig.kt
package com.livetvpro.data.models

import com.google.gson.annotations.SerializedName

/**
 * Configuration for direct-link listener functionality
 * Fetched from the API endpoint
 */
data class ListenerConfig(
    @SerializedName("enable_direct_link")
    val enableDirectLink: Boolean = false,
    
    @SerializedName("direct_link_url")
    val directLinkUrl: String = "",
    
    @SerializedName("allowed_pages")
    val allowedPages: List<String> = emptyList()
) {
    companion object {
        // Page identifiers that match the API configuration
        const val PAGE_HOME = "home"
        const val PAGE_CATEGORIES = "categories"
        const val PAGE_CHANNELS = "channels"
        const val PAGE_LIVE_EVENTS = "live_events"
        const val PAGE_FAVORITES = "favorites"
        // Note: "player" is NOT included here - listeners never show on player pages
    }
    
    /**
     * Check if listener should be shown on a specific page
     */
    fun isEnabledForPage(pageId: String): Boolean {
        return enableDirectLink && allowedPages.contains(pageId)
    }
}
