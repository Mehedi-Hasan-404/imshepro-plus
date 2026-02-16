package com.livetvpro.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Category
import com.livetvpro.data.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _categories = MutableLiveData<List<Category>>()
    val categories: LiveData<List<Category>> = _categories

    private val _filteredCategories = MutableLiveData<List<Category>>()
    val filteredCategories: LiveData<List<Category>> = _filteredCategories

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var currentSearchQuery = ""

    init {
        Timber.d("HomeViewModel initialized")
        loadCategories()
    }

    fun loadCategories() {
        viewModelScope.launch {
            try {
                Timber.d("Starting to load categories...")
                _isLoading.value = true
                _error.value = null
                
                val categories = categoryRepository.getCategories()
                Timber.d("Loaded ${categories.size} categories from repository")
                
                _categories.value = categories
                searchCategories(currentSearchQuery)
                
                if (categories.isEmpty()) {
                    Timber.w("No categories found in API/Firestore")
                    _error.value = "No categories available.\nPlease check your internet connection."
                } else {
                    Timber.d("Successfully loaded ${categories.size} categories")
                    _error.value = null
                }
            } catch (e: Exception) {
                Timber.e(e, "Error loading categories")
                _error.value = "Failed to load categories: ${e.message}"
                
                _categories.value = emptyList()
                _filteredCategories.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun searchCategories(query: String) {
        try {
            currentSearchQuery = query
            val allCategories = _categories.value ?: emptyList()

            if (query.isBlank()) {
                _filteredCategories.value = allCategories
            } else {
                _filteredCategories.value = allCategories.filter {
                    it.name.contains(query, ignoreCase = true) ||
                    it.slug.contains(query, ignoreCase = true)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error searching categories")
        }
    }

    fun retry() {
        Timber.d("Retry requested")
        _error.value = null
        loadCategories()
    }
}
