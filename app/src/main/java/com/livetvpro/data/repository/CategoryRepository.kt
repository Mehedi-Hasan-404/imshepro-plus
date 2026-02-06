package com.livetvpro.data.repository

import com.livetvpro.data.models.Category
import com.livetvpro.data.models.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val dataRepository: NativeDataRepository
) {
    suspend fun getCategories(): List<Category> {
        if (!dataRepository.isDataLoaded()) {
            // Data not loaded yet - return empty
            return emptyList()
        }
        return dataRepository.getCategories()
    }

    suspend fun getCategoryBySlug(slug: String): Category? {
        return getCategories().find { it.slug == slug }
    }

    fun getSports(): Flow<List<Channel>> = flow {
        if (!dataRepository.isDataLoaded()) {
            emit(emptyList())
        } else {
            emit(dataRepository.getSports())
        }
    }
}
