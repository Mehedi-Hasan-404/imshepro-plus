package com.livetvpro.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.livetvpro.data.local.dao.FavoriteChannelDao
import com.livetvpro.data.local.dao.PlaylistDao
import com.livetvpro.data.local.entity.FavoriteChannelEntity
import com.livetvpro.data.local.entity.FavoriteChannelConverters
import com.livetvpro.data.local.entity.PlaylistEntity

@Database(
    entities = [FavoriteChannelEntity::class, PlaylistEntity::class],
    version = 4,  // Incremented version for new entity
    exportSchema = false
)
@TypeConverters(FavoriteChannelConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteChannelDao(): FavoriteChannelDao
    abstract fun playlistDao(): PlaylistDao
}
