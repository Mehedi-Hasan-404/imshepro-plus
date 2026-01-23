package com.livetvpro.ui.live

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.EventCategory
import com.livetvpro.data.models.EventStatus
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.data.repository.LiveEventRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class LiveEventsViewModel @Inject constructor(
    private val liveEventRepository: LiveEventRepository
) : ViewModel() {

    private val _events = MutableLiveData<List<LiveEvent>>()
    val events: LiveData<List<LiveEvent>> = _events

    private val _eventCategories = MutableLiveData<List<EventCategory>>()
    val eventCategories: LiveData<List<EventCategory>> = _eventCategories

    private val _filteredEvents = MutableLiveData<List<LiveEvent>>()
    val filteredEvents: LiveData<List<LiveEvent>> = _filteredEvents

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    init {
        loadEvents()
    }

    fun loadEvents() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                val events = liveEventRepository.getLiveEvents()
                _events.value = events
                Timber.d("Loaded ${events.size} live events")
            } catch (e: Exception) {
                Timber.e(e, "Error loading live events")
                _error.value = "Failed to load events: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadEventCategories() {
        viewModelScope.launch {
            try {
                val categories = liveEventRepository.getEventCategories()
                _eventCategories.value = categories
                Timber.d("Loaded ${categories.size} event categories")
            } catch (e: Exception) {
                Timber.e(e, "Error loading event categories")
            }
        }
    }

    fun filterEvents(status: EventStatus?, categoryId: String = "evt_cat_all") {
        val allEvents = _events.value ?: return
        val currentTime = System.currentTimeMillis()

        var filtered = allEvents

        // Filter by category first
        if (categoryId != "evt_cat_all") {
            filtered = filtered.filter { event ->
                event.eventCategoryId == categoryId
            }
        }

        // Then filter by status
        filtered = when (status) {
            EventStatus.LIVE -> {
                filtered.filter { event ->
                    event.isLive || isEventLiveByTime(event, currentTime)
                }.sortedBy { it.startTime }
            }
            EventStatus.UPCOMING -> {
                filtered.filter { event ->
                    !event.isLive && !isEventLiveByTime(event, currentTime) && isEventUpcoming(event, currentTime)
                }.sortedBy { it.startTime }
            }
            EventStatus.RECENT -> {
                filtered.filter { event ->
                    !event.isLive && isEventEnded(event, currentTime)
                }.sortedByDescending { it.startTime }
            }
            null -> {
                // All events - sort by: Live first, then Upcoming, then Recent
                filtered.sortedWith(
                    compareBy<LiveEvent> { event ->
                        when {
                            event.isLive || isEventLiveByTime(event, currentTime) -> 0
                            isEventUpcoming(event, currentTime) -> 1
                            else -> 2
                        }
                    }.thenBy { it.startTime }
                )
            }
        }

        _filteredEvents.value = filtered
    }

    private fun isEventLiveByTime(event: LiveEvent, currentTime: Long): Boolean {
        return try {
            val startTime = parseTimestamp(event.startTime)
            val endTime = event.endTime?.let { parseTimestamp(it) } ?: Long.MAX_VALUE
            currentTime in startTime..endTime
        } catch (e: Exception) {
            false
        }
    }

    private fun isEventUpcoming(event: LiveEvent, currentTime: Long): Boolean {
        return try {
            val startTime = parseTimestamp(event.startTime)
            currentTime < startTime
        } catch (e: Exception) {
            false
        }
    }

    private fun isEventEnded(event: LiveEvent, currentTime: Long): Boolean {
        return try {
            val endTime = event.endTime?.let { parseTimestamp(it) }
                ?: parseTimestamp(event.startTime)
            currentTime > endTime
        } catch (e: Exception) {
            false
        }
    }

    private fun parseTimestamp(timeString: String): Long {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(timeString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
