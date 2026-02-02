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
import androidx.annotation.RequiresApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.livetvpro.R

@RequiresApi(Build.VERSION_CODES.O)
class PipHelper(
    private val activity: Activity,
    private val playerView: PlayerView,
    private val getPlayer: () -> ExoPlayer?
) {
    
    private var pictureInPictureParamsBuilder = PictureInPictureParams.Builder()
    private val pipSourceRect = Rect()
    private val rationalLimitWide = Rational(239, 100)
    private val rationalLimitTall = Rational(100, 239)
    
    companion object {
        const val ACTION_MEDIA_CONTROL = "com.livetvpro.MEDIA_CONTROL"
        const val EXTRA_CONTROL_TYPE = "control_type"
        const val CONTROL_TYPE_PLAY = 1
        const val CONTROL_TYPE_PAUSE = 2
        const val CONTROL_TYPE_REWIND = 3
        const val CONTROL_TYPE_FORWARD = 4
        private const val REQUEST_PLAY = 1
        private const val REQUEST_PAUSE = 2
        private const val REQUEST_REWIND = 3
        private const val REQUEST_FORWARD = 4
    }
    
    fun isPipSupported(): Boolean {
        return activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    
    fun checkPipPermission(): Boolean {
        val appOpsManager = activity.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return AppOpsManager.MODE_ALLOWED == appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
            android.os.Process.myUid(),
            activity.packageName
        )
    }
    
    fun openPipSettings() {
        val intent = Intent(
            "android.settings.PICTURE_IN_PICTURE_SETTINGS",
            Uri.fromParts("package", activity.packageName, null)
        )
        if (intent.resolveActivity(activity.packageManager) != null) {
            activity.startActivity(intent)
        }
    }
    
    fun updatePictureInPictureParams() {
        if (!isPipSupported()) return
        
        try {
            pictureInPictureParamsBuilder = PictureInPictureParams.Builder()
            
            val player = getPlayer() ?: return
            val format = player.videoFormat
            val width = format?.width ?: 16
            val height = format?.height ?: 9
            
            var ratio = if (width > 0 && height > 0) {
                Rational(width, height)
            } else {
                Rational(16, 9)
            }
            
            // Clamp aspect ratio to system limits
            if (ratio.toFloat() > rationalLimitWide.toFloat()) {
                ratio = rationalLimitWide
            } else if (ratio.toFloat() < rationalLimitTall.toFloat()) {
                ratio = rationalLimitTall
            }
            
            pictureInPictureParamsBuilder.setAspectRatio(ratio)
            
            // Set source rect hint for smooth transition
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
            
            // Set PiP actions
            val actions = buildPipActions(player.isPlaying)
            pictureInPictureParamsBuilder.setActions(actions)
            
            activity.setPictureInPictureParams(pictureInPictureParamsBuilder.build())
        } catch (e: Exception) {
            // Handle exception silently
        }
    }
    
    private fun buildPipActions(isPlaying: Boolean): ArrayList<RemoteAction> {
        val actions = ArrayList<RemoteAction>()
        
        // Rewind action
        val rewindIntent = PendingIntent.getBroadcast(
            activity,
            REQUEST_REWIND,
            Intent(ACTION_MEDIA_CONTROL)
                .setPackage(activity.packageName)
                .putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_REWIND),
            PendingIntent.FLAG_IMMUTABLE
        )
        val rewindIcon = Icon.createWithResource(activity, R.drawable.ic_skip_backward)
        actions.add(RemoteAction(rewindIcon, "Rewind", "Rewind 10s", rewindIntent))
        
        // Play/Pause action
        if (isPlaying) {
            val pauseIntent = PendingIntent.getBroadcast(
                activity,
                REQUEST_PAUSE,
                Intent(ACTION_MEDIA_CONTROL)
                    .setPackage(activity.packageName)
                    .putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PAUSE),
                PendingIntent.FLAG_IMMUTABLE
            )
            val pauseIcon = Icon.createWithResource(activity, R.drawable.ic_pause)
            val pauseTitle = activity.getString(R.string.pause)
            actions.add(RemoteAction(pauseIcon, pauseTitle, pauseTitle, pauseIntent))
        } else {
            val playIntent = PendingIntent.getBroadcast(
                activity,
                REQUEST_PLAY,
                Intent(ACTION_MEDIA_CONTROL)
                    .setPackage(activity.packageName)
                    .putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PLAY),
                PendingIntent.FLAG_IMMUTABLE
            )
            val playIcon = Icon.createWithResource(activity, R.drawable.ic_play)
            val playTitle = activity.getString(R.string.play)
            actions.add(RemoteAction(playIcon, playTitle, playTitle, playIntent))
        }
        
        // Forward action
        val forwardIntent = PendingIntent.getBroadcast(
            activity,
            REQUEST_FORWARD,
            Intent(ACTION_MEDIA_CONTROL)
                .setPackage(activity.packageName)
                .putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_FORWARD),
            PendingIntent.FLAG_IMMUTABLE
        )
        val forwardIcon = Icon.createWithResource(activity, R.drawable.ic_skip_forward)
        actions.add(RemoteAction(forwardIcon, "Forward", "Forward 10s", forwardIntent))
        
        return actions
    }
    
    fun updatePlaybackAction(isPlaying: Boolean) {
        if (!isPipSupported()) return
        
        try {
            val actions = buildPipActions(isPlaying)
            pictureInPictureParamsBuilder.setActions(actions)
            activity.setPictureInPictureParams(pictureInPictureParamsBuilder.build())
        } catch (e: Exception) {
            // Handle exception silently
        }
    }
    
    fun enterPipMode(): Boolean {
        if (!isPipSupported()) return false
        
        // Check permission
        if (!checkPipPermission()) {
            openPipSettings()
            return false
        }
        
        val player = getPlayer() ?: return false
        val format = player.videoFormat
        
        if (format != null) {
            var rational = Rational(format.width, format.height)
            
            // Android 13+ expanded PiP support
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                activity.packageManager.hasSystemFeature(PackageManager.FEATURE_EXPANDED_PICTURE_IN_PICTURE) &&
                (rational.toFloat() > rationalLimitWide.toFloat() ||
                        rational.toFloat() < rationalLimitTall.toFloat())) {
                pictureInPictureParamsBuilder.setExpandedAspectRatio(rational)
            }
            
            // Clamp to limits
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
    
    fun initialize() {
        if (!isPipSupported()) return
        
        try {
            val actions = buildPipActions(false)
            pictureInPictureParamsBuilder.setActions(actions)
        } catch (e: Exception) {
            // Handle exception silently
        }
    }
}
