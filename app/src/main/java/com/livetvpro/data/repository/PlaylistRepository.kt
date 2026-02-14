package com.livetvpro.data.repository

import com.livetvpro.data.local.dao.PlaylistDao
import com.livetvpro.data.local.entity.PlaylistEntity
import com.livetvpro.data.models.Playlist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao
) {
    fun getAllPlaylists(): Flow<List<Playlist>> {
        return playlistDao.getAllPlaylists().map { entities ->
            entities.map { it.toPlaylist() }
        }
    }

    suspend fun getPlaylistById(playlistId: String): Playlist? {
        return playlistDao.getPlaylistById(playlistId)?.toPlaylist()
    }

    suspend fun addPlaylist(title: String, url: String = "", isFile: Boolean = false, filePath: String = ""): Playlist {
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            title = title,
            url = url,
            isFile = isFile,
            filePath = filePath,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        playlistDao.insertPlaylist(playlist.toEntity())
        return playlist
    }

    suspend fun updatePlaylist(playlist: Playlist) {
        val updatedPlaylist = playlist.copy(updatedAt = System.currentTimeMillis())
        playlistDao.updatePlaylist(updatedPlaylist.toEntity())
    }

    suspend fun deletePlaylist(playlist: Playlist) {
        playlistDao.deletePlaylist(playlist.toEntity())
    }

    suspend fun deletePlaylistById(playlistId: String) {
        playlistDao.deletePlaylistById(playlistId)
    }

    suspend fun deleteAllPlaylists() {
        playlistDao.deleteAllPlaylists()
    }

    private fun PlaylistEntity.toPlaylist() = Playlist(
        id = id,
        title = title,
        url = url,
        isFile = isFile,
        filePath = filePath,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun Playlist.toEntity() = PlaylistEntity(
        id = id,
        title = title,
        url = url,
        isFile = isFile,
        filePath = filePath,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
