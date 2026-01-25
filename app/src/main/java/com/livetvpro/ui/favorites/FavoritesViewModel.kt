package com.livetvpro.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.data.repository.FavoritesRepository
import com.livetvpro.data.repository.NativeDataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val nativeDataRepository: NativeDataRepository // ✅ Added Injection
) : ViewModel() {

    // Observe favorites as LiveData
    val favorites = favoritesRepository.getFavoritesFlow().asLiveData()

    /**
     * ✅ NEW: Get the fresh/live version of a channel by ID.
     * This ensures we have the latest links even if the favorite snapshot is old.
     */
    fun getLiveChannel(channelId: String): Channel? {
        return try {
            nativeDataRepository.getChannels().find { it.id == channelId }
        } catch (e: Exception) {
            null
        }
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            if (favoritesRepository.isFavorite(channel.id)) {
                favoritesRepository.removeFavorite(channel.id)
            } else {
                val fav = FavoriteChannel(
                    id = channel.id,
                    name = channel.name,
                    logoUrl = channel.logoUrl,
                    streamUrl = channel.streamUrl,
                    categoryId = channel.categoryId,
                    categoryName = channel.categoryName
                )
                favoritesRepository.addFavorite(fav)
            }
        }
    }

    fun removeFavorite(channelId: String) {
        viewModelScope.launch {
            favoritesRepository.removeFavorite(channelId)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            favoritesRepository.clearAll()
        }
    }
}
