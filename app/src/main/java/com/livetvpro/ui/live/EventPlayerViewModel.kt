package com.livetvpro.ui.live

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.data.repository.LiveEventRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class EventPlayerViewModel @Inject constructor(
    private val repository: LiveEventRepository
) : ViewModel() {

    private val _event = MutableLiveData<LiveEvent?>()
    val event: LiveData<LiveEvent?> = _event

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    fun loadEvent(eventId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val e = repository.getEventById(eventId)
                _event.value = e
                if (e == null) _error.value = "Event not found"
                Timber.d("Loaded event ${e?.id}")
            } catch (ex: Exception) {
                Timber.e(ex, "Error loading event")
                _error.value = ex.message ?: "Failed to load event"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
