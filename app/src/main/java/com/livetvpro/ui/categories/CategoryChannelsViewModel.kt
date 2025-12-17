package com.livetvpro.ui.categories

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.data.repository.CategoryRepository
import com.livetvpro.data.repository.ChannelRepository
import com.livetvpro.data.repository.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class CategoryChannelsViewModel @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val categoryRepository: CategoryRepository,
    private val favoritesRepository: FavoritesRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // Internal state using StateFlow for efficient combining
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    
    // Category name retrieved from Navigation arguments
    val categoryName: String = savedStateHandle.get<String>("categoryName") ?: "Channels"

    // UI State as LiveData for Fragment observation
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    /**
     * The final list displayed in the UI. 
     * It automatically filters the combined (Firestore + M3U) channel list 
     * whenever the user types in the search bar.
     */
    val filteredChannels: LiveData<List<Channel>> = combine(_channels, _searchQuery) { list, query ->
        if (query.isEmpty()) {
            list
        } else {
            list.filter { it.name.contains(query, ignoreCase = true) }
        }
    }.asLiveData(viewModelScope.context)

    /**
     * Fetches all channels for the category.
     * The updated Repository handles merging Firestore docs and M3U links.
     */
    fun loadChannels(categoryId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                Timber.d("Loading channels for category: $categoryId")
                // Calls the updated repository method that merges both sources
                val result = channelRepository.getChannelsByCategory(categoryId)
                _channels.value = result
                Timber.d("Successfully loaded ${result.size} channels")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load channels")
                _error.value = "Failed to load channels: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Updates the search query. Triggered by the SearchableFragment interface.
     */
    fun searchChannels(query: String) {
        _searchQuery.value = query
    }

    /**
     * Handles adding/removing channels from local favorites.
     * Preserves the streamUrl to ensure favorites can play offline/instantly.
     */
    fun toggleFavorite(channel: Channel) {
        val favoriteChannel = FavoriteChannel(
            id = channel.id,
            name = channel.name,
            logoUrl = channel.logoUrl,
            streamUrl = channel.streamUrl,
            categoryId = channel.categoryId,
            categoryName = channel.categoryName
        )
        
        if (favoritesRepository.isFavorite(channel.id)) {
            favoritesRepository.removeFavorite(channel.id)
            Timber.d("Removed from favorites: ${channel.name}")
        } else {
            favoritesRepository.addFavorite(favoriteChannel)
            Timber.d("Added to favorites: ${channel.name}")
        }
    }

    /**
     * Checks if a channel is currently a favorite for UI star icon display.
     */
    fun isFavorite(channelId: String): Boolean {
        return favoritesRepository.isFavorite(channelId)
    }
}

