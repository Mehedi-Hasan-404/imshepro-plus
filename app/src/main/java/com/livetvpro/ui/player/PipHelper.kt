package com.livetvpro.ui.player

import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.livetvpro.R
import com.livetvpro.data.local.PreferencesManager

/**
 * PipHelper - Comprehensive Picture-in-Picture management
 * 
 * Features:
 * - Automatic PiP aspect ratio calculation
 * - Seamless transitions and source rect hints
 * - Configurable actions (Skip Forward/Backward OR Next/Previous)
 * - Broadcast receiver for PiP controls
 * - Permission and capability checking
 * - Android 12+ optimizations (auto-enter, seamless resize)
 */
@RequiresApi(Build.VERSION_CODES.O)
class PipHelper(
    private val activity: Activity,
    private val playerView: PlayerView,
    private val getPlayer: () -> ExoPlayer?,
    private val preferencesManager: PreferencesManager,
    private val onRetryPlayback: () -> Unit = {}
) {
    
    private var pictureInPictureParamsBuilder = PictureInPictureParams.Builder()
    private val pipSourceRect = Rect()
    private val rationalLimitWide = Rational(239, 100)
    private val rationalLimitTall = Rational(100, 239)
    private var pipBroadcastReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false
    
    // Skip amount for forward/backward actions (10 seconds)
    private val skipMs = 10_000L
    
    companion object {
        const val ACTION_MEDIA_CONTROL = "com.livetvpro.MEDIA_CONTROL"
        const val EXTRA_CONTROL_TYPE = "control_type"
        const val CONTROL_TYPE_PLAY = 1
        const val CONTROL_TYPE_PAUSE = 2
        const val CONTROL_TYPE_SKIP_BACKWARD = 3
        const val CONTROL_TYPE_SKIP_FORWARD = 4
        const val CONTROL_TYPE_PREVIOUS = 5
        const val CONTROL_TYPE_NEXT = 6
        
        private const val REQUEST_PLAY = 1
        private const val REQUEST_PAUSE = 2
        private const val REQUEST_SKIP_BACKWARD = 3
        private const val REQUEST_SKIP_FORWARD = 4
        private const val REQUEST_PREVIOUS = 5
        private const val REQUEST_NEXT = 6
    }
    
    /**
     * Check if device supports Picture-in-Picture
     */
    fun isPipSupported(): Boolean {
        return activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    
    /**
     * Check if app has PiP permission
     */
    fun checkPipPermission(): Boolean {
        if (!isPipSupported()) return false
        
        val appOpsManager = activity.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return true
        
        return AppOpsManager.MODE_ALLOWED == appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
            android.os.Process.myUid(),
            activity.packageName
        )
    }
    
    /**
     * Open system PiP settings for this app
     */
    fun openPipSettings() {
        val intent = Intent(
            "android.settings.PICTURE_IN_PICTURE_SETTINGS",
            Uri.fromParts("package", activity.packageName, null)
        )
        if (intent.resolveActivity(activity.packageManager) != null) {
            activity.startActivity(intent)
        }
    }
    
    /**
     * Initialize PiP helper - should be called in onCreate
     */
    fun initialize() {
        if (!isPipSupported()) return
        
        try {
            val actions = buildPipActions(isPlaying = false)
            pictureInPictureParamsBuilder.setActions(actions)
            
            // Android 12+ features
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pictureInPictureParamsBuilder.setAutoEnterEnabled(false)
                pictureInPictureParamsBuilder.setSeamlessResizeEnabled(true)
            }
        } catch (e: Exception) {
            // Handle exception silently
        }
    }
    
    /**
     * Update Picture-in-Picture parameters with current video state
     */
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
            
            // Set PiP actions based on current playback state
            val actions = buildPipActions(player.isPlaying)
            pictureInPictureParamsBuilder.setActions(actions)
            
            activity.setPictureInPictureParams(pictureInPictureParamsBuilder.build())
        } catch (e: Exception) {
            // Handle exception silently
        }
    }
    
    /**
     * Build PiP remote actions based on user preference
     */
    private fun buildPipActions(isPlaying: Boolean): ArrayList<RemoteAction> {
        val actions = ArrayList<RemoteAction>()
        
        // Get user preference for PiP action mode
        val useSkipActions = preferencesManager.getPipActionMode() == PreferencesManager.PIP_ACTION_MODE_SKIP
        
        if (useSkipActions) {
            // Mode 1: Skip Backward / Play-Pause / Skip Forward
            addSkipBackwardAction(actions)
            addPlayPauseAction(actions, isPlaying)
            addSkipForwardAction(actions)
        } else {
            // Mode 2: Previous / Play-Pause / Next
            addPreviousAction(actions)
            addPlayPauseAction(actions, isPlaying)
            addNextAction(actions)
        }
        
        return actions
    }
    
    /**
     * Add Skip Backward action (10 seconds)
     */
    private fun addSkipBackwardAction(actions: ArrayList<RemoteAction>) {
        val rewindIntent = PendingIntent.getBroadcast(
            activity,
            REQUEST_SKIP_BACKWARD,
            Intent(ACTION_MEDIA_CONTROL)
                .setPackage(activity.packageName)
                .putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_SKIP_BACKWARD),
            PendingIntent.FLAG_IMMUTABLE
        )
        val rewindIcon = Icon.createWithResource(activity, R.drawable.ic_skip_backward)
        actions.add(RemoteAction(rewindIcon, "Rewind", "Rewind 10s", rewindIntent))
    }
    
    /**
     * Add Skip Forward action (10 seconds)
     */
    private fun addSkipForwardAction(actions: ArrayList<RemoteAction>) {
        val forwardIntent = PendingIntent.getBroadcast(
            activity,
            REQUEST_SKIP_FORWARD,
            Intent(ACTION_MEDIA_CONTROL)
                .setPackage(activity.packageName)
                .putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_SKIP_FORWARD),
            PendingIntent.FLAG_IMMUTABLE
        )
        val forwardIcon = Icon.createWithResource(activity, R.drawable.ic_skip_forward)
        actions.add(RemoteAction(forwardIcon, "Forward", "Forward 10s", forwardIntent))
    }
    
    /**
     * Add Previous action (previous track/chapter)
     */
    private fun addPreviousAction(actions: ArrayList<RemoteAction>) {
        val previousIntent = PendingIntent.getBroadcast(
            activity,
            REQUEST_PREVIOUS,
            Intent(ACTION_MEDIA_CONTROL)
                .setPackage(activity.packageName)
                .putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PREVIOUS),
            PendingIntent.FLAG_IMMUTABLE
        )
        val previousIcon = Icon.createWithResource(activity, R.drawable.ic_skip_back)
        actions.add(RemoteAction(previousIcon, "Previous", "Previous", previousIntent))
    }
    
    /**
     * Add Next action (next track/chapter)
     */
    private fun addNextAction(actions: ArrayList<RemoteAction>) {
        val nextIntent = PendingIntent.getBroadcast(
            activity,
            REQUEST_NEXT,
            Intent(ACTION_MEDIA_CONTROL)
                .setPackage(activity.packageName)
                .putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_NEXT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val nextIcon = Icon.createWithResource(activity, R.drawable.ic_skip_forward)
        actions.add(RemoteAction(nextIcon, "Next", "Next", nextIntent))
    }
    
    /**
     * Add Play/Pause action
     */
    private fun addPlayPauseAction(actions: ArrayList<RemoteAction>, isPlaying: Boolean) {
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
    }
    
    /**
     * Update only the playback action (play/pause) without rebuilding everything
     */
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
    
    /**
     * Enter Picture-in-Picture mode
     */
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
        
        // Set source rect
        playerView.getGlobalVisibleRect(pipSourceRect)
        if (!pipSourceRect.isEmpty) {
            pictureInPictureParamsBuilder.setSourceRectHint(pipSourceRect)
        }
        
        // Set actions
        val actions = buildPipActions(player.isPlaying)
        pictureInPictureParamsBuilder.setActions(actions)
        
        return try {
            activity.enterPictureInPictureMode(pictureInPictureParamsBuilder.build())
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Register broadcast receiver for PiP controls
     */
    fun registerPipReceiver() {
        if (!isPipSupported() || isReceiverRegistered) return
        
        pipBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_MEDIA_CONTROL) return
                
                val player = getPlayer() ?: return
                val controlType = intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)
                
                handlePipControl(player, controlType)
            }
        }
        
        try {
            val filter = IntentFilter(ACTION_MEDIA_CONTROL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(
                    pipBroadcastReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                activity.registerReceiver(pipBroadcastReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) {
            // Handle exception
        }
    }
    
    /**
     * Handle PiP control actions
     */
    private fun handlePipControl(player: ExoPlayer, controlType: Int) {
        val hasError = player.playerError != null
        val hasEnded = player.playbackState == Player.STATE_ENDED
        
        when (controlType) {
            CONTROL_TYPE_PLAY -> {
                if (hasError || hasEnded) {
                    onRetryPlayback()
                } else {
                    player.play()
                }
                updatePlaybackAction(true)
            }
            
            CONTROL_TYPE_PAUSE -> {
                if (hasError || hasEnded) {
                    onRetryPlayback()
                } else {
                    player.pause()
                }
                updatePlaybackAction(false)
            }
            
            CONTROL_TYPE_SKIP_BACKWARD -> {
                if (!hasError && !hasEnded) {
                    val newPosition = player.currentPosition - skipMs
                    player.seekTo(if (newPosition < 0) 0 else newPosition)
                }
            }
            
            CONTROL_TYPE_SKIP_FORWARD -> {
                if (!hasError && !hasEnded) {
                    val newPosition = player.currentPosition + skipMs
                    val duration = player.duration
                    
                    if (player.isCurrentMediaItemLive && duration != C.TIME_UNSET && newPosition >= duration) {
                        player.seekTo(duration)
                    } else {
                        player.seekTo(newPosition)
                    }
                }
            }
            
            CONTROL_TYPE_PREVIOUS -> {
                if (!hasError && !hasEnded) {
                    if (player.hasPreviousMediaItem()) {
                        player.seekToPreviousMediaItem()
                    } else {
                        // If no previous item, seek to beginning of current
                        player.seekTo(0)
                    }
                }
            }
            
            CONTROL_TYPE_NEXT -> {
                if (!hasError && !hasEnded) {
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                    }
                }
            }
        }
    }
    
    /**
     * Unregister broadcast receiver for PiP controls
     */
    fun unregisterPipReceiver() {
        if (!isReceiverRegistered) return
        
        try {
            pipBroadcastReceiver?.let {
                activity.unregisterReceiver(it)
                pipBroadcastReceiver = null
                isReceiverRegistered = false
            }
        } catch (e: Exception) {
            // Handle exception
        }
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        unregisterPipReceiver()
    }
}
