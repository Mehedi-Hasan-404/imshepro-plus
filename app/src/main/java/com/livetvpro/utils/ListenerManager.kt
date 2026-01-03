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

    init {
        // Initialize immediately when the singleton is created
        initialize()
    }

    fun initialize() {
        scope.launch {
            try {
                Log.d("ListenerManager", "Fetching listener config...")
                val response = apiService.getListenerConfig()
                
                if (response.isSuccessful) {
                    config = response.body()
                    isInitialized = true
                    Log.d("ListenerManager", "Listener config loaded: enabled=${config?.enableDirectLink}, url=${config?.directLinkUrl}")
                    Log.d("ListenerManager", "Allowed pages: ${config?.allowedPages}")
                } else {
                    Log.e("ListenerManager", "Failed to load config: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e("ListenerManager", "Error loading config", e)
            }
        }
    }

    /**
     * Called when user interacts with a page (clicks channel, event, etc.)
     * Returns true if listener was triggered, false otherwise
     */
    fun onPageInteraction(pageId: String): Boolean {
        Log.d("ListenerManager", "onPageInteraction called for: $pageId")
        
        if (!isInitialized) {
            Log.w("ListenerManager", "Not initialized yet")
            return false
        }

        val cfg = config
        if (cfg == null) {
            Log.w("ListenerManager", "No config available")
            return false
        }

        Log.d("ListenerManager", "Config check: enabled=${cfg.enableDirectLink}")
        
        if (!cfg.enableDirectLink) {
            Log.d("ListenerManager", "Direct link disabled in config")
            return false
        }

        if (!cfg.isEnabledForPage(pageId)) {
            Log.d("ListenerManager", "Page '$pageId' not in allowed list: ${cfg.allowedPages}")
            return false
        }

        if (cfg.directLinkUrl.isBlank()) {
            Log.w("ListenerManager", "Direct link URL is empty")
            return false
        }

        // Trigger the listener
        Log.d("ListenerManager", "Triggering listener for page: $pageId with URL: ${cfg.directLinkUrl}")
        openDirectLink(cfg.directLinkUrl)
        return true
    }

    private fun openDirectLink(url: String) {
        try {
            // Open in external browser
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d("ListenerManager", "Successfully opened URL in browser: $url")
        } catch (e: Exception) {
            Log.e("ListenerManager", "Failed to open URL: $url", e)
        }
    }
}

