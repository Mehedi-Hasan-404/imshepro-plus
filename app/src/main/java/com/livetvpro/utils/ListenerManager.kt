package com.livetvpro.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.livetvpro.data.repository.DataRepository
import com.livetvpro.security.SecurityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListenerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataRepository: DataRepository,
    private val securityManager: SecurityManager // ADDED
) {
    private val triggeredSessions = mutableSetOf<String>()
    
    // Obfuscated flag to detect tampering
    private var isSystemValid = true

    /**
     * PROTECTED: Check integrity before allowing page interaction
     */
    fun onPageInteraction(pageType: String, uniqueId: String? = null): Boolean {
        // CRITICAL: Verify app integrity first
        if (!verifySystemIntegrity()) {
            Log.e("ListenerManager", "🚨 System integrity compromised")
            securityManager.enforceIntegrity() // Crash app
            return true // Block interaction
        }
        
        // Get config (which also checks integrity internally)
        val cfg = dataRepository.getListenerConfig()

        if (!cfg.enableDirectLink) return false
        if (!cfg.isEnabledForPage(pageType)) return false
        if (cfg.directLinkUrl.isBlank()) return false

        val sessionKey = if (uniqueId != null) "${pageType}_$uniqueId" else pageType

        if (triggeredSessions.contains(sessionKey)) {
            return false
        }

        // Final integrity check before opening link
        if (!securityManager.verifyIntegrity()) {
            securityManager.enforceIntegrity()
            return true
        }

        openDirectLink(cfg.directLinkUrl)
        triggeredSessions.add(sessionKey)
        return true
    }

    /**
     * Verify system integrity with obfuscated logic
     */
    private fun verifySystemIntegrity(): Boolean {
        // Check if class has been modified
        val stackTrace = Thread.currentThread().stackTrace
        val callerClass = stackTrace.getOrNull(3)?.className
        
        // Verify caller is legitimate
        if (callerClass == null || !callerClass.startsWith("com.livetvpro")) {
            isSystemValid = false
            return false
        }
        
        // Check if security manager is bypassed
        if (!securityManager.verifyIntegrity()) {
            isSystemValid = false
            return false
        }
        
        return isSystemValid
    }

    /**
     * PROTECTED: Open link only if integrity passes
     */
    private fun openDirectLink(url: String) {
        try {
            // Final check before opening
            if (!securityManager.verifyIntegrity()) {
                securityManager.enforceIntegrity()
                return
            }
            
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("ListenerManager", "Failed to open URL", e)
        }
    }
    
    /**
     * Clear session (can be called when user returns from ad)
     */
    fun resetSessions() {
        // Verify integrity before allowing reset
        if (!securityManager.verifyIntegrity()) {
            securityManager.enforceIntegrity()
            return
        }
        triggeredSessions.clear()
    }
}
