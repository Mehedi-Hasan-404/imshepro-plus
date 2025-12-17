package com.livetvpro.ui.categories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.data.repository.CategoryRepository
import com.livetvpro.data.repository.ChannelRepository
import com.livetvpro.data.repository.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoryChannelsViewModel @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val categoryRepository: CategoryRepository,
    private val favoritesRepository: FavoritesRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    
    val categoryName: String = savedStateHandle.get<String>("categoryName") ?: "Channels"

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // This fixes the "Unresolved reference: filteredChannels" error
    val filteredChannels: StateFlow<List<Channel>> = combine(_channels, _searchQuery) { list, query ->
        if (query.isEmpty()) list
        else list.filter { it.name.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun loadChannels(categoryId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _channels.value = channelRepository.getChannelsByCategory(categoryId)
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    // This fixes the "Unresolved reference: searchChannels" error
    fun searchChannels(query: String) {
        _searchQuery.value = query
    }

    fun toggleFavorite(channel: Channel) {
        val favoriteChannel = FavoriteChannel(
            id = channel.id,
            name = channel.name,
            logoUrl = channel.logoUrl,
            streamUrl = channel.streamUrl, // Fixes playback from favorites
            categoryId = channel.categoryId,
            categoryName = channel.categoryName
        )
        
        if (favoritesRepository.isFavorite(channel.id)) {
            favoritesRepository.removeFavorite(channel.id)
        } else {
            favoritesRepository.addFavorite(favoriteChannel)
        }
    }

    fun isFavorite(channelId: String): Boolean = favoritesRepository.isFavorite(channelId)
}

