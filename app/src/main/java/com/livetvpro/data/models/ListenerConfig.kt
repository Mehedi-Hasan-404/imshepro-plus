package com.livetvpro.data.models

import com.google.gson.annotations.SerializedName

/**
 * Minimal data class - actual logic is in native code
 * This is just for JSON parsing
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
        // Page identifiers (only for reference)
        const val PAGE_HOME = "home"
        const val PAGE_CATEGORIES = "categories"
        const val PAGE_CHANNELS = "channels"
        const val PAGE_LIVE_EVENTS = "live_events"
        const val PAGE_FAVORITES = "favorites"
        const val PAGE_SPORTS = "sports"
    }
}
