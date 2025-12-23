package com.livetvpro.data.repository

import com.livetvpro.data.api.ApiService
import com.livetvpro.data.models.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val apiService: ApiService
) {
    suspend fun getCategories(): List<Category> = withContext(Dispatchers.IO) {
        try {
            Timber.d("Fetching categories from API...")
            val response = apiService.getCategories()
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true && body.data != null) {
                    Timber.d("Successfully loaded ${body.data.size} categories")
                    return@withContext body.data.sortedBy { it.order }
                }
            }
            
            Timber.e("Failed to load categories: ${response.message()}")
            emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Error loading categories from API")
            emptyList()
        }
    }

    suspend fun getCategoryBySlug(slug: String): Category? = withContext(Dispatchers.IO) {
        try {
            val categories = getCategories()
            categories.firstOrNull { it.slug == slug }
        } catch (e: Exception) {
            Timber.e(e, "Error loading category by slug: $slug")
            null
        }
    }
}

