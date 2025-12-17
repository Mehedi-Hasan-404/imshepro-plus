package com.livetvpro.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Category(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
    val iconUrl: String? = null,
    val m3uUrl: String? = null,
    val order: Int = 0
) : Parcelable

@Parcelize
data class Channel(
    val id: String = "",
    val name: String = "",
    val logoUrl: String = "",
    val streamUrl: String = "",
    val categoryId: String = "",
    val categoryName: String = ""
) : Parcelable

@Parcelize
data class FavoriteChannel(
    val id: String = "",
    val name: String = "",
    val logoUrl: String = "",
    val streamUrl: String = "", // Crucial fix: stores the link
    val categoryId: String = "",
    val categoryName: String = "",
    val addedAt: Long = System.currentTimeMillis()
) : Parcelable

@Parcelize
data class LiveEvent(
    val id: String = "",
    val category: String = "",
    val league: String = "",
    val team1Name: String = "",
    val team1Logo: String = "",
    val team2Name: String = "",
    val team2Logo: String = "",
    val startTime: String = "",
    val endTime: String? = null,
    val isLive: Boolean = false,
    val links: List<LiveEventLink> = emptyList(),
    val title: String = "",
    val description: String = ""
) : Parcelable {
    fun getStatus(currentTime: Long): EventStatus {
        val startTimestamp = parseTimestamp(startTime)
        val endTimestamp = endTime?.let { parseTimestamp(it) }

        return when {
            isLive -> EventStatus.LIVE
            currentTime < startTimestamp -> EventStatus.UPCOMING
            endTimestamp != null && currentTime > endTimestamp -> EventStatus.RECENT
            currentTime > startTimestamp -> EventStatus.RECENT
            else -> EventStatus.UPCOMING
        }
    }

    private fun parseTimestamp(timeString: String): Long {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.getDefault())
                .parse(timeString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}

@Parcelize
data class LiveEventLink(
    val label: String = "",
    val url: String = ""
) : Parcelable

enum class EventStatus {
    LIVE, UPCOMING, RECENT
}

