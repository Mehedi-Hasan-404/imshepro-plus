package com.livetvpro.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.livetvpro.data.models.Category
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    suspend fun getCategories(): List<Category> {
        return try {
            firestore.collection("categories")
                .get()
                .await()
                .documents
                .mapNotNull { it.toObject(Category::class.java)?.copy(id = it.id) }
                .sortedBy { it.order }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getCategoryBySlug(slug: String): Category? {
        return try {
            val snapshot = firestore.collection("categories")
                .whereEqualTo("slug", slug)
                .get()
                .await()
            val doc = snapshot.documents.firstOrNull() ?: return null
            doc.toObject(Category::class.java)?.copy(id = doc.id)
        } catch (e: Exception) {
            null
        }
    }
}
