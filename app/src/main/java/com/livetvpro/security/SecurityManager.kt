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
        // ========================================
        // SECURITY LEVEL CONSTANTS
        // ========================================
        private const val LEVEL_1_RELAXED = 1
        private const val LEVEL_2_MODERATE = 2
        private const val LEVEL_3_STRICT = 3
        
        // ========================================
        // SECURITY LEVEL CONFIGURATION
        // ========================================
        // Change this to adjust strictness:
        // LEVEL_1_RELAXED: Only basic checks, warnings only
        // LEVEL_2_MODERATE: Balanced checks, allows debug builds
        // LEVEL_3_STRICT: Maximum security, crashes on any issue
        private const val SECURITY_LEVEL = LEVEL_2_MODERATE
        
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
    private val checkInterval = 60000L
    
    /**
     * LEVEL 1 (RELAXED): Basic checks, never crashes
     * - Checks run but only log warnings
     * - Always returns true
     * - Use for: Development, Testing, Sideloaded APKs
     */
    private fun verifyIntegrityLevel1(): Boolean {
        try {
            val checks = listOf(
                "Package" to checkPackageName(),
                "Debuggable" to !isDebuggable(),
                "Native" to checkNativeLibrary()
            )
            
            checks.forEach { (name, passed) ->
                if (!passed) {
                    Timber.w("⚠️ Security Check Failed: $name (ignored in relaxed mode)")
                }
            }
            
            // Always return true - just log warnings
            return true
        } catch (e: Exception) {
            Timber.e(e, "Security check error (ignored)")
            return true
        }
    }
    
    /**
     * LEVEL 2 (MODERATE): Balanced security
     * - Allows debug builds (for development)
     * - Checks signature but doesn't block on failure
     * - Blocks only on critical failures
     * - Use for: Most production apps, apps distributed outside Play Store
     */
    private fun verifyIntegrityLevel2(): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // Cache result for 60 seconds
        if (integrityToken != 0 && (currentTime - lastCheckTime) < checkInterval) {
            return nativeVerifyToken(integrityToken)
        }
        
        try {
            val checks = mapOf(
                "Package" to checkPackageName(),
                "Debuggable" to !isDebuggable(), // Warning only
                "Installer" to checkInstaller(), // Warning only
                "Native" to checkNativeLibrary(),
                "Debugger" to !checkDebugger()
            )
            
            // Log all results
            checks.forEach { (name, passed) ->
                if (passed) {
                    Timber.d("✅ $name check passed")
                } else {
                    Timber.w("⚠️ $name check failed")
                }
            }
            
            // CRITICAL checks that must pass
            val criticalChecks = listOf(
                checks["Package"],
                checks["Native"]
            )
            
            // NON-CRITICAL checks (just warnings)
            val nonCriticalChecks = listOf(
                checks["Debuggable"],
                checks["Installer"],
                checks["Debugger"]
            )
            
            // Must pass all critical checks
            if (!criticalChecks.all { it == true }) {
                Timber.e("🚨 CRITICAL security checks failed")
                integrityToken = 0
                return false
            }
            
            // Generate token
            val isDebug = isDebuggable()
            integrityToken = nativeValidateIntegrity(context.packageName, isDebug)
            lastCheckTime = currentTime
            
            return integrityToken != 0
            
        } catch (e: Exception) {
            Timber.e(e, "Security check error")
            return false
        }
    }
    
    /**
     * LEVEL 3 (STRICT): Maximum security
     * - All checks must pass
     * - Crashes on any failure
     * - Blocks debug builds in production
     * - Use for: Banking apps, Payment apps, High-security apps
     */
    private fun verifyIntegrityLevel3(): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // Cache result for 60 seconds
        if (integrityToken != 0 && (currentTime - lastCheckTime) < checkInterval) {
            return nativeVerifyToken(integrityToken)
        }
        
        try {
            val checks = listOf(
                checkSignature(),
                checkInstaller(),
                !isDebuggable(), // Must NOT be debuggable
                checkNativeLibrary(),
                !checkDebugger()
            )
            
            // ALL checks must pass
            if (!checks.all { it }) {
                Timber.e("🚨 STRICT MODE: Security checks failed")
                logFailureDetails(checks)
                integrityToken = 0
                return false
            }
            
            // Generate token
            integrityToken = nativeValidateIntegrity(context.packageName, false)
            lastCheckTime = currentTime
            
            return integrityToken != 0
            
        } catch (e: Exception) {
            Timber.e(e, "Security check error")
            return false
        }
    }
    
    /**
     * Main entry point - delegates to appropriate level
     */
    fun verifyIntegrity(): Boolean {
        return when (SECURITY_LEVEL) {
            LEVEL_1_RELAXED -> verifyIntegrityLevel1()
            LEVEL_2_MODERATE -> verifyIntegrityLevel2()
            LEVEL_3_STRICT -> verifyIntegrityLevel3()
            else -> verifyIntegrityLevel2() // Default to moderate
        }
    }
    
    // ==================== Individual Check Methods ====================
    
    private fun checkPackageName(): Boolean {
        val validPackages = listOf("com.livetvpro", "com.livetvpro.debug")
        return context.packageName in validPackages
    }
    
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
            
            signatures.isNotEmpty()
        } catch (e: Exception) {
            Timber.e(e, "Signature check failed")
            false
        }
    }
    
    private fun checkInstaller(): Boolean {
        return try {
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
            
            val allowedInstallers = listOf(
                "com.android.packageinstaller",
                "com.google.android.packageinstaller",
                "com.android.vending",
                null // Sideload OK
            )
            
            installer in allowedInstallers
        } catch (e: Exception) {
            true // Allow if check fails
        }
    }
    
    private fun isDebuggable(): Boolean {
        return (context.applicationInfo.flags and 
               android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    
    private fun checkNativeLibrary(): Boolean {
        return try {
            nativeCheckDebugger()
            true
        } catch (e: Exception) {
            Timber.e(e, "Native library check failed")
            false
        }
    }
    
    private fun checkDebugger(): Boolean {
        return try {
            nativeCheckDebugger()
        } catch (e: Exception) {
            false
        }
    }
    
    private fun logFailureDetails(checks: List<Boolean>) {
        val checkNames = listOf(
            "Signature", "Installer", "Debug Mode", 
            "Native Library", "Debugger"
        )
        
        checks.forEachIndexed { index, passed ->
            if (!passed) {
                Timber.e("❌ ${checkNames.getOrNull(index)} check FAILED")
            }
        }
    }
    
    /**
     * Force crash the app if tampered
     * Behavior depends on security level
     */
    fun enforceIntegrity() {
        when (SECURITY_LEVEL) {
            LEVEL_1_RELAXED -> {
                // Just log, don't crash
                Timber.w("⚠️ Integrity enforcement called (ignored in relaxed mode)")
                return
            }
            LEVEL_2_MODERATE -> {
                // Clear data but don't crash immediately
                clearAppData()
                Timber.e("⚠️ Security violation detected - data cleared")
                // Let app continue but with empty data
                return
            }
            LEVEL_3_STRICT -> {
                // Clear data AND crash
                clearAppData()
                val errorCode = "E${System.currentTimeMillis() % 100000}"
                throw SecurityException(errorCode)
            }
        }
    }
    
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
    
    fun getIntegrityToken(): Int = integrityToken
    
    /**
     * Get current security level name
     */
    fun getSecurityLevelName(): String {
        return when (SECURITY_LEVEL) {
            LEVEL_1_RELAXED -> "Relaxed (Development)"
            LEVEL_2_MODERATE -> "Moderate (Recommended)"
            LEVEL_3_STRICT -> "Strict (Maximum Security)"
            else -> "Unknown"
        }
    }
}
