package com.livetvpro.ui.live

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.EventCategory
import com.livetvpro.data.models.EventStatus
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.data.repository.LiveEventRepository
import com.livetvpro.utils.RetryViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class LiveEventsViewModel @Inject constructor(
    private val liveEventRepository: LiveEventRepository
) : RetryViewModel() {

    private val _events = MutableLiveData<List<LiveEvent>>()
    val events: LiveData<List<LiveEvent>> = _events

    private val _eventCategories = MutableLiveData<List<EventCategory>>()
    val eventCategories: LiveData<List<EventCategory>> = _eventCategories

    private val _filteredEvents = MutableLiveData<List<LiveEvent>>()
    val filteredEvents: LiveData<List<LiveEvent>> = _filteredEvents

    // Track last-used filter so we can re-apply it the instant data arrives
    private var pendingStatusFilter: EventStatus? = null
    private var pendingCategoryId: String = "evt_cat_all"

    // Reuse one SDF — creating a new one per parseTimestamp() call on every
    // 10 s tick is wasteful on large event lists
    private val sdf = java.text.SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault()
    ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }

    init {
        loadData()
    }

    override fun loadData() {
        loadEvents()
    }

    override fun onResume() {
        // Do nothing — data is already loaded and cached in memory
    }

    private fun loadEvents() {
        viewModelScope.launch {
            try {
                startLoading()
                val events = liveEventRepository.getLiveEvents()
                _events.value = events
                Timber.d("Loaded ${events.size} live events")
                finishLoading(dataIsEmpty = events.isEmpty())
                // Apply filter right away — no need to wait for the fragment's
                // observer to react, which caused the visible blank-screen delay
                filterEvents(pendingStatusFilter, pendingCategoryId)
            } catch (e: Exception) {
                Timber.e(e, "Error loading live events")
                _events.value = emptyList()
                finishLoading(dataIsEmpty = true, error = e)
            }
        }
    }

    fun loadEventCategories() {
        // Skip if already loaded — fragment recreates on every tab switch
        if (_eventCategories.value != null) return
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
        // Always save the requested filter — if data isn't loaded yet this will
        // be re-applied automatically once loadEvents() finishes
        pendingStatusFilter = status
        pendingCategoryId = categoryId
        val allEvents = _events.value ?: return
        val currentTime = System.currentTimeMillis()

        var filtered = allEvents

        if (categoryId != "evt_cat_all") {
            filtered = filtered.filter { event ->
                event.eventCategoryId == categoryId
            }
        }

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

        // Only emit if the list actually changed — prevents observer re-delivery flash
        if (filtered != _filteredEvents.value) {
            _filteredEvents.value = filtered
        }
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
            sdf.parse(timeString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
