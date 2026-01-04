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
    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }
    
    // All logic is in native code
    private external fun nativeShouldShowLink(pageType: String, uniqueId: String?): Boolean
    private external fun nativeGetDirectLinkUrl(): String
    private external fun nativeResetSessions()
    private external fun nativeIsConfigValid(): Boolean

    /**
     * Check if direct link should be shown
     * ALL LOGIC IS IN NATIVE CODE - NO KOTLIN LOGIC HERE
     */
    fun onPageInteraction(pageType: String, uniqueId: String? = null): Boolean {
        // Ask native code if we should show the link
        val shouldShow = nativeShouldShowLink(pageType, uniqueId)
        
        if (shouldShow) {
            // Get URL from native code
            val url = nativeGetDirectLinkUrl()
            
            if (url.isNotEmpty()) {
                openDirectLink(url)
                return true
            }
        }
        
        return false
    }

    /**
     * Open redirect link
     */
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
    
    /**
     * Reset session tracking (calls native code)
     */
    fun resetSessions() {
        nativeResetSessions()
    }
    
    /**
     * Check if configuration is valid (asks native code)
     */
    fun isConfigValid(): Boolean {
        return nativeIsConfigValid()
    }
}
