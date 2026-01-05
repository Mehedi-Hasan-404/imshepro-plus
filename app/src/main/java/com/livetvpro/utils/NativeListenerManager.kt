package com.livetvpro.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages direct link redirects based on native configuration
 * 
 * BEHAVIOR:
 * - First time user clicks on a page/section → Opens link, blocks player
 * - All subsequent clicks → Allows normal playback
 */
@Singleton
class NativeListenerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }
    
    // Native methods
    private external fun nativeShouldShowLink(pageType: String, uniqueId: String?): Boolean
    private external fun nativeGetDirectLinkUrl(): String
    private external fun nativeResetSessions()
    private external fun nativeIsConfigValid(): Boolean

    /**
     * Check if should show direct link on page interaction
     * 
     * @param pageType The page being accessed (e.g., "channels", "live_events")
     * @param uniqueId Optional unique identifier (e.g., category ID) for per-item tracking
     * @return TRUE if link was opened (blocks player), FALSE if should allow normal playback
     */
    fun onPageInteraction(pageType: String, uniqueId: String? = null): Boolean {
        return try {
            // Ask native code if we should show the link
            val shouldShow = nativeShouldShowLink(pageType, uniqueId)
            
            if (shouldShow) {
                // Get URL from native code
                val url = nativeGetDirectLinkUrl()
                
                if (url.isNotEmpty()) {
                    Timber.d("🔗 Opening direct link: $url")
                    openDirectLink(url)
                    return true  // Link opened, block the player
                }
            }
            
            // Don't show link, allow normal playback
            Timber.d("✅ Allowing normal playback")
            return false
            
        } catch (e: Exception) {
            Timber.e(e, "Error in onPageInteraction")
            return false  // On error, allow normal playback
        }
    }

    /**
     * Open redirect link in browser
     */
    private fun openDirectLink(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open URL: $url")
        }
    }
    
    /**
     * Reset all session tracking (for testing or user preference)
     */
    fun resetSessions() {
        try {
            nativeResetSessions()
            Timber.d("🔄 All sessions reset")
        } catch (e: Exception) {
            Timber.e(e, "Error resetting sessions")
        }
    }
    
    /**
     * Check if configuration is valid
     */
    fun isConfigValid(): Boolean {
        return try {
            nativeIsConfigValid()
        } catch (e: Exception) {
            Timber.e(e, "Error checking config validity")
            false
        }
    }
}
