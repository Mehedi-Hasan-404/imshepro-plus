package com.livetvpro.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val url: String,
    val isFile: Boolean,
    val filePath: String,
    val createdAt: Long,
    val updatedAt: Long
)
