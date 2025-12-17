package com.livetvpro.ui.favorites

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.data.repository.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository
) : ViewModel() {

    private val _favorites = MutableLiveData<List<FavoriteChannel>>()
    val favorites: LiveData<List<FavoriteChannel>> = _favorites

    init {
        loadFavorites()
    }

    fun loadFavorites() {
        _favorites.value = favoritesRepository.getFavorites()
    }

    fun toggleFavorite(channel: Channel) {
        if (favoritesRepository.isFavorite(channel.id)) {
            favoritesRepository.removeFavorite(channel.id)
        } else {
            val fav = FavoriteChannel(
                id = channel.id,
                name = channel.name,
                logoUrl = channel.logoUrl,
                streamUrl = channel.streamUrl, // Pass streamUrl to model
                categoryId = channel.categoryId,
                categoryName = channel.categoryName
            )
            favoritesRepository.addFavorite(fav)
        }
        loadFavorites()
    }

    fun removeFavorite(channelId: String) {
        favoritesRepository.removeFavorite(channelId)
        loadFavorites()
    }
}

