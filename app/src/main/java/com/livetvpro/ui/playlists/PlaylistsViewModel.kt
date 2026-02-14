package com.livetvpro.ui.playlists

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Playlist
import com.livetvpro.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository
) : ViewModel() {

    val playlists: LiveData<List<Playlist>> = playlistRepository.getAllPlaylists()
        .catch { e ->
            _error.postValue("Failed to load playlists: ${e.message}")
            emit(emptyList())
        }
        .asLiveData()

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    fun addPlaylist(title: String, url: String = "", isFile: Boolean = false, filePath: String = "") {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                playlistRepository.addPlaylist(title, url, isFile, filePath)
            } catch (e: Exception) {
                _error.value = "Failed to add playlist: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updatePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                playlistRepository.updatePlaylist(playlist)
            } catch (e: Exception) {
                _error.value = "Failed to update playlist: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                playlistRepository.deletePlaylist(playlist)
            } catch (e: Exception) {
                _error.value = "Failed to delete playlist: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
