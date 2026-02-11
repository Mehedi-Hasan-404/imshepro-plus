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
    private val nativeDataRepository: NativeDataRepository
) : ViewModel() {

    val favorites = favoritesRepository.getFavoritesFlow().asLiveData()

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
                val streamUrlToSave = when {
                    channel.streamUrl.isNotEmpty() -> channel.streamUrl
                    !channel.links.isNullOrEmpty() -> {
                        val firstLink = channel.links.first()
                        buildStreamUrlFromLink(firstLink)
                    }
                    else -> ""
                }
                
                val fav = FavoriteChannel(
                    id = channel.id,
                    name = channel.name,
                    logoUrl = channel.logoUrl,
                    streamUrl = streamUrlToSave,
                    categoryId = channel.categoryId,
                    categoryName = channel.categoryName,
                    links = channel.links
                )
                favoritesRepository.addFavorite(fav)
            }
        }
    }
    
    private fun buildStreamUrlFromLink(link: com.livetvpro.data.models.ChannelLink): String {
        val parts = mutableListOf<String>()
        parts.add(link.url)
        
        link.referer?.let { if (it.isNotEmpty()) parts.add("referer=$it") }
        link.cookie?.let { if (it.isNotEmpty()) parts.add("cookie=$it") }
        link.origin?.let { if (it.isNotEmpty()) parts.add("origin=$it") }
        link.userAgent?.let { if (it.isNotEmpty()) parts.add("User-Agent=$it") }
        link.drmScheme?.let { if (it.isNotEmpty()) parts.add("drmScheme=$it") }
        link.drmLicenseUrl?.let { if (it.isNotEmpty()) parts.add("drmLicense=$it") }
        
        return if (parts.size > 1) {
            parts.joinToString("|")
        } else {
            parts[0]
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
