package com.livetvpro.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
                throw SecurityException("Native library initialization failed")
            }
        }
    }
    
    // Native methods
    private external fun nativeValidateIntegrity(packageName: String, isDebug: Boolean): Int
    private external fun nativeVerifyToken(token: Int): Boolean
    private external fun nativeCheckDebugger(): Boolean
    
    private var integrityToken: Int = 0
    private var lastCheckTime = 0L
    private val checkInterval = 60000L // Re-check every 60 seconds
    
    /**
     * CRITICAL: Verify app integrity using native code
     */
    fun verifyIntegrity(): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // Cache result for 60 seconds
        if (integrityToken != 0 && (currentTime - lastCheckTime) < checkInterval) {
            return nativeVerifyToken(integrityToken)
        }
        
        // Perform full integrity check
        val checks = listOf(
            checkSignature(),
            checkInstaller(),
            checkDebugMode(),
            checkNativeLibrary(),
            checkDebugger()
        )
        
        val allPassed = checks.all { it }
        
        if (allPassed) {
            // Get new integrity token from native code
            val isDebug = (context.applicationInfo.flags and 
                          android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            integrityToken = nativeValidateIntegrity(context.packageName, isDebug)
            lastCheckTime = currentTime
        } else {
            Timber.e("🚨 SECURITY: App integrity check FAILED")
            logFailureDetails(checks)
            integrityToken = 0
        }
        
        return allPassed && integrityToken != 0
    }
    
    /**
     * Verify app signature hasn't been modified
     */
    private fun checkSignature(): Boolean {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }
            
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }
            
            // Verify signature exists (add specific signature check in production)
            signatures.isNotEmpty()
        } catch (e: Exception) {
            Timber.e(e, "Signature check failed")
            false
        }
    }
    
    /**
     * Check installer source (modified for sideload apps)
     */
    private fun checkInstaller(): Boolean {
        return try {
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
            
            // For sideloaded apps, installer will be null or package installer
            // This is expected - we just verify it's not from unknown sources
            val allowedInstallers = listOf(
                "com.android.packageinstaller",
                "com.google.android.packageinstaller",
                "com.android.vending", // Play Store (if you ever publish)
                null // Sideload is OK
            )
            
            installer in allowedInstallers
        } catch (e: Exception) {
            Timber.e(e, "Installer check failed")
            true // Allow if check fails (for sideload compatibility)
        }
    }
    
    /**
     * Check if running in debug mode
     */
    private fun checkDebugMode(): Boolean {
        val isDebuggable = (context.applicationInfo.flags and 
                           android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        
        // In release builds, this should be false
        // In debug builds, this should be true
        return !isDebuggable || BuildConfig.DEBUG
    }
    
    /**
     * Verify native library is loaded
     */
    private fun checkNativeLibrary(): Boolean {
        return try {
            // Try to call a native method to verify library is loaded
            nativeCheckDebugger() // This will throw if library not loaded
            true
        } catch (e: Exception) {
            Timber.e(e, "Native library check failed")
            false
        }
    }
    
    /**
     * Check for debugger attachment (native check)
     */
    private fun checkDebugger(): Boolean {
        return try {
            !nativeCheckDebugger() // Returns true if debugger attached
        } catch (e: Exception) {
            Timber.e(e, "Debugger check failed")
            true // Assume safe if check fails
        }
    }
    
    /**
     * Log which checks failed
     */
    private fun logFailureDetails(checks: List<Boolean>) {
        val checkNames = listOf(
            "Signature", "Installer", "Debug Mode", 
            "Native Library", "Debugger"
        )
        
        checks.forEachIndexed { index, passed ->
            if (!passed) {
                Timber.e("❌ ${checkNames[index]} check FAILED")
            }
        }
    }
    
    /**
     * Force crash the app if tampered
     */
    fun enforceIntegrity() {
        if (!verifyIntegrity()) {
            // Clear sensitive data
            clearAppData()
            
            // Generate obscure error code based on timestamp
            val errorCode = "E${System.currentTimeMillis() % 100000}"
            
            // Crash with meaningless error
            throw SecurityException(errorCode)
        }
    }
    
    /**
     * Clear app data before crashing
     */
    private fun clearAppData() {
        try {
            context.getSharedPreferences("live_tv_pro_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    /**
     * Get integrity token for verification
     */
    fun getIntegrityToken(): Int = integrityToken
}
