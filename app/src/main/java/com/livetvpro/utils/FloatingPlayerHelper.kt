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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                if (context is Activity) {
                    showPermissionDialog(context)
                }
                return
            }
        }
        
        FloatingPlayerService.start(context, channel)
    }

    fun stopFloatingPlayer(context: Context) {
        FloatingPlayerService.stop(context)
    }

    private fun showPermissionDialog(activity: Activity) {
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
}
