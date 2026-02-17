package com.livetvpro.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Category
import com.livetvpro.data.repository.CategoryRepository
import com.livetvpro.utils.RetryViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
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
        loadData()
    }

    override fun loadData() {
        viewModelScope.launch {
            try {
                startLoading()
                
                val categories = categoryRepository.getCategories()
                
                _categories.value = categories
                searchCategories(currentSearchQuery)
                
                finishLoading(dataIsEmpty = categories.isEmpty())
            } catch (e: Exception) {
                _categories.value = emptyList()
                _filteredCategories.value = emptyList()
                
                finishLoading(dataIsEmpty = true, error = e)
            }
        }
    }

    // FIXED: Removed the override - this property is final in the parent class
    // If you need custom reload behavior, use a different approach
    // override fun shouldReloadOnResume(): Boolean {
    //     return false
    // }

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
        }
    }
}
