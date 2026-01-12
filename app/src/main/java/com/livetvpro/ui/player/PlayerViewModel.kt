package com.livetvpro.ui.player

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.data.repository.ChannelRepository
import com.livetvpro.data.repository.LiveEventRepository
import kotlinx.coroutines.launch

class PlayerViewModel : ViewModel() {

    // Repositories (Ideally injected via Hilt/Koin, instantiated here for simplicity if not using DI)
    private val channelRepository = ChannelRepository()
    private val eventRepository = LiveEventRepository()

    private val _relatedItems = MutableLiveData<List<Channel>>()
    val relatedItems: LiveData<List<Channel>> = _relatedItems

    fun loadRelatedChannels(categoryId: String, excludeId: String) {
        viewModelScope.launch {
            try {
                val channels = channelRepository.getChannelsByCategory(categoryId)
                _relatedItems.value = channels.filter { it.id != excludeId }
            } catch (e: Exception) {
                // Handle error if needed
            }
        }
    }

    fun loadRelatedEvents(excludeId: String) {
        viewModelScope.launch {
            try {
                val events = eventRepository.getAllEvents()
                _relatedItems.value = events.filter { it.id != excludeId }.map { event ->
                    Channel(
                        id = event.id,
                        name = event.title.ifEmpty { "${event.team1Name} vs ${event.team2Name}" },
                        description = event.description ?: "",
                        streamUrl = "",
                        categoryId = ""
                        // Add other necessary fields with defaults as per your Channel model
                    )
                }
            } catch (e: Exception) {
                // Handle error if needed
            }
        }
    }

    suspend fun getAllEvents(): List<LiveEvent> {
        return eventRepository.getAllEvents()
    }
}
