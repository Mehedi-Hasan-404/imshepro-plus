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

    fun checkFavoriteStatus(channelId: String) {
        _isFavorite.value = favoritesRepository.isFavorite(channelId)
    }

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
                streamUrl = channel.streamUrl,
                categoryId = channel.categoryId,
                categoryName = channel.categoryName
            )
            favoritesRepository.addFavorite(favorite)
            _isFavorite.value = true
        }
    }

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

    fun loadRelatedEvents(currentEventId: String) {
        viewModelScope.launch {
            try {
                val allEvents = liveEventRepository.getLiveEvents()
                val liveAndUpcomingEvents = allEvents.filter { event ->
                    event.id != currentEventId && event.isLive
                }

                val eventsToShow = liveAndUpcomingEvents.take(5).map { event ->
                    Channel(
                        id = event.id,
                        name = event.title.ifEmpty { "${event.team1Name} vs ${event.team2Name}" },
                        logoUrl = event.team1Logo.ifEmpty { event.team2Logo },
                        streamUrl = event.links.firstOrNull()?.url ?: "",
                        categoryId = "live_events",
                        categoryName = "Live Events"
                    )
                }

                _relatedItems.postValue(eventsToShow)
            } catch (e: Exception) {
                _relatedItems.postValue(emptyList())
            }
        }
    }

    suspend fun getAllEvents(): List<LiveEvent> {
        return try {
            liveEventRepository.getLiveEvents()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
