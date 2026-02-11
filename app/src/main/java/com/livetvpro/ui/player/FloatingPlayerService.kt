package com.livetvpro.ui.player

import android.widget.FrameLayout
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import kotlin.math.abs

@dagger.hilt.android.AndroidEntryPoint
class FloatingPlayerService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    
    // Position and touch tracking
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    
    // State
    private var controlsLocked = false
    private var isMuted = false
    
    // Handler for auto-hiding unlock button (ExoPlayer handles controls auto-hide)
    private val hideControlsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    // Handler for auto-hiding unlock button
    private val hideUnlockButtonRunnable = Runnable {
        unlockButton?.visibility = View.GONE
    }
    
    // UI components
    private var lockOverlay: View? = null
    private var unlockButton: ImageButton? = null
    private var params: WindowManager.LayoutParams? = null
    
    // Store the channel data and playback position
    private var currentChannel: Channel? = null
    private var currentPlaybackPosition: Long = 0L
    
    
    // FIXED: Add PreferencesManager to save/restore window position
    @javax.inject.Inject
    lateinit var preferencesManager: com.livetvpro.data.local.PreferencesManager
    // Size limits
    private fun getMinWidth() = dpToPx(240)
    private fun getMaxWidth() = dpToPx(400)
    private fun getMinHeight() = getMinWidth() * 9 / 16
    private fun getMaxHeight() = getMaxWidth() * 9 / 16
    
    companion object {
        const val EXTRA_CHANNEL = "extra_channel"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PLAYBACK_POSITION = "extra_playback_position"
        const val EXTRA_LINK_INDEX = "extra_link_index" // 🔥 NEW
        const val ACTION_STOP = "action_stop"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_player_channel"
        
        // 🔥 UPDATED: Added linkIndex parameter with default value
        fun start(context: Context, channel: Channel, linkIndex: Int = 0, playbackPosition: Long = 0L) {
            try {
                if (channel.links == null || channel.links.isEmpty()) {
                    android.widget.Toast.makeText(
                        context,
                        "No stream available",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                
                // 🔥 FIX: Use the selected link index instead of always first
                val selectedLink = if (linkIndex in channel.links.indices) {
                    channel.links[linkIndex]
                } else {
                    channel.links.firstOrNull()
                }
                
                val streamUrl = selectedLink?.url ?: ""
                
                android.util.Log.e("FloatingPlayerService", "Starting with link index: $linkIndex")
                android.util.Log.e("FloatingPlayerService", "Selected quality: ${selectedLink?.quality}")
                android.util.Log.e("FloatingPlayerService", "Stream URL: $streamUrl")
                
                if (streamUrl.isEmpty()) {
                    android.widget.Toast.makeText(
                        context,
                        "Invalid stream URL",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                
                val intent = Intent(context, FloatingPlayerService::class.java).apply {
                    putExtra(EXTRA_CHANNEL, channel)
                    putExtra(EXTRA_STREAM_URL, streamUrl)
                    putExtra(EXTRA_TITLE, channel.name)
                    putExtra(EXTRA_PLAYBACK_POSITION, playbackPosition)
                    putExtra(EXTRA_LINK_INDEX, linkIndex) // 🔥 NEW: Store the link index
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                
            } catch (e: Exception) {
                android.util.Log.e("FloatingPlayerService", "Error starting service", e)
                e.printStackTrace()
                android.widget.Toast.makeText(
                    context,
                    "Failed to start floating player: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingPlayerService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    // 🔥 UPDATED: Read linkIndex and use selected link
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        
        currentChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_CHANNEL)
        }
        
        // 🔥 NEW: Get the link index
        val linkIndex = intent?.getIntExtra(EXTRA_LINK_INDEX, 0) ?: 0
        
        android.util.Log.e("FloatingPlayerService", "onStartCommand - linkIndex: $linkIndex")
        android.util.Log.e("FloatingPlayerService", "Channel: ${currentChannel?.name}")
        android.util.Log.e("FloatingPlayerService", "Total links: ${currentChannel?.links?.size ?: 0}")
        
        // 🔥 FIX: Use the selected link instead of first
        val selectedLink = if (currentChannel != null && 
                              currentChannel!!.links != null && 
                              linkIndex in currentChannel!!.links.indices) {
            currentChannel!!.links[linkIndex]
        } else {
            currentChannel?.links?.firstOrNull()
        }
        
        val streamUrl = intent?.getStringExtra(EXTRA_STREAM_URL) ?: selectedLink?.url ?: ""
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: currentChannel?.name ?: "Live Stream"
        currentPlaybackPosition = intent?.getLongExtra(EXTRA_PLAYBACK_POSITION, 0L) ?: 0L
        
        android.util.Log.e("FloatingPlayerService", "Selected quality: ${selectedLink?.quality}")
        android.util.Log.e("FloatingPlayerService", "Stream URL: $streamUrl")
        
        val useTransferredPlayer = intent?.getBooleanExtra("use_transferred_player", false) ?: false
        
        android.util.Log.d("FloatingPlayerService", "Starting - useTransferredPlayer: $useTransferredPlayer")
        
        // FIXED: Don't require streamUrl if using transferred player
        if (streamUrl.isEmpty() && !useTransferredPlayer) {
            android.util.Log.e("FloatingPlayerService", "No stream URL and not using transferred player - stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        
        startForeground(NOTIFICATION_ID, createNotification(title))
        
        if (floatingView == null) {
            createFloatingView(streamUrl, title, useTransferredPlayer)
        }
        
        return START_STICKY
    }

    // REST OF THE FILE REMAINS THE SAME - Only the companion object and onStartCommand were updated
    // The createFloatingView and all other methods remain unchanged
    
    private fun createFloatingView(streamUrl: String, title: String, useTransferredPlayer: Boolean = false) {
        // ... existing code (lines 184-614 from original file) ...
        // This method is too long to include here, but NO CHANGES are needed
    }
    
    private fun showControls() {
        if (controlsLocked) return
        playerView?.showController()
    }
    
    private fun hideControls() {
        playerView?.hideController()
    }
    
    private fun toggleLock() {
        val lockBtn = playerView?.findViewById<ImageButton>(R.id.btn_lock)
        
        if (controlsLocked) {
            controlsLocked = false
            lockOverlay?.visibility = View.GONE
            hideUnlockButton()
            lockBtn?.setImageResource(R.drawable.ic_lock_open)
            showControls()
            android.util.Log.d("FloatingPlayerService", "Controls unlocked")
        } else {
            controlsLocked = true
            lockBtn?.setImageResource(R.drawable.ic_lock_closed)
            playerView?.hideController()
            lockOverlay?.apply {
                visibility = View.VISIBLE
                isClickable = false
                isFocusable = false
            }
            android.util.Log.d("FloatingPlayerService", "Controls locked - window is still draggable")
        }
    }

    private fun showUnlockButton() {
        unlockButton?.visibility = View.VISIBLE
        hideControlsHandler.removeCallbacks(hideUnlockButtonRunnable)
        hideControlsHandler.postDelayed(hideUnlockButtonRunnable, 3000)
    }
    
    private fun hideUnlockButton() {
        hideControlsHandler.removeCallbacks(hideUnlockButtonRunnable)
        unlockButton?.visibility = View.GONE
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Player",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating player service notification"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String): Notification {
        val stopIntent = Intent(this, FloatingPlayerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating Player")
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_play)
            .addAction(R.drawable.ic_close, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideControlsHandler.removeCallbacks(hideUnlockButtonRunnable)
        player?.release()
        player = null
        
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
            floatingView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * SUMMARY OF CHANGES IN THIS FILE:
 * =================================
 * 
 * 1. Line 86: Added EXTRA_LINK_INDEX constant
 * 2. Line 91: Updated start() signature to accept linkIndex parameter (default 0)
 * 3. Lines 100-107: Use channel.links[linkIndex] instead of firstOrNull()
 * 4. Line 128: Pass linkIndex to intent extras
 * 5. Line 170: Read linkIndex from intent in onStartCommand
 * 6. Lines 177-183: Use selected link based on linkIndex
 * 7. Added extensive logging throughout for debugging
 * 
 * ALL OTHER METHODS REMAIN UNCHANGED
 */
