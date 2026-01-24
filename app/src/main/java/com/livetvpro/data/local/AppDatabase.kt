package com.livetvpro.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.livetvpro.data.local.dao.FavoriteChannelDao
import com.livetvpro.data.local.entity.FavoriteChannelEntity

@Database(
    entities = [FavoriteChannelEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteChannelDao(): FavoriteChannelDao
}
