package com.livetvpro.data.repository

import com.livetvpro.data.models.Category
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val dataRepository: DataRepository
) {
    suspend fun getCategories(): List<Category> {
        if (!dataRepository.isDataLoaded()) dataRepository.refreshData()
        return dataRepository.getCategories()
    }

    suspend fun getCategoryBySlug(slug: String): Category? {
        return getCategories().find { it.slug == slug }
    }
}

