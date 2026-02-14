package com.livetvpro.ui.player

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.ChannelLink
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.data.repository.ChannelRepository
import com.livetvpro.data.repository.FavoritesRepository
import com.livetvpro.data.repository.LiveEventRepository
import com.livetvpro.data.repository.NativeDataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val channelRepository: ChannelRepository,
    private val liveEventRepository: LiveEventRepository,
    private val nativeDataRepository: NativeDataRepository
) : ViewModel() {

    private val _relatedItems = MutableLiveData<List<Channel>>()
    val relatedItems: LiveData<List<Channel>> = _relatedItems

    private val _relatedLiveEvents = MutableLiveData<List<LiveEvent>>()
    val relatedLiveEvents: LiveData<List<LiveEvent>> = _relatedLiveEvents

    private val _isFavorite = MutableLiveData<Boolean>()
    val isFavorite: LiveData<Boolean> = _isFavorite

    private val _refreshedChannel = MutableLiveData<Channel?>()
    val refreshedChannel: LiveData<Channel?> = _refreshedChannel

    fun refreshChannelData(channelId: String) {
        viewModelScope.launch {
            try {
                val allChannels = nativeDataRepository.getChannels()
                val freshChannel = allChannels.find { it.id == channelId }
                if (freshChannel != null) {
                    _refreshedChannel.postValue(freshChannel)
                }
            } catch (e: Exception) {
            }
        }
    }

    fun checkFavoriteStatus(channelId: String) {
        viewModelScope.launch {
            val result = favoritesRepository.isFavorite(channelId)
            _isFavorite.value = result
        }
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            val currentStatus = favoritesRepository.isFavorite(channel.id)
            if (currentStatus) {
                favoritesRepository.removeFavorite(channel.id)
                _isFavorite.value = false
            } else {
                val favoriteLinks = channel.links?.map { channelLink ->
                    ChannelLink(
                        quality = channelLink.quality,
                        url = channelLink.url
                    )
                }
                
                val favorite = FavoriteChannel(
                    id = channel.id,
                    name = channel.name,
                    logoUrl = channel.logoUrl,
                    streamUrl = channel.streamUrl,
                    categoryId = channel.categoryId,
                    categoryName = channel.categoryName,
                    links = favoriteLinks
                )
                favoritesRepository.addFavorite(favorite)
                _isFavorite.value = true
            }
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
    
    /**
     * Set related channels directly (e.g., from playlist)
     */
    fun setRelatedChannels(channels: List<Channel>) {
        _relatedItems.postValue(channels.take(9))
    }

    fun loadRelatedEvents(currentEventId: String) {
        viewModelScope.launch {
            try {
                val allEvents = liveEventRepository.getLiveEvents()
                val currentTime = System.currentTimeMillis()
                
                val eventsWithStatus = allEvents.map { event ->
                    event to event.getStatus(currentTime)
                }
                
                val liveEvents = eventsWithStatus.filter { (event, status) ->
                    event.id != currentEventId && status == com.livetvpro.data.models.EventStatus.LIVE
                }
                
                val upcomingEvents = eventsWithStatus.filter { (event, status) ->
                    event.id != currentEventId && status == com.livetvpro.data.models.EventStatus.UPCOMING
                }
                
                val relatedEvents = (liveEvents.map { it.first } + upcomingEvents.map { it.first }).take(6)
                
                _relatedLiveEvents.postValue(relatedEvents)
                
            } catch (e: Exception) {
                _relatedLiveEvents.postValue(emptyList())
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
