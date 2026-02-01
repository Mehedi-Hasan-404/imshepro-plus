package com.livetvpro.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * OrientationHelper - Utility for handling orientation and device type detection
 * 
 * This helper class provides utilities for:
 * - Detecting device architecture (x86, ARM)
 * - Determining optimal player activity based on orientation
 * - Checking screen size categories
 */
object OrientationHelper {

    /**
     * Check if device is x86 architecture
     * x86 devices (emulators, Chromebooks, some Android TV) benefit from landscape player
     */
    fun isX86Device(): Boolean {
        val abi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS[0]
        } else {
            @Suppress("DEPRECATION")
            Build.CPU_ABI
        }
        return abi.contains("x86")
    }

    /**
     * Check if device is a tablet based on screen size
     */
    fun isTablet(context: Context): Boolean {
        val configuration = context.resources.configuration
        val screenLayout = configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
        return screenLayout >= Configuration.SCREENLAYOUT_SIZE_LARGE
    }

    /**
     * Check if device is currently in landscape orientation
     */
    fun isLandscape(context: Context): Boolean {
        return context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    /**
     * Check if device is currently in portrait orientation
     */
    fun isPortrait(context: Context): Boolean {
        return context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    /**
     * Determine if LandscapePlayerActivity should be used based on device and orientation
     * 
     * Returns true if:
     * - Device is x86 AND currently in landscape
     * - Device is tablet AND currently in landscape
     * - Any device switching from portrait to landscape
     */
    fun shouldUseLandscapePlayer(context: Context): Boolean {
        return isLandscape(context) && (isX86Device() || isTablet(context))
    }

    /**
     * Get screen width in dp
     */
    fun getScreenWidthDp(context: Context): Int {
        return context.resources.configuration.screenWidthDp
    }

    /**
     * Get screen height in dp
     */
    fun getScreenHeightDp(context: Context): Int {
        return context.resources.configuration.screenHeightDp
    }

    /**
     * Check if device has a large screen (sw >= 600dp)
     * Used for determining if we should show more UI elements
     */
    fun hasLargeScreen(context: Context): Boolean {
        return context.resources.configuration.smallestScreenWidthDp >= 600
    }

    /**
     * Get device type description for logging/debugging
     */
    fun getDeviceDescription(): String {
        val abi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.joinToString(", ")
        } else {
            @Suppress("DEPRECATION")
            Build.CPU_ABI
        }
        
        return """
            Device: ${Build.MANUFACTURER} ${Build.MODEL}
            Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            ABI: $abi
            Architecture: ${if (isX86Device()) "x86" else "ARM"}
        """.trimIndent()
    }

    /**
     * Log current orientation state for debugging
     */
    fun logOrientationState(context: Context, tag: String = "OrientationHelper") {
        android.util.Log.d(tag, """
            Orientation State:
            - Current: ${if (isLandscape(context)) "Landscape" else "Portrait"}
            - Is Tablet: ${isTablet(context)}
            - Is x86: ${isX86Device()}
            - Screen Width: ${getScreenWidthDp(context)}dp
            - Screen Height: ${getScreenHeightDp(context)}dp
            - Should use landscape player: ${shouldUseLandscapePlayer(context)}
        """.trimIndent())
    }
}
