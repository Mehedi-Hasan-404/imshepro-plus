package com.livetvpro.ui.categories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Channel
import com.livetvpro.data.repository.CategoryRepository
import com.livetvpro.data.repository.ChannelRepository
import com.livetvpro.data.repository.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadChannels(categoryId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _channels.value = channelRepository.getChannelsByCategory(categoryId)
            } catch (e: Exception) {
                _channels.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleFavorite(channel: Channel) {
        val favoriteChannel = com.livetvpro.data.models.FavoriteChannel(
            id = channel.id,
            name = channel.name,
            logoUrl = channel.logoUrl,
            streamUrl = channel.streamUrl,
            categoryId = channel.categoryId,
            categoryName = channel.categoryName
        )
        
        if (favoritesRepository.isFavorite(channel.id)) {
            favoritesRepository.removeFavorite(channel.id)
        } else {
            favoritesRepository.addFavorite(favoriteChannel)
        }
    }

    fun isFavorite(channelId: String): Boolean {
        return favoritesRepository.isFavorite(channelId)
    }
}

