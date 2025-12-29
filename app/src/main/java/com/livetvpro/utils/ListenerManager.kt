// app/src/main/java/com/livetvpro/utils/ListenerManager.kt
package com.livetvpro.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.livetvpro.data.api.ListenerService
import com.livetvpro.data.models.ListenerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages direct-link listener (interstitial ad) functionality
 * Opens URL automatically on FIRST interaction per page (like an interstitial ad)
 */
@Singleton
class ListenerManager @Inject constructor(
    private val context: Context,
    private val listenerService: ListenerService
) {
    private var config: ListenerConfig? = null
    private val shownPages = mutableSetOf<String>()
    
    /**
     * Initialize by fetching listener configuration from API
     * Should be called once on app startup
     */
    suspend fun initialize() {
        try {
            Timber.d("🔔 Fetching listener configuration from /public/listener-config...")
            val response = withContext(Dispatchers.IO) {
                listenerService.getListenerConfig()
            }
            
            if (response.isSuccessful) {
                config = response.body()
                Timber.d("✅ Listener config loaded:")
                Timber.d("   - Enabled: ${config?.enableDirectLink}")
                Timber.d("   - URL: ${config?.directLinkUrl}")
                Timber.d("   - Pages: ${config?.allowedPages?.joinToString(", ")}")
            } else {
                Timber.w("⚠️ Failed to load listener config: ${response.code()} ${response.message()}")
                config = ListenerConfig() // Use default (disabled)
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error loading listener configuration")
            config = ListenerConfig() // Use default (disabled)
        }
    }
    
    /**
     * INTERSTITIAL AD BEHAVIOR:
     * Automatically opens direct link on FIRST interaction with an allowed page
     * Returns true if listener was shown, false otherwise
     */
    fun onPageInteraction(pageId: String): Boolean {
        val cfg = config ?: run {
            Timber.d("⏭️ No config loaded yet for page: $pageId")
            return false
        }
        
        // Check if this page is eligible for listeners
        if (!cfg.isEnabledForPage(pageId)) {
            Timber.d("⏭️ Listener not enabled for page: $pageId")
            return false
        }
        
        // Check if we've already shown listener for this page (ONE TIME PER PAGE)
        if (shownPages.contains(pageId)) {
            Timber.d("⏭️ Listener already shown for page: $pageId")
            return false
        }
        
        // AUTOMATICALLY OPEN THE LINK (Like interstitial ad)
        return showListener(cfg.directLinkUrl, pageId)
    }
    
    /**
     * Show listener by opening URL in browser (INTERSTITIAL AD STYLE)
     */
    private fun showListener(url: String, pageId: String): Boolean {
        try {
            if (url.isBlank()) {
                Timber.w("⚠️ Listener URL is blank")
                return false
            }
            
            Timber.d("╔═══════════════════════════════════════════════════════╗")
            Timber.d("   🔔 OPENING DIRECT LINK (INTERSTITIAL AD)")
            Timber.d("   Page: $pageId")
            Timber.d("   URL: ${url.take(60)}...")
            Timber.d("╚═══════════════════════════════════════════════════════╝")
            
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            
            context.startActivity(intent)
            
            // Mark this page as shown (ONCE PER SESSION)
            shownPages.add(pageId)
            
            Timber.d("✅ Listener opened successfully for page: $pageId")
            return true
        } catch (e: Exception) {
            Timber.e(e, "❌ Error showing listener")
            return false
        }
    }
    
    /**
     * Reset shown pages (e.g., on app restart or config change)
     */
    fun reset() {
        shownPages.clear()
        Timber.d("🔄 Listener tracking reset")
    }
    
    /**
     * Check if listener is enabled for a specific page
     */
    fun isEnabledForPage(pageId: String): Boolean {
        return config?.isEnabledForPage(pageId) ?: false
    }
    
    /**
     * Check if listener was already shown for a page
     */
    fun wasShownForPage(pageId: String): Boolean {
        return shownPages.contains(pageId)
    }
}
