package com.livetvpro.ui.categories

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.ChannelLink
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.data.repository.CategoryRepository
import com.livetvpro.data.repository.ChannelRepository
import com.livetvpro.data.repository.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
    
    // Cache for favorite status to avoid repeated DB queries
    private val _favoriteStatusCache = MutableStateFlow<Set<String>>(emptySet())
    
    val categoryName: String = savedStateHandle.get<String>("categoryName") ?: "Channels"

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    val filteredChannels: LiveData<List<Channel>> = combine(_channels, _searchQuery) { list, query ->
        if (query.isEmpty()) list
        else list.filter { it.name.contains(query, ignoreCase = true) }
    }.asLiveData(viewModelScope.coroutineContext + Dispatchers.Main)

    init {
        // Load favorite status cache
        loadFavoriteCache()
    }

    /**
     * Load all favorite IDs into cache
     */
    private fun loadFavoriteCache() {
        viewModelScope.launch {
            favoritesRepository.getFavoritesFlow().collect { favorites ->
                _favoriteStatusCache.value = favorites.map { it.id }.toSet()
            }
        }
    }

    fun loadChannels(categoryId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = channelRepository.getChannelsByCategory(categoryId)
                _channels.value = result
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun searchChannels(query: String) {
        _searchQuery.value = query
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            // Convert Channel.links to FavoriteChannel.links
            val favoriteLinks = channel.links?.map { channelLink ->
                ChannelLink(
                    quality = channelLink.quality,
                    url = channelLink.url
                )
            }
            
            val favoriteChannel = FavoriteChannel(
                id = channel.id,
                name = channel.name,
                logoUrl = channel.logoUrl,
                streamUrl = channel.streamUrl,
                categoryId = channel.categoryId,
                categoryName = channel.categoryName,
                links = favoriteLinks // Pass links to favorite
            )
            
            if (favoritesRepository.isFavorite(channel.id)) {
                favoritesRepository.removeFavorite(channel.id)
            } else {
                favoritesRepository.addFavorite(favoriteChannel)
            }
            
            // Cache will update automatically via Flow
        }
    }

    /**
     * Check if a channel is favorited using the cache
     * This is called synchronously from the adapter
     */
    fun isFavorite(channelId: String): Boolean {
        return _favoriteStatusCache.value.contains(channelId)
    }
}
