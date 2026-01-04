// app/src/main/java/com/livetvpro/utils/ListenerManager.kt
package com.livetvpro.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.livetvpro.data.api.ApiService
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var config: com.livetvpro.data.models.ListenerConfig? = null
    private var isInitialized = false
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

    fun onPageInteraction(pageType: String, uniqueId: String? = null): Boolean {
        if (!isInitialized || config == null) return false

        val cfg = config!!
        
        if (!cfg.enableDirectLink) return false
        if (!cfg.isEnabledForPage(pageType)) return false
        if (cfg.directLinkUrl.isBlank()) return false

        val sessionKey = if (uniqueId != null) "${pageType}_$uniqueId" else pageType

        if (triggeredSessions.contains(sessionKey)) {
            return false
        }

        openDirectLink(cfg.directLinkUrl)
        triggeredSessions.add(sessionKey)
        
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
