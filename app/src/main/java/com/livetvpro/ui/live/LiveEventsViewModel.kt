package com.livetvpro.ui.live

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    private val _filteredEvents = MutableLiveData<List<LiveEvent>>()
    val filteredEvents: LiveData<List<LiveEvent>> = _filteredEvents

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var currentFilter = EventStatus.LIVE

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
                filterEvents(currentFilter)
                Timber.d("Loaded ${events.size} live events")
            } catch (e: Exception) {
                Timber.e(e, "Error loading live events")
                _error.value = "Failed to load events: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun filterEvents(status: EventStatus?) {
        val allEvents = _events.value ?: return
        val currentTime = System.currentTimeMillis()

        _filteredEvents.value = if (status == null) {
            allEvents.sortedWith(compareByDescending<LiveEvent> { it.isLive }
                .thenBy { parseTimestamp(it.startTime) })
        } else {
            currentFilter = status
            allEvents.filter { it.getStatus(currentTime) == status }
                .sortedBy { parseTimestamp(it.startTime) }
        }
    }

    private fun parseTimestamp(timeString: String): Long {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.getDefault())
                .parse(timeString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
