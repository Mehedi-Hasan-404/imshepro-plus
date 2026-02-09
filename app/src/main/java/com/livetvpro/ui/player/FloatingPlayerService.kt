package com.livetvpro.ui.player

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
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.DefaultTimeBar
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
    private var controlsVisible = true
    
    // Auto-hide controls
    private val hideControlsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideControlsRunnable = Runnable {
        if (!controlsLocked && controlsVisible) {
            hideControls()
        }
    }
    private val HIDE_CONTROLS_DELAY = 3000L
    
    // UI components
    private var controlsContainer: View? = null
    private var bottomControlsContainer: View? = null
    private var lockOverlay: View? = null
    private var unlockButton: ImageButton? = null
    private var params: WindowManager.LayoutParams? = null
    
    // FIXED: Add seek bar and time display references
    private var seekBar: DefaultTimeBar? = null
    private var currentTimeText: TextView? = null
    private var durationText: TextView? = null
    
    // FIXED: Add runnable for updating time displays
    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            updateTimeDisplays()
            hideControlsHandler.postDelayed(this, 200) // Update every 200ms
        }
    }
    
    // Handler for auto-hiding unlock button
    private val hideUnlockButtonRunnable = Runnable {
        unlockButton?.visibility = View.GONE
    }
    
    // Store the channel data and playback position
    private var currentChannel: Channel? = null
    private var currentPlaybackPosition: Long = 0L
    
    
    // FIXED: Add PreferencesManager to save/restore window position
    @javax.inject.Inject
    lateinit var preferencesManager: com.livetvpro.data.local.PreferencesManager
    // Size limits
    private fun getMinWidth() = dpToPx(280)  // Increased from 180dp to 280dp for better visibility
    private fun getMaxWidth() = dpToPx(450)  // Also increased max slightly to 450dp
    private fun getMinHeight() = getMinWidth() * 9 / 16
    private fun getMaxHeight() = getMaxWidth() * 9 / 16
    
    companion object {
        const val EXTRA_CHANNEL = "extra_channel"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PLAYBACK_POSITION = "extra_playback_position"
        const val ACTION_STOP = "action_stop"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_player_channel"
        
        fun start(context: Context, channel: Channel, playbackPosition: Long = 0L) {
            try {
                if (channel.links == null || channel.links.isEmpty()) {
                    android.widget.Toast.makeText(
                        context,
                        "No stream available",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                
                val streamUrl = channel.links.firstOrNull()?.url ?: ""
                
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
        
        val streamUrl = intent?.getStringExtra(EXTRA_STREAM_URL) ?: currentChannel?.links?.firstOrNull()?.url ?: ""
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: currentChannel?.name ?: "Live Stream"
        currentPlaybackPosition = intent?.getLongExtra(EXTRA_PLAYBACK_POSITION, 0L) ?: 0L
        
        
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

    private fun createFloatingView(streamUrl: String, title: String, useTransferredPlayer: Boolean = false) {
        try {
            val themeContext = android.view.ContextThemeWrapper(this, R.style.Theme_LiveTVPro)
            floatingView = LayoutInflater.from(themeContext).inflate(R.layout.floating_player_window, null)
            
            // FIXED: Get screen dimensions correctly for PORTRAIT mode
            // Always center based on portrait dimensions even if launched from landscape
            val screenWidth: Int
            val screenHeight: Int
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ (API 30+)
                val windowMetrics = windowManager?.currentWindowMetrics
                val bounds = windowMetrics?.bounds
                val width = bounds?.width() ?: 1080
                val height = bounds?.height() ?: 1920
                
                // CRITICAL FIX: Ensure we always use portrait dimensions
                // (smaller value = width, larger value = height)
                if (width < height) {
                    // Already in portrait
                    screenWidth = width
                    screenHeight = height
                } else {
                    // In landscape, swap to get portrait dimensions
                    screenWidth = height
                    screenHeight = width
                }
            } else {
                // Android 10 and below
                val display = windowManager?.defaultDisplay
                val size = Point()
                display?.getRealSize(size)
                val width = size.x
                val height = size.y
                
                // CRITICAL FIX: Ensure we always use portrait dimensions
                if (width < height) {
                    screenWidth = width
                    screenHeight = height
                } else {
                    screenWidth = height
                    screenHeight = width
                }
            }
            
            // Calculate window size (60% of screen width)
            val windowWidth = (screenWidth * 0.6).toInt().coerceIn(getMinWidth(), getMaxWidth())
            val windowHeight = windowWidth * 9 / 16
            
            // Calculate center position
            val xPos = (screenWidth - windowWidth) / 2
            val yPos = (screenHeight - windowHeight) / 2
            
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            params = WindowManager.LayoutParams(
                windowWidth,
                windowHeight,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = xPos
                y = yPos
            }
            
            windowManager?.addView(floatingView, params)
            
            // Setup UI references
            controlsContainer = floatingView?.findViewById(R.id.top_controls_container)
            bottomControlsContainer = floatingView?.findViewById(R.id.bottom_controls_container)
            lockOverlay = floatingView?.findViewById(R.id.lock_overlay)
            unlockButton = floatingView?.findViewById(R.id.unlock_button)
            playerView = floatingView?.findViewById(R.id.player_view)
            
            // FIXED: Get references to seek bar and time displays
            seekBar = floatingView?.findViewById(R.id.exo_progress)
            currentTimeText = floatingView?.findViewById(R.id.exo_position)
            durationText = floatingView?.findViewById(R.id.exo_duration)
            
            val titleView = floatingView?.findViewById<TextView>(R.id.tv_title)
            titleView?.text = title
            
            // FIXED: Initialize player BEFORE setting up controls
            if (useTransferredPlayer && PlayerHolder.getInstance()?.player != null) {
                player = PlayerHolder.getInstance()?.player
                android.util.Log.d("FloatingPlayerService", "Using transferred player instance")
            } else {
                player = ExoPlayer.Builder(this).build()
                android.util.Log.d("FloatingPlayerService", "Created new player for URL: $streamUrl")
                
                if (streamUrl.isNotEmpty()) {
                    val mediaItem = MediaItem.fromUri(streamUrl)
                    player?.setMediaItem(mediaItem)
                    player?.prepare()
                    player?.playWhenReady = true
                    
                    if (currentPlaybackPosition > 0) {
                        player?.seekTo(currentPlaybackPosition)
                    }
                }
            }
            
            playerView?.player = player
            
            setupControls()
            setupGestures()
            
            // FIXED: Start updating time displays
            hideControlsHandler.post(updateTimeRunnable)
            
        } catch (e: Exception) {
            android.util.Log.e("FloatingPlayerService", "Error creating floating view", e)
            e.printStackTrace()
            stopSelf()
        }
    }

    // FIXED: Add method to update time displays
    private fun updateTimeDisplays() {
        val currentPlayer = player ?: return
        
        val currentPosition = currentPlayer.currentPosition
        val duration = currentPlayer.duration
        
        // Update current time
        currentTimeText?.text = formatTime(currentPosition)
        
        // Update duration (only if available, otherwise show "--:--")
        if (duration > 0 && duration != androidx.media3.common.C.TIME_UNSET) {
            durationText?.text = formatTime(duration)
        } else {
            durationText?.text = "--:--"
        }
        
        // The seekbar will automatically update itself if it's connected to the player
        // But we need to make sure it's enabled and working
        seekBar?.let { bar ->
            // Enable the seekbar for VOD content
            if (duration > 0 && duration != androidx.media3.common.C.TIME_UNSET) {
                bar.isEnabled = true
                // Set position and duration manually to ensure it works
                bar.setPosition(currentPosition)
                bar.setDuration(duration)
            } else {
                // For live streams, disable seeking
                bar.isEnabled = false
            }
        }
    }
    
    // FIXED: Add time formatting helper
    private fun formatTime(timeMs: Long): String {
        if (timeMs < 0 || timeMs == androidx.media3.common.C.TIME_UNSET) {
            return "00:00"
        }
        
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun setupControls() {
        val closeBtn = floatingView?.findViewById<ImageButton>(R.id.btn_close)
        val fullscreenBtn = floatingView?.findViewById<ImageButton>(R.id.btn_fullscreen)
        val lockBtn = floatingView?.findViewById<ImageButton>(R.id.btn_lock)
        val muteBtn = floatingView?.findViewById<ImageButton>(R.id.btn_mute)
        val playPauseBtn = floatingView?.findViewById<ImageButton>(R.id.btn_play_pause)
        val seekBackBtn = floatingView?.findViewById<ImageButton>(R.id.btn_seek_back)
        val seekForwardBtn = floatingView?.findViewById<ImageButton>(R.id.btn_seek_forward)
        val resizeBtn = floatingView?.findViewById<ImageButton>(R.id.btn_resize)
        
        closeBtn?.setOnClickListener {
            stopSelf()
        }
        
        fullscreenBtn?.setOnClickListener {
            try {
                currentChannel?.let { channel ->
                    currentPlaybackPosition = player?.currentPosition ?: 0L
                    FloatingPlayerActivity.startWithChannel(this, channel)
                    stopSelf()
                }
            } catch (e: Exception) {
                android.util.Log.e("FloatingPlayerService", "Error opening fullscreen", e)
            }
        }
        
        lockBtn?.setOnClickListener {
            toggleLock()
        }
        
        unlockButton?.setOnClickListener {
            toggleLock()
        }
        
        muteBtn?.setOnClickListener {
            isMuted = !isMuted
            player?.volume = if (isMuted) 0f else 1f
            muteBtn.setImageResource(
                if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
            )
        }
        
        playPauseBtn?.setOnClickListener {
            if (player?.isPlaying == true) {
                player?.pause()
            } else {
                player?.play()
            }
        }
        
        seekBackBtn?.setOnClickListener {
            player?.let { p ->
                val newPosition = (p.currentPosition - 10000).coerceAtLeast(0)
                p.seekTo(newPosition)
            }
        }
        
        seekForwardBtn?.setOnClickListener {
            player?.let { p ->
                val duration = p.duration
                val newPosition = if (duration > 0 && duration != androidx.media3.common.C.TIME_UNSET) {
                    (p.currentPosition + 10000).coerceAtMost(duration)
                } else {
                    p.currentPosition + 10000
                }
                p.seekTo(newPosition)
            }
        }
        
        // FIXED: Setup seek bar listener
        seekBar?.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                // Pause updates while scrubbing
                hideControlsHandler.removeCallbacks(updateTimeRunnable)
            }
            
            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                // Update time display during scrubbing
                currentTimeText?.text = formatTime(position)
            }
            
            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                if (!canceled) {
                    player?.seekTo(position)
                }
                // Resume updates after scrubbing
                hideControlsHandler.post(updateTimeRunnable)
            }
        })
        
        // Resize button touch handling
        var resizeInitialWidth = 0
        var resizeInitialHeight = 0
        var resizeInitialTouchX = 0f
        var resizeInitialTouchY = 0f
        
        resizeBtn?.setOnTouchListener { view, event ->
            if (controlsLocked) return@setOnTouchListener false
            
            params?.let { p ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        resizeInitialWidth = p.width
                        resizeInitialHeight = p.height
                        resizeInitialTouchX = event.rawX
                        resizeInitialTouchY = event.rawY
                        true
                    }
                    
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - resizeInitialTouchX
                        val dy = event.rawY - resizeInitialTouchY
                        val delta = ((dx + dy) / 2).toInt()
                        var newWidth = resizeInitialWidth + delta
                        newWidth = newWidth.coerceIn(getMinWidth(), getMaxWidth())
                        val newHeight = newWidth * 9 / 16
                        
                        if (newWidth != p.width || newHeight != p.height) {
                            p.width = newWidth
                            p.height = newHeight
                            windowManager?.updateViewLayout(floatingView, p)
                        }
                        true
                    }
                    
                    MotionEvent.ACTION_UP -> {
                        true
                    }
                    
                    else -> false
                }
            } ?: false
        }
        
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playPauseBtn?.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        var isDragging = false
        var hasMoved = false
        
        playerView?.setOnTouchListener { view, event ->
            // When locked, don't allow dragging or interaction
            if (controlsLocked) {
                return@setOnTouchListener true
            }
            
            params?.let { p ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = p.x
                        initialY = p.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        hasMoved = false
                        true
                    }
                    
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isDragging = true
                            hasMoved = true
                            p.x = initialX + dx
                            p.y = initialY + dy
                            windowManager?.updateViewLayout(floatingView, p)
                        }
                        true
                    }
                    
                    MotionEvent.ACTION_UP -> {
                        if (!hasMoved) {
                            // Tap to toggle controls
                            if (controlsVisible) {
                                hideControls()
                            } else {
                                showControls()
                            }
                        }
                        isDragging = false
                        hasMoved = false
                        true
                    }
                    
                    else -> false
                }
            } ?: false
        }
    }
    
    private fun showControls() {
        if (controlsLocked) return
        
        controlsContainer?.visibility = View.VISIBLE
        bottomControlsContainer?.visibility = View.VISIBLE
        controlsVisible = true
        
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, HIDE_CONTROLS_DELAY)
    }
    
    private fun hideControls() {
        controlsContainer?.visibility = View.GONE
        bottomControlsContainer?.visibility = View.GONE
        controlsVisible = false
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
    }
    
    private fun toggleLock() {
        controlsLocked = !controlsLocked
        val lockBtn = floatingView?.findViewById<ImageButton>(R.id.btn_lock)
        
        if (controlsLocked) {
            // Lock: hide controls, show overlay and unlock button
            hideControls()
            lockOverlay?.apply {
                visibility = View.VISIBLE
                isClickable = true
                isFocusable = true
            }
            showUnlockButton()
            lockBtn?.setImageResource(R.drawable.ic_lock_closed)
        } else {
            // Unlock: show controls, hide overlay and unlock button
            lockOverlay?.visibility = View.GONE
            hideUnlockButton()
            lockBtn?.setImageResource(R.drawable.ic_lock_open)
            showControls()
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
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.removeCallbacks(hideUnlockButtonRunnable)
        // FIXED: Stop updating time displays
        hideControlsHandler.removeCallbacks(updateTimeRunnable)
        
        player?.release()
        player = null
        
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
            floatingView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
