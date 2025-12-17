package com.livetvpro.data.repository

import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.data.models.FavoriteChannel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesRepository @Inject constructor(
    private val preferencesManager: PreferencesManager
) {
    fun getFavorites(): List<FavoriteChannel> {
        return preferencesManager.getFavorites()
    }

    fun addFavorite(channel: FavoriteChannel) {
        val favorites = getFavorites().toMutableList()
        favorites.removeAll { it.id == channel.id }
        favorites.add(0, channel)
        preferencesManager.saveFavorites(favorites)
    }

    fun removeFavorite(channelId: String) {
        val favorites = getFavorites().toMutableList()
        favorites.removeAll { it.id == channelId }
        preferencesManager.saveFavorites(favorites)
    }

    fun isFavorite(channelId: String): Boolean {
        return getFavorites().any { it.id == channelId }
    }

    fun clearAll() {
        preferencesManager.saveFavorites(emptyList())
    }
}

