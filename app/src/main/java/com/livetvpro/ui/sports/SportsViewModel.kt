package com.livetvpro.ui.sports

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Sport
import com.livetvpro.data.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SportsViewModel @Inject constructor(
    private val repository: CategoryRepository
) : ViewModel() {

    private val _sports = MutableLiveData<List<Sport>>()
    private val _filteredSports = MutableLiveData<List<Sport>>()
    val filteredSports: LiveData<List<Sport>> = _filteredSports

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var currentQuery: String = ""

    init {
        loadSports()
    }

    fun loadSports() {
        viewModelScope.launch {
            repository.getSports()
                .onStart {
                    _isLoading.value = true
                    _error.value = null
                }
                .catch { e ->
                    _isLoading.value = false
                    _error.value = e.message ?: "Failed to load sports"
                }
                .collect { sports ->
                    _isLoading.value = false
                    _sports.value = sports
                    applyFilter()
                }
        }
    }

    fun searchSports(query: String) {
        currentQuery = query
        applyFilter()
    }

    private fun applyFilter() {
        val sports = _sports.value ?: emptyList()
        
        if (currentQuery.isBlank()) {
            _filteredSports.value = sports
        } else {
            _filteredSports.value = sports.filter { sport ->
                sport.name.contains(currentQuery, ignoreCase = true)
            }
        }
    }

    fun retry() {
        loadSports()
    }
}
