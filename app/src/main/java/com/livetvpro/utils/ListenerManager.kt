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

            if (response.isSuccessful) {
                config = response.body()
                Timber.d("✅ Listener config loaded successfully")
            } else {
                Timber.w("⚠️ Failed to load listener config: ${response.code()}")
                config = ListenerConfig()
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error loading listener configuration")
            config = ListenerConfig()
        }
    }

    fun onPageInteraction(pageId: String): Boolean {
        val cfg = config ?: run {
            Timber.d("⏭️ No config loaded yet for page: $pageId")
            return false
        }

        if (!cfg.isEnabledForPage(pageId)) {
            Timber.d("⏭️ Listener not enabled for page: $pageId")
            return false
        }

        if (shownPages.contains(pageId)) {
            Timber.d("⏭️ Listener already shown for page: $pageId")
            return false
        }

        return showListener(cfg.directLinkUrl, pageId)
    }

    private fun showListener(url: String, pageId: String): Boolean {
        try {
            if (url.isBlank()) {
                Timber.w("⚠️ Listener URL is blank")
                return false
            }

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(intent)

            shownPages.add(pageId)
            Timber.d("✅ Listener opened for page: $pageId")
            return true
        } catch (e: Exception) {
            Timber.e(e, "❌ Error showing listener")
            return false
        }
    }

    fun reset() {
        shownPages.clear()
        Timber.d("🔄 Listener tracking reset")
    }

    fun isEnabledForPage(pageId: String): Boolean {
        return config?.isEnabledForPage(pageId) ?: false
    }

    fun wasShownForPage(pageId: String): Boolean {
        return shownPages.contains(pageId)
    }
}
