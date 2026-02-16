package com.livetvpro.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Category
import com.livetvpro.data.repository.CategoryRepository
import com.livetvpro.utils.RetryViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository
) : RetryViewModel() {

    private val _categories = MutableLiveData<List<Category>>()
    val categories: LiveData<List<Category>> = _categories

    private val _filteredCategories = MutableLiveData<List<Category>>()
    val filteredCategories: LiveData<List<Category>> = _filteredCategories

    private var currentSearchQuery = ""

    init {
        Timber.d("HomeViewModel initialized")
        loadData()
    }

    override fun loadData() {
        viewModelScope.launch {
            try {
                Timber.d("Starting to load categories...")
                startLoading()
                
                val categories = categoryRepository.getCategories()
                Timber.d("Loaded ${categories.size} categories from repository")
                
                _categories.value = categories
                searchCategories(currentSearchQuery)
                
                // Notify RetryViewModel about the result
                finishLoading(dataIsEmpty = categories.isEmpty())
                
                if (categories.isEmpty()) {
                    Timber.w("No categories found")
                } else {
                    Timber.d("Successfully loaded ${categories.size} categories")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error loading categories")
                _categories.value = emptyList()
                _filteredCategories.value = emptyList()
                
                // Notify RetryViewModel about the error
                finishLoading(dataIsEmpty = true, error = e)
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
}
