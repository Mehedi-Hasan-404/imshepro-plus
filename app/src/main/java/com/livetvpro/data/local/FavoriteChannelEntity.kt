package com.livetvpro.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_channels")
data class FavoriteChannelEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val logoUrl: String,
    val streamUrl: String,
    val categoryId: String,
    val categoryName: String,
    val addedAt: Long = System.currentTimeMillis()
)
