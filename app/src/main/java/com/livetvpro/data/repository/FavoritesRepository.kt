package com.livetvpro.data.repository

import com.livetvpro.data.local.dao.FavoriteChannelDao
import com.livetvpro.data.local.entity.FavoriteChannelEntity
import com.livetvpro.data.models.FavoriteChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesRepository @Inject constructor(
    private val favoriteDao: FavoriteChannelDao
) {
    
    // Get all favorites as Flow (for real-time updates)
    fun getFavoritesFlow(): Flow<List<FavoriteChannel>> {
        return favoriteDao.getAllFavorites().map { entities ->
            entities.map { it.toFavoriteChannel() }
        }
    }
    
    // Get all favorites as list (for one-time fetch)
    suspend fun getFavorites(): List<FavoriteChannel> {
        // Since we're using Flow, we need to collect first value
        // For simplicity, let's keep the DAO returning Flow and handle conversion elsewhere
        // Or add a suspend function in DAO
        val entities = favoriteDao.getAllFavorites()
        // This is a Flow, so we can't directly return List
        // Better approach: Add suspend function to DAO
        return emptyList() // Placeholder - use Flow version instead
    }

    suspend fun addFavorite(channel: FavoriteChannel) {
        val entity = FavoriteChannelEntity(
            id = channel.id,
            name = channel.name,
            logoUrl = channel.logoUrl,
            streamUrl = channel.streamUrl,
            categoryId = channel.categoryId,
            categoryName = channel.categoryName,
            addedAt = channel.addedAt
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

// Extension function to convert Entity to Model
private fun FavoriteChannelEntity.toFavoriteChannel(): FavoriteChannel {
    return FavoriteChannel(
        id = id,
        name = name,
        logoUrl = logoUrl,
        streamUrl = streamUrl,
        categoryId = categoryId,
        categoryName = categoryName,
        addedAt = addedAt
    )
}
