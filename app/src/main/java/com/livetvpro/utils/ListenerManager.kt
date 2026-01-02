// File: app/src/main/java/com/livetvpro/utils/ListenerManager.kt
package com.livetvpro.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.livetvpro.data.api.ApiService
import com.livetvpro.data.models.ListenerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListenerManager @Inject constructor(
    private val context: Context,
    private val apiService: ApiService
) {

    private var config: ListenerConfig? = null
    private val shownPages = mutableSetOf<String>()

    suspend fun initialize() {
        try {
            Timber.d("🔔 Fetching listener configuration...")
            val response = withContext(Dispatchers.IO) {
                apiService.getListenerConfig()
            }

            if (response.isSuccessful && response.body() != null) {
                config = response.body()
                Timber.d("✅ Listener Config Loaded: Enable=${config?.enableDirectLink}, URL=${config?.directLinkUrl}")
            } else {
                Timber.w("⚠️ Failed to load listener config: ${response.code()}")
                config = ListenerConfig() // Default empty config
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error loading listener configuration")
            config = ListenerConfig()
        }
    }

    fun onPageInteraction(pageId: String): Boolean {
        val currentConfig = config ?: return false

        // 1. Check if enabled for this specific page
        if (!currentConfig.isEnabledForPage(pageId)) {
            return false
        }

        // 2. Check if already shown for this page
        if (shownPages.contains(pageId)) {
            return false
        }

        // 3. Show the ad
        return openBrowser(currentConfig.directLinkUrl, pageId)
    }

    private fun openBrowser(url: String, pageId: String): Boolean {
        try {
            if (url.isBlank()) return false

            Timber.d("🚀 Opening Ad Link: $url")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            shownPages.add(pageId)
            return true
        } catch (e: Exception) {
            Timber.e(e, "❌ Error opening browser")
            return false
        }
    }

    fun reset() {
        shownPages.clear()
    }
}

