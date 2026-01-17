package com.livetvpro.ui.player

import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.livetvpro.R

/**
 * Helper class for managing Picture-in-Picture (PiP) mode functionality.
 * Handles PiP params updates, aspect ratio calculations, and PiP mode entry.
 */
@RequiresApi(Build.VERSION_CODES.O)
class PipHelper(
    private val activity: Activity,
    private val playerView: PlayerView,
    private val getPlayer: () -> ExoPlayer?
) {
    
    private var pictureInPictureParamsBuilder: PictureInPictureParams.Builder = 
        PictureInPictureParams.Builder()
    
    private val pipSourceRect = Rect()
    private val rationalLimitWide = Rational(239, 100)
    private val rationalLimitTall = Rational(100, 239)
    
    companion object {
        private const val ACTION_MEDIA_CONTROL = "com.livetvpro.MEDIA_CONTROL"
        private const val EXTRA_CONTROL_TYPE = "control_type"
        private const val CONTROL_TYPE_PLAY = 1
        private const val CONTROL_TYPE_PAUSE = 2
        private const val REQUEST_PLAY = 1
        private const val REQUEST_PAUSE = 2
    }
    
    /**
     * Check if PiP is supported on this device
     */
    fun isPipSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    
    /**
     * Update PiP parameters with current video aspect ratio and source rect
     */
    fun updatePictureInPictureParams() {
        if (!isPipSupported()) return
        
        try {
            val player = getPlayer() ?: return
            val format = player.videoFormat
            val width = format?.width ?: 16
            val height = format?.height ?: 9
            
            var ratio = if (width > 0 && height > 0) {
                Rational(width, height)
            } else {
                Rational(16, 9)
            }
            
            // Clamp aspect ratio to allowed limits
            if (ratio.toFloat() > rationalLimitWide.toFloat()) {
                ratio = rationalLimitWide
            } else if (ratio.toFloat() < rationalLimitTall.toFloat()) {
                ratio = rationalLimitTall
            }
            
            pictureInPictureParamsBuilder.setAspectRatio(ratio)
            
            // Set source rect hint
            if (playerView.width > 0 && playerView.height > 0) {
                playerView.getGlobalVisibleRect(pipSourceRect)
                if (!pipSourceRect.isEmpty) {
                    pictureInPictureParamsBuilder.setSourceRectHint(pipSourceRect)
                }
            }
            
            // Android 12+ features
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pictureInPictureParamsBuilder.setAutoEnterEnabled(false)
                pictureInPictureParamsBuilder.setSeamlessResizeEnabled(true)
            }
            
            activity.setPictureInPictureParams(pictureInPictureParamsBuilder.build())
        } catch (e: Exception) {
            // Silently ignore PiP update failures
        }
    }
    
    /**
     * Update PiP actions (play/pause button)
     */
    fun updatePictureInPictureActions(
        iconId: Int,
        titleResId: Int,
        controlType: Int,
        requestCode: Int
    ): Boolean {
        if (!isPipSupported()) return false
        
        return try {
            val actions = ArrayList<RemoteAction>()
            val intent = PendingIntent.getBroadcast(
                activity,
                requestCode,
                Intent(ACTION_MEDIA_CONTROL)
                    .setPackage(activity.packageName)
                    .putExtra(EXTRA_CONTROL_TYPE, controlType),
                PendingIntent.FLAG_IMMUTABLE
            )
            val icon = Icon.createWithResource(activity, iconId)
            val title = activity.getString(titleResId)
            actions.add(RemoteAction(icon, title, title, intent))
            
            pictureInPictureParamsBuilder.setActions(actions)
            activity.setPictureInPictureParams(pictureInPictureParamsBuilder.build())
            true
        } catch (e: IllegalStateException) {
            false
        }
    }
    
    /**
     * Update PiP play/pause action based on playback state
     */
    fun updatePlaybackAction(isPlaying: Boolean) {
        if (isPlaying) {
            updatePictureInPictureActions(
                R.drawable.ic_pause,
                R.string.exo_controls_pause_description,
                CONTROL_TYPE_PAUSE,
                REQUEST_PAUSE
            )
        } else {
            updatePictureInPictureActions(
                R.drawable.ic_play,
                R.string.exo_controls_play_description,
                CONTROL_TYPE_PLAY,
                REQUEST_PLAY
            )
        }
    }
    
    /**
     * Enter Picture-in-Picture mode
     */
    fun enterPipMode(): Boolean {
        if (!isPipSupported()) return false
        
        // Check PiP permission
        val appOpsManager = activity.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        if (AppOpsManager.MODE_ALLOWED != appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                android.os.Process.myUid(),
                activity.packageName
            )) {
            // Open PiP settings if permission not granted
            val intent = Intent(
                "android.settings.PICTURE_IN_PICTURE_SETTINGS",
                Uri.fromParts("package", activity.packageName, null)
            )
            if (intent.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(intent)
            }
            return false
        }
        
        val player = getPlayer() ?: return false
        
        // Hide controller before entering PiP
        playerView.setControllerAutoShow(false)
        playerView.hideController()
        
        // Set video surface size
        val format = player.videoFormat
        if (format != null) {
            val videoSurfaceView = playerView.videoSurfaceView
            if (videoSurfaceView is SurfaceView) {
                videoSurfaceView.holder.setFixedSize(format.width, format.height)
            }
            
            var rational = Rational(format.width, format.height)
            
            // Set expanded aspect ratio for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                activity.packageManager.hasSystemFeature(PackageManager.FEATURE_EXPANDED_PICTURE_IN_PICTURE) &&
                (rational.toFloat() > rationalLimitWide.toFloat() ||
                        rational.toFloat() < rationalLimitTall.toFloat())) {
                pictureInPictureParamsBuilder.setExpandedAspectRatio(rational)
            }
            
            // Clamp aspect ratio
            if (rational.toFloat() > rationalLimitWide.toFloat()) {
                rational = rationalLimitWide
            } else if (rational.toFloat() < rationalLimitTall.toFloat()) {
                rational = rationalLimitTall
            }
            
            pictureInPictureParamsBuilder.setAspectRatio(rational)
        }
        
        return try {
            activity.enterPictureInPictureMode(pictureInPictureParamsBuilder.build())
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Initialize PiP with initial play/pause action
     */
    fun initialize() {
        if (!isPipSupported()) return
        
        updatePictureInPictureActions(
            R.drawable.ic_pause,
            R.string.exo_controls_pause_description,
            CONTROL_TYPE_PAUSE,
            REQUEST_PAUSE
        )
    }
}
