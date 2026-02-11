package com.livetvpro.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.livetvpro.data.models.Channel
import com.livetvpro.ui.player.FloatingPlayerService

/**
 * FloatingPlayerHelper
 * 
 * Utility object for managing floating player functionality
 * 
 * UPDATED VERSION with linkIndex support for multiple stream qualities
 */
object FloatingPlayerHelper {
    
    /**
     * Check if the app has overlay permission (required for floating player)
     * 
     * @param context Android context
     * @return true if permission is granted, false otherwise
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            // Below Android M, overlay permission is granted by default
            true
        }
    }
    
    /**
     * Request overlay permission from the user
     * 
     * Opens the system settings screen where user can grant permission
     */
    fun requestOverlayPermission(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
    
    /**
     * Launch the floating player with the specified channel and link
     * 
     * 🔥 UPDATED: Now accepts linkIndex parameter to support multiple stream qualities
     * 
     * @param context Android context
     * @param channel Channel object containing name, logo, and links
     * @param linkIndex Index of the link to play (default: 0 for first link)
     * 
     * Example usage:
     * ```
     * // Play first link
     * FloatingPlayerHelper.launchFloatingPlayer(context, channel)
     * 
     * // Play second link (index 1)
     * FloatingPlayerHelper.launchFloatingPlayer(context, channel, 1)
     * ```
     */
    fun launchFloatingPlayer(context: Context, channel: Channel, linkIndex: Int = 0) {
        android.util.Log.e("FloatingPlayerHelper", "========================================")
        android.util.Log.e("FloatingPlayerHelper", "Launching floating player")
        android.util.Log.e("FloatingPlayerHelper", "Channel: ${channel.name}")
        android.util.Log.e("FloatingPlayerHelper", "Channel ID: ${channel.id}")
        android.util.Log.e("FloatingPlayerHelper", "Link index: $linkIndex")
        android.util.Log.e("FloatingPlayerHelper", "Total links: ${channel.links?.size ?: 0}")
        
        // Validate overlay permission
        if (!hasOverlayPermission(context)) {
            android.util.Log.e("FloatingPlayerHelper", "ERROR: No overlay permission!")
            android.widget.Toast.makeText(
                context,
                "Overlay permission required for floating player",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        
        // Validate channel has links
        if (channel.links.isNullOrEmpty()) {
            android.util.Log.e("FloatingPlayerHelper", "ERROR: Channel has no links!")
            android.widget.Toast.makeText(
                context,
                "No stream available for ${channel.name}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        
        // Validate linkIndex is within bounds
        if (linkIndex !in channel.links.indices) {
            android.util.Log.e("FloatingPlayerHelper", "WARNING: Invalid linkIndex $linkIndex, using 0")
            android.widget.Toast.makeText(
                context,
                "Invalid stream selection, using first available",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        
        // Log selected link details
        val selectedLink = if (linkIndex in channel.links.indices) {
            channel.links[linkIndex]
        } else {
            channel.links[0]
        }
        
        android.util.Log.e("FloatingPlayerHelper", "Selected link quality: ${selectedLink.quality}")
        android.util.Log.e("FloatingPlayerHelper", "Selected link URL: ${selectedLink.url}")
        
        // 🔥 UPDATED: Use FloatingPlayerService.start() method with linkIndex
        try {
            FloatingPlayerService.start(
                context = context,
                channel = channel,
                linkIndex = linkIndex,
                playbackPosition = 0L
            )
            android.util.Log.e("FloatingPlayerHelper", "Floating player service started successfully")
        } catch (e: Exception) {
            android.util.Log.e("FloatingPlayerHelper", "ERROR starting floating player service", e)
            android.widget.Toast.makeText(
                context,
                "Failed to start floating player: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        
        android.util.Log.e("FloatingPlayerHelper", "========================================")
    }
}

/**
 * SUMMARY OF CHANGES:
 * ===================
 * 
 * 1. Added linkIndex parameter to launchFloatingPlayer() with default value 0
 * 2. Added validation for channel.links being null or empty
 * 3. Added validation for linkIndex being within valid range
 * 4. Updated to call FloatingPlayerService.start() with linkIndex
 * 5. Added extensive logging for debugging
 * 6. Added error handling with user-friendly toast messages
 * 7. Added helper method requestOverlayPermission() for convenience
 * 8. Added comprehensive documentation
 */
