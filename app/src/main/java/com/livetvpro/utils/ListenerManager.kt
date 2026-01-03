// File: app/src/main/java/com/livetvpro/utils/ListenerManager.kt
package com.livetvpro.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.livetvpro.data.api.ApiService
import com.livetvpro.data.models.ListenerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListenerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService
) {
    private var config: ListenerConfig? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isInitialized = false

    // CHANGED: Use a Set to track specific sessions (e.g., "category_sports", "favorites", etc.)
    private val triggeredSessions = mutableSetOf<String>()

    init {
        initialize()
    }

    fun initialize() {
        scope.launch {
            try {
                val response = apiService.getListenerConfig()
                if (response.isSuccessful) {
                    config = response.body()
                    isInitialized = true
                }
            } catch (e: Exception) {
                Log.e("ListenerManager", "Error loading config", e)
            }
        }
    }

    /**
     * Checks if ad should be shown for a specific context.
     * @param pageType The type of page (channels, favorites, etc.)
     * @param uniqueId Optional ID to make the session unique (e.g., Category ID)
     */
    fun onPageInteraction(pageType: String, uniqueId: String? = null): Boolean {
        if (!isInitialized || config == null) return false

        val cfg = config!!
        
        // 1. Check if feature is enabled globally and for this page type
        if (!cfg.enableDirectLink) return false
        if (!cfg.isEnabledForPage(pageType)) return false
        if (cfg.directLinkUrl.isBlank()) return false

        // 2. Construct a unique key for this session
        // If uniqueId is provided (e.g. Category ID), key is "channels_123"
        // If no uniqueId (Favorites), key is just "favorites"
        val sessionKey = if (uniqueId != null) "${pageType}_$uniqueId" else pageType

        // 3. Check if this specific session has already triggered an ad
        if (triggeredSessions.contains(sessionKey)) {
            Log.d("ListenerManager", "Ad already shown for session: $sessionKey")
            return false
        }

        // 4. Trigger the Ad
        openDirectLink(cfg.directLinkUrl)
        
        // 5. Mark this specific session as triggered
        triggeredSessions.add(sessionKey)
        Log.d("ListenerManager", "Ad triggered and locked for session: $sessionKey")
        
        return true
    }

    private fun openDirectLink(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("ListenerManager", "Failed to open URL", e)
        }
    }
}

