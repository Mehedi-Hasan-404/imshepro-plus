package com.livetvpro.data.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class Category(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
    val iconUrl: String? = null,
    val m3uUrl: String? = null,
    val order: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = ""
) : Parcelable

@Parcelize
data class Channel(
    val id: String = "",
    val name: String = "",
    val logoUrl: String = "",
    val streamUrl: String = "",
    val categoryId: String = "",
    val categoryName: String = "",
    
    // Group title from M3U (for sub-categorization)
    val groupTitle: String = "",
    
    // Support for multiple links in channels
    val links: List<ChannelLink>? = null,
    
    // Event-specific fields (for when Channel is used to represent events)
    val team1Logo: String = "",
    val team2Logo: String = "",
    val isLive: Boolean = false,
    val startTime: String = "",
    val endTime: String = "",
    
    // Timestamps
    val createdAt: String = "",
    val updatedAt: String = ""
) : Parcelable

// Channel link data class with DRM support
@Parcelize
data class ChannelLink(
    @SerializedName(value = "quality", alternate = ["label", "name"])
    val quality: String = "",
    val url: String = "",
    val cookie: String? = null,
    val referer: String? = null,
    val origin: String? = null,
    val userAgent: String? = null,
    val drmScheme: String? = null,
    val drmLicenseUrl: String? = null
) : Parcelable

@Parcelize
data class FavoriteChannel(
    val id: String = "",
    val name: String = "",
    val logoUrl: String = "",
    val streamUrl: String = "",
    val categoryId: String = "",
    val categoryName: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val links: List<ChannelLink>? = null
) : Parcelable

@Parcelize
data class LiveEvent(
    val id: String = "",
    val eventCategoryId: String = "",
    val eventCategoryName: String = "",
    @SerializedName(value = "category", alternate = ["eventCategoryName"])
    val category: String = "",
    val league: String = "",
    val leagueLogo: String = "",
    val team1Name: String = "",
    val team1Logo: String = "",
    val team2Name: String = "",
    val team2Logo: String = "",
    val startTime: String = "",
    val endTime: String? = null,
    val isLive: Boolean = false,
    val links: List<LiveEventLink> = emptyList(),
    val title: String = "",
    val description: String = "",
    val wrapper: String = "", // Wrapper text (e.g., "🔥 Hot")
    val createdAt: String = "",
    val updatedAt: String = ""
) : Parcelable {
    fun getStatus(currentTime: Long): EventStatus {
        val startTimestamp = parseTimestamp(startTime)
        val endTimestamp = endTime?.let { parseTimestamp(it) }

        return when {
            endTimestamp != null && currentTime >= startTimestamp && currentTime <= endTimestamp -> EventStatus.LIVE
            isLive && currentTime >= startTimestamp -> EventStatus.LIVE
            currentTime < startTimestamp -> EventStatus.UPCOMING
            endTimestamp != null && currentTime > endTimestamp -> EventStatus.RECENT
            currentTime > startTimestamp -> EventStatus.RECENT
            else -> EventStatus.UPCOMING
        }
    }

    private fun parseTimestamp(timeString: String): Long {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(timeString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}

@Parcelize
data class LiveEventLink(
    @SerializedName(value = "quality", alternate = ["label", "name"])
    val quality: String = "",
    val url: String = "",
    val cookie: String? = null,
    val referer: String? = null,
    val origin: String? = null,
    val userAgent: String? = null,
    val drmScheme: String? = null,
    val drmLicenseUrl: String? = null
) : Parcelable

@Parcelize
data class EventCategory(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
    val logoUrl: String = "",
    val order: Int = 0,
    val isDefault: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = ""
) : Parcelable

enum class EventStatus {
    LIVE, UPCOMING, RECENT
}
