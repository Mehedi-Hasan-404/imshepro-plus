package com.livetvpro.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.ui.player.FloatingPlayerService

object FloatingPlayerHelper {

    /**
     * Check if the app has overlay permission
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Request overlay permission
     */
    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * Show permission dialog and request if needed
     */
    fun checkAndRequestPermission(context: Context, onGranted: () -> Unit) {
        if (hasOverlayPermission(context)) {
            onGranted()
        } else {
            if (context is androidx.appcompat.app.AppCompatActivity) {
                AlertDialog.Builder(context)
                    .setTitle("Permission Required")
                    .setMessage("Floating Player requires permission to draw over other apps. Please enable it in settings.")
                    .setPositiveButton("Settings") { _, _ ->
                        requestOverlayPermission(context)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                requestOverlayPermission(context)
            }
        }
    }

    /**
     * Start floating player with stream
     */
    fun startFloatingPlayer(
        context: Context,
        streamUrl: String,
        streamTitle: String,
        preferencesManager: PreferencesManager
    ) {
        if (!preferencesManager.isFloatingPlayerEnabled()) {
            return
        }

        if (!hasOverlayPermission(context)) {
            checkAndRequestPermission(context) {
                launchFloatingPlayer(context, streamUrl, streamTitle)
            }
            return
        }

        launchFloatingPlayer(context, streamUrl, streamTitle)
    }

    private fun launchFloatingPlayer(context: Context, streamUrl: String, streamTitle: String) {
        val intent = Intent(context, FloatingPlayerService::class.java).apply {
            action = FloatingPlayerService.ACTION_START
            putExtra(FloatingPlayerService.EXTRA_STREAM_URL, streamUrl)
            putExtra(FloatingPlayerService.EXTRA_STREAM_TITLE, streamTitle)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * Stop specific floating player
     */
    fun stopFloatingPlayer(context: Context, playerId: Int) {
        val intent = Intent(context, FloatingPlayerService::class.java).apply {
            action = FloatingPlayerService.ACTION_STOP
            putExtra(FloatingPlayerService.EXTRA_PLAYER_ID, playerId)
        }
        context.startService(intent)
    }

    /**
     * Stop all floating players
     */
    fun stopAllFloatingPlayers(context: Context) {
        val intent = Intent(context, FloatingPlayerService::class.java).apply {
            action = FloatingPlayerService.ACTION_STOP_ALL
        }
        context.startService(intent)
    }
}
