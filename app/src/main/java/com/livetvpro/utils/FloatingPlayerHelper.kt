package com.livetvpro.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.livetvpro.data.models.Channel
import com.livetvpro.ui.player.FloatingPlayerService

object FloatingPlayerHelper {

    fun launchFloatingPlayer(context: Context, channel: Channel, playbackPosition: Long = 0L) {
        android.util.Log.e("DEBUG_FLOATING_HELPER", "========================================")
        android.util.Log.e("DEBUG_FLOATING_HELPER", "launchFloatingPlayer() CALLED")
        android.util.Log.e("DEBUG_FLOATING_HELPER", "========================================")
        android.util.Log.e("DEBUG_FLOATING_HELPER", "Context type: ${context.javaClass.simpleName}")
        android.util.Log.e("DEBUG_FLOATING_HELPER", "Channel name: ${channel.name}")
        android.util.Log.e("DEBUG_FLOATING_HELPER", "Channel ID: ${channel.id}")
        android.util.Log.e("DEBUG_FLOATING_HELPER", "Channel links: ${channel.links?.size ?: 0}")
        android.util.Log.e("DEBUG_FLOATING_HELPER", "Playback position: $playbackPosition")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.util.Log.e("DEBUG_FLOATING_HELPER", "Checking overlay permission...")
            val canDraw = Settings.canDrawOverlays(context)
            android.util.Log.e("DEBUG_FLOATING_HELPER", "Can draw overlays: $canDraw")
            
            if (!canDraw) {
                android.util.Log.e("DEBUG_FLOATING_HELPER", "NO PERMISSION - showing dialog or toast")
                
                if (context is Activity) {
                    android.util.Log.e("DEBUG_FLOATING_HELPER", "Context is Activity - showing dialog")
                    showPermissionDialog(context)
                } else {
                    android.util.Log.e("DEBUG_FLOATING_HELPER", "Context is NOT Activity - showing toast")
                    android.widget.Toast.makeText(
                        context,
                        "Overlay permission required. Please enable in settings.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                return
            }
        }
        
        android.util.Log.e("DEBUG_FLOATING_HELPER", "Permission OK - calling FloatingPlayerService.start()...")
        
        try {
            // FIXED: Pass playback position as third parameter
            FloatingPlayerService.start(context, channel, playbackPosition)
            android.util.Log.e("DEBUG_FLOATING_HELPER", "FloatingPlayerService.start() returned successfully")
            
        } catch (e: Exception) {
            android.util.Log.e("DEBUG_FLOATING_HELPER", "EXCEPTION calling service!", e)
            android.util.Log.e("DEBUG_FLOATING_HELPER", "Exception: ${e.message}")
            e.printStackTrace()
            
            android.widget.Toast.makeText(
                context,
                "Failed to start floating player: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        
        android.util.Log.e("DEBUG_FLOATING_HELPER", "========================================")
        android.util.Log.e("DEBUG_FLOATING_HELPER", "launchFloatingPlayer() COMPLETED")
        android.util.Log.e("DEBUG_FLOATING_HELPER", "========================================")
    }

    fun stopFloatingPlayer(context: Context) {
        android.util.Log.e("DEBUG_FLOATING_HELPER", "Stopping floating player service")
        FloatingPlayerService.stop(context)
    }

    private fun showPermissionDialog(activity: Activity) {
        android.util.Log.e("DEBUG_FLOATING_HELPER", "Showing permission dialog")
        AlertDialog.Builder(activity)
            .setTitle("Permission Required")
            .setMessage("Floating Player requires permission to display over other apps. Please enable it in the settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(intent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun hasOverlayPermission(context: Context): Boolean {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
        android.util.Log.e("DEBUG_FLOATING_HELPER", "hasOverlayPermission: $hasPermission")
        return hasPermission
    }
}
