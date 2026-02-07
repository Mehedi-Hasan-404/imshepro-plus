package com.livetvpro.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.data.local.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * FloatingPlayerActivity
 * Manages floating video player windows that can be dragged, resized, and controlled
 */
@AndroidEntryPoint
class FloatingPlayerActivity : AppCompatActivity() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if floating player is enabled
        if (!preferencesManager.isFloatingPlayerEnabled()) {
            Toast.makeText(this, "Please enable Floating Player from sidebar", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Check overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            showPermissionDialog()
            return
        }
        
        // Get stream data from intent
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""
        val streamTitle = intent.getStringExtra(EXTRA_STREAM_TITLE) ?: "Live Stream"
        
        if (streamUrl.isEmpty()) {
            Toast.makeText(this, "No stream URL provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Start floating player service
        val serviceIntent = Intent(this, FloatingPlayerService::class.java).apply {
            action = FloatingPlayerService.ACTION_START
            putExtra(FloatingPlayerService.EXTRA_STREAM_URL, streamUrl)
            putExtra(FloatingPlayerService.EXTRA_STREAM_TITLE, streamTitle)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        // Close this activity immediately (service will handle the floating window)
        finish()
    }
    
    private fun showPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Permission Required")
            .setMessage("Floating Player requires permission to display over other apps.")
            .setPositiveButton("Grant Permission") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                // Permission granted, restart the activity
                recreate()
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    companion object {
        private const val REQUEST_CODE_OVERLAY_PERMISSION = 5001
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_STREAM_TITLE = "extra_stream_title"
        
        /**
         * Start floating player with stream
         */
        fun start(context: Context, streamUrl: String, streamTitle: String) {
            val intent = Intent(context, FloatingPlayerActivity::class.java).apply {
                putExtra(EXTRA_STREAM_URL, streamUrl)
                putExtra(EXTRA_STREAM_TITLE, streamTitle)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
