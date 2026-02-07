package com.livetvpro.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.ui.player.FloatingPlayerActivity

/**
 * FloatingPlayerHelper
 * Utility class to manage floating player functionality
 */
object FloatingPlayerHelper {
    
    /**
     * Start floating player with stream
     * 
     * @param context Application context
     * @param streamUrl URL of the stream to play
     * @param streamTitle Title of the stream
     * @param preferencesManager PreferencesManager instance
     */
    fun startFloatingPlayer(
        context: Context,
        streamUrl: String,
        streamTitle: String,
        preferencesManager: PreferencesManager
    ) {
        // Check if floating player is enabled
        if (!preferencesManager.isFloatingPlayerEnabled()) {
            Toast.makeText(
                context,
                "Floating Player is disabled. Enable it from sidebar menu.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        
        // Check overlay permission for Android M and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(
                    context,
                    "Please grant overlay permission for Floating Player",
                    Toast.LENGTH_SHORT
                ).show()
                
                // Open permission settings
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
        }
        
        // Check max floating windows
        val maxWindows = preferencesManager.getMaxFloatingWindows()
        if (maxWindows == 0) {
            Toast.makeText(
                context,
                "Multiple floating windows disabled. Enable from settings.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        
        // Start floating player activity
        FloatingPlayerActivity.start(context, streamUrl, streamTitle)
        
        Toast.makeText(
            context,
            "Opening in Floating Player...",
            Toast.LENGTH_SHORT
        ).show()
    }
    
    /**
     * Check if device supports floating player (overlay permission)
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // Pre-M devices don't need permission
        }
    }
    
    /**
     * Request overlay permission
     */
    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
}
