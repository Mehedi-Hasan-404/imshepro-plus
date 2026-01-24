package com.livetvpro.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.livetvpro.data.models.ChannelLink

@Entity(tableName = "favorite_channels")
data class FavoriteChannelEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val logoUrl: String,
    val streamUrl: String,
    val categoryId: String,
    val categoryName: String,
    val addedAt: Long = System.currentTimeMillis(),
    val linksJson: String? = null // Store links as JSON string
)

/**
 * Room Type Converters for ChannelLink list
 */
class FavoriteChannelConverters {
    private val gson = Gson()
    
    @TypeConverter
    fun fromLinksList(links: List<ChannelLink>?): String? {
        if (links == null) return null
        return gson.toJson(links)
    }
    
    @TypeConverter
    fun toLinksList(linksJson: String?): List<ChannelLink>? {
        if (linksJson == null) return null
        val type = object : TypeToken<List<ChannelLink>>() {}.type
        return gson.fromJson(linksJson, type)
    }
}
