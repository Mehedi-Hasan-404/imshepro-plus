package com.livetvpro.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
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

    fun initialize() {
        scope.launch {
            try {
                val response = apiService.getListenerConfig()
                if (response.isSuccessful) {
                    config = response.body()
                    isInitialized = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun onPageInteraction(pageId: String): Boolean {
        if (!isInitialized) return false

        val cfg = config ?: return false

        if (!cfg.enableDirectLink) return false

        if (!cfg.isEnabledForPage(pageId)) return false

        if (cfg.directLinkUrl.isBlank()) return false

        openDirectLink(cfg.directLinkUrl)
        return true
    }

    private fun openDirectLink(url: String) {
        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            
            customTabsIntent.launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }
}

