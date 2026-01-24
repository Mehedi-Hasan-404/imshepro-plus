package com.livetvpro.data.local.dao

import androidx.room.*
import com.livetvpro.data.local.entity.FavoriteChannelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteChannelDao {
    
    @Query("SELECT * FROM favorite_channels ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteChannelEntity>>
    
    @Query("SELECT * FROM favorite_channels WHERE id = :channelId LIMIT 1")
    suspend fun getFavoriteById(channelId: String): FavoriteChannelEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteChannelEntity)
    
    @Delete
    suspend fun deleteFavorite(favorite: FavoriteChannelEntity)
    
    @Query("DELETE FROM favorite_channels WHERE id = :channelId")
    suspend fun deleteFavoriteById(channelId: String)
    
    @Query("DELETE FROM favorite_channels")
    suspend fun deleteAllFavorites()
    
    @Query("SELECT EXISTS(SELECT 1 FROM favorite_channels WHERE id = :channelId)")
    suspend fun isFavorite(channelId: String): Boolean
    
    @Query("SELECT COUNT(*) FROM favorite_channels")
    suspend fun getFavoritesCount(): Int
}
