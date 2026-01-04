package com.livetvpro.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.livetvpro.data.repository.DataRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListenerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataRepository: DataRepository
) {
    private val triggeredSessions = mutableSetOf<String>()

    fun onPageInteraction(pageType: String, uniqueId: String? = null): Boolean {
        // Config is pre-loaded in DataRepository
        val cfg = dataRepository.getListenerConfig()

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

