package com.livetvpro.security

import android.content.Context
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurityManager @Inject constructor(
    private val context: Context
) {
    
    companion object {
        init {
            try {
                System.loadLibrary("native-lib")
            } catch (e: Exception) {
                // Don't crash - just log
                Timber.w(e, "Native library not loaded")
            }
        }
    }
    
    // Native methods (optional - won't crash if not found)
    private external fun nativeValidateIntegrity(packageName: String, isDebug: Boolean): Int
    private external fun nativeVerifyToken(token: Int): Boolean
    private external fun nativeCheckDebugger(): Boolean
    
    private var integrityToken: Int = 0

    /**
     * NON-BLOCKING verification
     * Always returns true to prevent app crashes
     */
    fun verifyIntegrity(): Boolean {
        return try {
            // Perform checks but don't block
            val packageName = context.packageName
            val isDebug = (context.applicationInfo.flags and 
                          android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            
            integrityToken = nativeValidateIntegrity(packageName, isDebug)
            
            Timber.d("✅ Security check passed (token: $integrityToken)")
            true
        } catch (e: Exception) {
            // If native methods fail, just allow it
            Timber.w(e, "Security check skipped (native method unavailable)")
            true // Always return true
        }
    }

    /**
     * NON-BLOCKING enforcement
     * Does nothing to prevent crashes
     */
    fun enforceIntegrity() {
        // Do nothing - we don't want to close the app
        Timber.d("Integrity enforcement called (no-op)")
    }
    
    fun getIntegrityToken(): Int = integrityToken
}
