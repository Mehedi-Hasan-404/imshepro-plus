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

    fun launchFloatingPlayer(context: Context, channel: Channel) {
        android.util.Log.d("FloatingPlayerHelper", "=== Launch Floating Player ===")
        android.util.Log.d("FloatingPlayerHelper", "Channel: ${channel.name}")
        android.util.Log.d("FloatingPlayerHelper", "Links count: ${channel.links?.size ?: 0}")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                android.util.Log.w("FloatingPlayerHelper", "Missing overlay permission")
                
                if (context is Activity) {
                    // Show permission dialog if context is an Activity
                    showPermissionDialog(context)
                } else {
                    // If not an activity, just show a toast
                    android.widget.Toast.makeText(
                        context,
                        "Overlay permission required. Please enable in settings.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                return
            }
        }
        
        android.util.Log.d("FloatingPlayerHelper", "Starting floating player service...")
        FloatingPlayerService.start(context, channel)
    }

    fun stopFloatingPlayer(context: Context) {
        android.util.Log.d("FloatingPlayerHelper", "Stopping floating player service")
        FloatingPlayerService.stop(context)
    }

    private fun showPermissionDialog(activity: Activity) {
        android.util.Log.d("FloatingPlayerHelper", "Showing permission dialog")
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
        android.util.Log.d("FloatingPlayerHelper", "Has overlay permission: $hasPermission")
        return hasPermission
    }
}
