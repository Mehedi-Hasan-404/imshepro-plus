package com.livetvpro.ui.player

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.data.repository.CategoryRepository
import com.livetvpro.data.repository.ChannelRepository
import com.livetvpro.data.repository.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val channelRepository: ChannelRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _relatedChannels = MutableLiveData<List<Channel>>()
    val relatedChannels: LiveData<List<Channel>> = _relatedChannels

    private val _isFavorite = MutableLiveData<Boolean>()
    val isFavorite: LiveData<Boolean> = _isFavorite

    /**
     * Checks if the current channel is in the user's favorites
     */
    fun checkFavoriteStatus(channelId: String) {
        _isFavorite.value = favoritesRepository.isFavorite(channelId)
    }

    /**
     * Toggles favorite status. 
     * IMPORTANT: We now include the streamUrl so that favorites play correctly.
     */
    fun toggleFavorite(channel: Channel) {
        val currentStatus = favoritesRepository.isFavorite(channel.id)
        if (currentStatus) {
            favoritesRepository.removeFavorite(channel.id)
            _isFavorite.value = false
        } else {
            val favorite = FavoriteChannel(
                id = channel.id,
                name = channel.name,
                logoUrl = channel.logoUrl,
                streamUrl = channel.streamUrl, // Added to fix playback from favorites
                categoryId = channel.categoryId,
                categoryName = channel.categoryName
            )
            favoritesRepository.addFavorite(favorite)
            _isFavorite.value = true
        }
    }

    /**
     * Loads related channels from the same category.
     * Uses the updated repository which handles the merging logic automatically.
     */
    fun loadRelatedChannels(categoryId: String, currentChannelId: String) {
        viewModelScope.launch {
            try {
                Timber.d("Loading related channels for category: $categoryId")
                
                // Use the updated repository method that merges Firestore and M3U
                // This resolves the "Unresolved reference: getAllChannels" error
                val allChannels = channelRepository.getChannelsByCategory(categoryId)
                
                Timber.d("Total channels loaded for relation: ${allChannels.size}")
                
                // Filter out the channel currently playing
                val availableChannels = allChannels.filter { it.id != currentChannelId }
                
                if (availableChannels.isEmpty()) {
                    _relatedChannels.postValue(emptyList())
                    return@launch
                }
                
                // Logic to select channels around the current position
                val currentIndex = allChannels.indexOfFirst { it.id == currentChannelId }
                
                val related = if (currentIndex != -1) {
                    val beforeCount = 4
                    val afterCount = 5
                    val relatedList = mutableListOf<Channel>()
                    
                    // Grab channels appearing before the current one
                    val beforeStart = maxOf(0, currentIndex - beforeCount)
                    for (i in beforeStart until currentIndex) {
                        if (allChannels[i].id != currentChannelId) relatedList.add(allChannels[i])
                    }
                    
                    // Grab channels appearing after the current one
                    val afterEnd = minOf(allChannels.size - 1, currentIndex + afterCount)
                    for (i in (currentIndex + 1)..afterEnd) {
                        if (allChannels[i].id != currentChannelId) relatedList.add(allChannels[i])
                    }
                    
                    // Fill with randoms if we haven't reached 9 yet
                    if (relatedList.size < 9 && availableChannels.size > relatedList.size) {
                        val extra = availableChannels
                            .filter { it !in relatedList }
                            .shuffled()
                            .take(9 - relatedList.size)
                        relatedList.addAll(extra)
                    }
                    relatedList.take(9)
                } else {
                    availableChannels.shuffled().take(9)
                }
                
                _relatedChannels.postValue(related)
            } catch (e: Exception) {
                Timber.e(e, "Error loading related channels")
                _relatedChannels.postValue(emptyList())
            }
        }
    }
}

