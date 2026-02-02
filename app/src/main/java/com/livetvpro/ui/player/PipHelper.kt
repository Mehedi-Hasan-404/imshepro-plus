package com.livetvpro.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.util.Rational
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.livetvpro.R

private const val PIP_INTENTS_FILTER = "pip_action"
private const val PIP_INTENT_ACTION = "pip_action_code"
private const val PIP_PLAY = 1
private const val PIP_PAUSE = 2
private const val PIP_REWIND = 3
private const val PIP_FORWARD = 4

/**
 * PipHelper - Clean Picture-in-Picture implementation
 * Based on MPVPipHelper reference implementation
 * 
 * Features:
 * - Automatic aspect ratio calculation from video format
 * - Source rect hints for smooth transitions
 * - Remote actions (Play/Pause, Rewind, Forward)
 * - Broadcast receiver for PiP control handling
 * - Proper lifecycle management
 */
@RequiresApi(Build.VERSION_CODES.O)
class PipHelper(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
    private val getPlayer: () -> ExoPlayer?
) {
    private var pipReceiver: BroadcastReceiver? = null
    
    // Skip amount for rewind/forward actions (10 seconds in milliseconds)
    private val skipMs = 10_000L
    
    /**
     * Handle Picture-in-Picture mode changes
     */
    fun onPictureInPictureModeChanged(isInPipMode: Boolean) {
        if (isInPipMode) {
            registerPipReceiver()
        } else {
            unregisterPipReceiver()
        }
    }
    
    /**
     * Register broadcast receiver for PiP controls
     */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun registerPipReceiver() {
        pipReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val player = getPlayer() ?: return
                
                when (intent?.getIntExtra(PIP_INTENT_ACTION, 0)) {
                    PIP_PLAY -> {
                        player.play()
                    }
                    PIP_PAUSE -> {
                        player.pause()
                    }
                    PIP_REWIND -> {
                        val newPosition = (player.currentPosition - skipMs).coerceAtLeast(0)
                        player.seekTo(newPosition)
                    }
                    PIP_FORWARD -> {
                        val newPosition = player.currentPosition + skipMs
                        val duration = player.duration
                        if (duration > 0 && newPosition < duration) {
                            player.seekTo(newPosition)
                        } else if (duration > 0) {
                            player.seekTo(duration)
                        }
                    }
                }
                updatePictureInPictureParams()
            }
        }
        
        val filter = IntentFilter(PIP_INTENTS_FILTER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(pipReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(pipReceiver, filter)
        }
    }
    
    /**
     * Unregister broadcast receiver
     */
    private fun unregisterPipReceiver() {
        pipReceiver?.let {
            runCatching { activity.unregisterReceiver(it) }
            pipReceiver = null
        }
    }
    
    /**
     * Update Picture-in-Picture parameters with current state
     */
    fun updatePictureInPictureParams() {
        if (activity.isFinishing || activity.isDestroyed) return
        
        val params = buildPipParams()
        runCatching { activity.setPictureInPictureParams(params) }
    }
    
    /**
     * Build PiP parameters with aspect ratio, source rect, and actions
     */
    private fun buildPipParams(): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .apply {
                getVideoAspectRatio()?.let { aspectRatio ->
                    setAspectRatio(aspectRatio)
                    setSourceRectHint(calculateSourceRect(aspectRatio))
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(false)
                }
                
                setActions(createPipActions())
            }
            .build()
    
    /**
     * Get video aspect ratio from player
     */
    private fun getVideoAspectRatio(): Rational? {
        val player = getPlayer() ?: return null
        val format = player.videoFormat ?: return null
        
        val width = format.width
        val height = format.height
        
        if (width == 0 || height == 0) return null
        
        // Ensure aspect ratio is within valid range (0.5 to 2.39)
        return Rational(width, height).takeIf { it.toFloat() in 0.5f..2.39f }
    }
    
    /**
     * Calculate source rect hint for smooth PiP transition
     */
    private fun calculateSourceRect(aspectRatio: Rational): Rect {
        val viewWidth = playerView.width.toFloat()
        val viewHeight = playerView.height.toFloat()
        val videoAspect = aspectRatio.toFloat()
        val viewAspect = viewWidth / viewHeight
        
        return if (viewAspect < videoAspect) {
            // Letterboxed (black bars top/bottom)
            val height = viewWidth / videoAspect
            val top = ((viewHeight - height) / 2).toInt()
            Rect(0, top, viewWidth.toInt(), (height + top).toInt())
        } else {
            // Pillarboxed (black bars left/right)
            val width = viewHeight * videoAspect
            val left = ((viewWidth - width) / 2).toInt()
            Rect(left, 0, (width + left).toInt(), viewHeight.toInt())
        }
    }
    
    /**
     * Create remote actions for PiP controls
     */
    private fun createPipActions(): List<RemoteAction> {
        val player = getPlayer()
        val isPlaying = player?.isPlaying == true
        
        return listOf(
            createRemoteAction("rewind", R.drawable.ic_skip_backward, PIP_REWIND),
            if (isPlaying) {
                createRemoteAction("pause", R.drawable.ic_pause, PIP_PAUSE)
            } else {
                createRemoteAction("play", R.drawable.ic_play, PIP_PLAY)
            },
            createRemoteAction("forward", R.drawable.ic_skip_forward, PIP_FORWARD)
        )
    }
    
    /**
     * Create a single remote action
     */
    private fun createRemoteAction(
        title: String,
        @DrawableRes icon: Int,
        actionCode: Int
    ): RemoteAction {
        val intent = Intent(PIP_INTENTS_FILTER).apply {
            putExtra(PIP_INTENT_ACTION, actionCode)
            setPackage(activity.packageName)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            activity,
            actionCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return RemoteAction(
            Icon.createWithResource(activity, icon),
            title,
            title,
            pendingIntent
        )
    }
    
    /**
     * Enter Picture-in-Picture mode
     */
    fun enterPipMode() {
        runCatching {
            activity.enterPictureInPictureMode(buildPipParams())
        }.onFailure {
            Log.e("PipHelper", "Failed to enter PiP mode", it)
        }
    }
    
    /**
     * Clean up resources - call in onStop()
     */
    fun onStop() {
        unregisterPipReceiver()
    }
}
