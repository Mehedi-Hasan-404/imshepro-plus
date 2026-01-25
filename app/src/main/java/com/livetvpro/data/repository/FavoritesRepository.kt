package com.livetvpro.data.repository

import com.google.gson.Gson
import com.livetvpro.data.local.dao.FavoriteChannelDao
import com.livetvpro.data.local.entity.FavoriteChannelEntity
import com.livetvpro.data.models.ChannelLink
import com.livetvpro.data.models.FavoriteChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesRepository @Inject constructor(
    private val favoriteDao: FavoriteChannelDao
) {
    private val gson = Gson()
    
    fun getFavoritesFlow(): Flow<List<FavoriteChannel>> {
        return favoriteDao.getAllFavorites().map { entities ->
            entities.map { it.toFavoriteChannel() }
        }
    }

    suspend fun addFavorite(channel: FavoriteChannel) {
        val linksJson = if (channel.links != null && channel.links.isNotEmpty()) {
            try {
                gson.toJson(channel.links)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        
        val entity = FavoriteChannelEntity(
            id = channel.id,
            name = channel.name,
            logoUrl = channel.logoUrl,
            streamUrl = channel.streamUrl,
            categoryId = channel.categoryId,
            categoryName = channel.categoryName,
            addedAt = channel.addedAt,
            linksJson = linksJson
        )
        
        favoriteDao.insertFavorite(entity)
    }

    suspend fun removeFavorite(channelId: String) {
        favoriteDao.deleteFavoriteById(channelId)
    }

    suspend fun isFavorite(channelId: String): Boolean {
        return favoriteDao.isFavorite(channelId)
    }

    suspend fun clearAll() {
        favoriteDao.deleteAllFavorites()
    }
    
    suspend fun getFavoritesCount(): Int {
        return favoriteDao.getFavoritesCount()
    }
}

private fun FavoriteChannelEntity.toFavoriteChannel(): FavoriteChannel {
    val links = if (!linksJson.isNullOrEmpty()) {
        try {
            val gson = Gson()
            val type = object : com.google.gson.reflect.TypeToken<List<ChannelLink>>() {}.type
            gson.fromJson<List<ChannelLink>>(linksJson, type)
        } catch (e: Exception) {
            null
        }
    } else {
        null
    }
    
    return FavoriteChannel(
        id = id,
        name = name,
        logoUrl = logoUrl,
        streamUrl = streamUrl,
        categoryId = categoryId,
        categoryName = categoryName,
        addedAt = addedAt,
        links = links
    )
}
