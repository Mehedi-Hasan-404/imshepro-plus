package com.livetvpro.ui.player

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.data.repository.ChannelRepository
import com.livetvpro.data.repository.FavoritesRepository
import com.livetvpro.data.repository.LiveEventRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val channelRepository: ChannelRepository,
    private val liveEventRepository: LiveEventRepository
) : ViewModel() {

    private val _relatedItems = MutableLiveData<List<Channel>>()
    val relatedItems: LiveData<List<Channel>> = _relatedItems

    private val _isFavorite = MutableLiveData<Boolean>()
    val isFavorite: LiveData<Boolean> = _isFavorite

    /**
     * Check if a channel is favorited (async)
     */
    fun checkFavoriteStatus(channelId: String) {
        viewModelScope.launch {
            val result = favoritesRepository.isFavorite(channelId)
            _isFavorite.value = result
        }
    }

    /**
     * Toggle favorite status (async)
     */
    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            val currentStatus = favoritesRepository.isFavorite(channel.id)
            if (currentStatus) {
                favoritesRepository.removeFavorite(channel.id)
                _isFavorite.value = false
            } else {
                val favorite = FavoriteChannel(
                    id = channel.id,
                    name = channel.name,
                    logoUrl = channel.logoUrl,
                    streamUrl = channel.streamUrl,
                    categoryId = channel.categoryId,
                    categoryName = channel.categoryName
                )
                favoritesRepository.addFavorite(favorite)
                _isFavorite.value = true
            }
        }
    }

    /**
     * Load related channels for the current channel
     */
    fun loadRelatedChannels(categoryId: String, currentChannelId: String) {
        viewModelScope.launch {
            try {
                val allChannels = channelRepository.getChannelsByCategory(categoryId)
                val availableChannels = allChannels.filter { it.id != currentChannelId }

                if (availableChannels.isEmpty()) {
                    _relatedItems.postValue(emptyList())
                    return@launch
                }

                val currentIndex = allChannels.indexOfFirst { it.id == currentChannelId }
                val targetCount = 9 
                
                val related = if (currentIndex != -1) {
                    val relatedList = mutableListOf<Channel>()
                    val beforeCount = 4
                    val afterCount = 5
                    
                    val beforeStart = maxOf(0, currentIndex - beforeCount)
                    for (i in beforeStart until currentIndex) {
                        if (allChannels[i].id != currentChannelId) relatedList.add(allChannels[i])
                    }
                    
                    val afterEnd = minOf(allChannels.size - 1, currentIndex + afterCount)
                    for (i in (currentIndex + 1)..afterEnd) {
                        if (allChannels[i].id != currentChannelId) relatedList.add(allChannels[i])
                    }
                    
                    if (relatedList.size < targetCount && availableChannels.size > relatedList.size) {
                        val extra = availableChannels
                            .filter { it !in relatedList }
                            .shuffled()
                            .take(targetCount - relatedList.size)
                        relatedList.addAll(extra)
                    }
                    relatedList
                } else {
                    availableChannels.shuffled().take(targetCount)
                }
                
                _relatedItems.postValue(related)
            } catch (e: Exception) {
                _relatedItems.postValue(emptyList())
            }
        }
    }

    /**
     * Load related events for the current event
     */
    fun loadRelatedEvents(currentEventId: String) {
        viewModelScope.launch {
            try {
                val allEvents = liveEventRepository.getLiveEvents()
                
                // Filter: Get live and upcoming events, exclude current
                val relatedEvents = allEvents.filter { event ->
                    event.id != currentEventId && 
                    (event.isLive || event.getStatus(System.currentTimeMillis()) == com.livetvpro.data.models.EventStatus.UPCOMING)
                }

                // Convert to Channel format WITH event-specific fields
                val eventsAsChannels = relatedEvents.take(9).map { event ->
                    Channel(
                        id = event.id,
                        name = event.title.ifEmpty { "${event.team1Name} vs ${event.team2Name}" },
                        logoUrl = event.team1Logo.ifEmpty { event.team2Logo },
                        streamUrl = event.links.firstOrNull()?.url ?: "",
                        categoryId = "live_events",
                        categoryName = event.league,
                        
                        // Store event-specific data
                        team1Logo = event.team1Logo,
                        team2Logo = event.team2Logo,
                        isLive = event.isLive,
                        startTime = event.startTime,
                        endTime = event.endTime ?: ""
                    )
                }

                _relatedItems.postValue(eventsAsChannels)
            } catch (e: Exception) {
                _relatedItems.postValue(emptyList())
            }
        }
    }

    /**
     * Get all events (used for switching between events)
     */
    suspend fun getAllEvents(): List<LiveEvent> {
        return try {
            liveEventRepository.getLiveEvents()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
