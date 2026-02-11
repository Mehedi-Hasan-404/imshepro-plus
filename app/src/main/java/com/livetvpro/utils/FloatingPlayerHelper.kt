package com.livetvpro.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.livetvpro.data.models.Channel
import com.livetvpro.ui.player.FloatingPlayerService

object FloatingPlayerHelper {
    
    fun hasOverlayPermission(context: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
    
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
    
    fun launchFloatingPlayer(context: Context, channel: Channel, linkIndex: Int = 0) {
        if (!hasOverlayPermission(context)) {
            android.widget.Toast.makeText(
                context,
                "Overlay permission required for floating player",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        
        if (channel.links.isNullOrEmpty()) {
            android.widget.Toast.makeText(
                context,
                "No stream available for ${channel.name}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        
        try {
            FloatingPlayerService.start(
                context = context,
                channel = channel,
                linkIndex = linkIndex,
                playbackPosition = 0L
            )
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context,
                "Failed to start floating player: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
