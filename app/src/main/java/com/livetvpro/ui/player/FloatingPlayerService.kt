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
import androidx.media3.ui.DefaultTimeBar  // Add this import
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
    
    // ====== ADDED: Seek bar and time display components ======
    private var timeBar: DefaultTimeBar? = null
    private var positionView: TextView? = null
    private var durationView: TextView? = null
    private val updateProgressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            updateProgressHandler.postDelayed(this, 500) // Update every 500ms
        }
    }
    // ========================================================
    
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
    private fun getMinWidth() = dpToPx(240)
    private fun getMaxWidth() = dpToPx(400)
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
            
            // Get screen dimensions for proper positioning
            val screenWidth: Int
            val screenHeight: Int
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = windowManager?.currentWindowMetrics
                val bounds = windowMetrics?.bounds
                val width = bounds?.width() ?: 1080
                val height = bounds?.height() ?: 1920
                
                if (width < height) {
                    screenWidth = width
                    screenHeight = height
                } else {
                    screenWidth = height
                    screenHeight = width
                }
            } else {
                @Suppress("DEPRECATION")
                val display = windowManager?.defaultDisplay
                val size = Point()
                @Suppress("DEPRECATION")
                display?.getSize(size)
                
                if (size.x < size.y) {
                    screenWidth = size.x
                    screenHeight = size.y
                } else {
                    screenWidth = size.y
                    screenHeight = size.x
                }
            }
            
            val defaultWidth = dpToPx(280)
            val defaultHeight = defaultWidth * 9 / 16
            
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            params = WindowManager.LayoutParams(
                defaultWidth,
                defaultHeight,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (screenWidth - defaultWidth) / 2
                y = screenHeight / 3
            }
            
            windowManager?.addView(floatingView, params)
            
            // Initialize UI components
            playerView = floatingView?.findViewById(R.id.player_view)
            controlsContainer = floatingView?.findViewById(R.id.top_controls_container)
            bottomControlsContainer = floatingView?.findViewById(R.id.bottom_controls_container)
            lockOverlay = floatingView?.findViewById(R.id.lock_overlay)
            unlockButton = floatingView?.findViewById(R.id.unlock_button)
            
            // ====== ADDED: Initialize seek bar and time views ======
            timeBar = floatingView?.findViewById(R.id.exo_progress)
            positionView = floatingView?.findViewById(R.id.exo_position)
            durationView = floatingView?.findViewById(R.id.exo_duration)
            // ========================================================
            
            val titleView = floatingView?.findViewById<TextView>(R.id.tv_title)
            titleView?.text = title
            
            // Check if we should use transferred player or create new one
            if (useTransferredPlayer) {
                android.util.Log.d("FloatingPlayerService", "Attempting to use transferred player")
                player = PlayerHolder.getPlayer(this)
                
                if (player == null) {
                    android.util.Log.w("FloatingPlayerService", "No transferred player available, creating new one")
                    player = ExoPlayer.Builder(this).build()
                    val mediaItem = MediaItem.fromUri(streamUrl)
                    player?.setMediaItem(mediaItem)
                    player?.prepare()
                    player?.playWhenReady = true
                } else {
                    android.util.Log.d("FloatingPlayerService", "Successfully got transferred player")
                }
            } else {
                android.util.Log.d("FloatingPlayerService", "Creating new player for URL: $streamUrl")
                player = ExoPlayer.Builder(this).build()
                val mediaItem = MediaItem.fromUri(streamUrl)
                player?.setMediaItem(mediaItem)
                player?.prepare()
                if (currentPlaybackPosition > 0) {
                    player?.seekTo(currentPlaybackPosition)
                }
                player?.playWhenReady = true
            }
            
            playerView?.player = player
            
            // ====== ADDED: Setup seek bar interaction ======
            setupTimeBar()
            // ================================================
            
            setupClickListeners()
            setupGestures()
            
            showControls()
            
            // ====== ADDED: Start progress updates ======
            updateProgressHandler.post(updateProgressRunnable)
            // ===========================================
            
        } catch (e: Exception) {
            android.util.Log.e("FloatingPlayerService", "Error creating floating view", e)
            e.printStackTrace()
            stopSelf()
        }
    }

    // ====== ADDED: New method to setup time bar ======
    private fun setupTimeBar() {
        timeBar?.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                // Stop auto-updates while scrubbing
                updateProgressHandler.removeCallbacks(updateProgressRunnable)
            }

            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                // Update position text while scrubbing
                positionView?.text = formatTime(position)
            }

            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                if (!canceled) {
                    // Seek to the selected position
                    player?.seekTo(position)
                }
                // Resume auto-updates
                updateProgressHandler.post(updateProgressRunnable)
            }
        })
    }

    // ====== ADDED: New method to update progress ======
    private fun updateProgress() {
        player?.let { p ->
            val currentPosition = p.currentPosition
            val duration = p.duration
            
            // Update seek bar
            timeBar?.setDuration(if (duration > 0) duration else 0)
            timeBar?.setPosition(currentPosition)
            
            // Update time displays
            positionView?.text = formatTime(currentPosition)
            durationView?.text = if (duration > 0) formatTime(duration) else "LIVE"
        }
    }

    // ====== ADDED: New method to format time ======
    private fun formatTime(millis: Long): String {
        if (millis < 0) return "00:00"
        
        val totalSeconds = millis / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
    // ==================================================

    private fun setupClickListeners() {
        val closeBtn = floatingView?.findViewById<ImageButton>(R.id.btn_close)
        val muteBtn = floatingView?.findViewById<ImageButton>(R.id.btn_mute)
        val fullscreenBtn = floatingView?.findViewById<ImageButton>(R.id.btn_fullscreen)
        val lockBtn = floatingView?.findViewById<ImageButton>(R.id.btn_lock)
        val playPauseBtn = floatingView?.findViewById<ImageButton>(R.id.btn_play_pause)
        val seekBackBtn = floatingView?.findViewById<ImageButton>(R.id.btn_seek_back)
        val seekForwardBtn = floatingView?.findViewById<ImageButton>(R.id.btn_seek_forward)
        val resizeBtn = floatingView?.findViewById<ImageButton>(R.id.btn_resize)
        
        closeBtn?.setOnClickListener {
            stopSelf()
        }
        
        muteBtn?.setOnClickListener {
            isMuted = !isMuted
            player?.volume = if (isMuted) 0f else 1f
            muteBtn.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        }
        
        fullscreenBtn?.setOnClickListener {
            // Return to fullscreen player
            currentChannel?.let { channel ->
                val intent = Intent(this, FloatingPlayerActivity::class.java).apply {
                    putExtra("channel", channel)
                    putExtra("playback_position", player?.currentPosition ?: 0L)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                stopSelf()
            }
        }
        
        lockBtn?.setOnClickListener {
            toggleLock()
        }
        
        unlockButton?.setOnClickListener {
            toggleLock()
        }
        
        playPauseBtn?.setOnClickListener {
            player?.let {
                if (it.isPlaying) {
                    it.pause()
                } else {
                    it.play()
                }
            }
        }
        
        seekBackBtn?.setOnClickListener {
            player?.let {
                val newPosition = (it.currentPosition - 10000).coerceAtLeast(0)
                it.seekTo(newPosition)
            }
            resetAutoHideTimer()
        }
        
        seekForwardBtn?.setOnClickListener {
            player?.let {
                val newPosition = (it.currentPosition + 10000).coerceAtMost(it.duration)
                it.seekTo(newPosition)
            }
            resetAutoHideTimer()
        }
        
        // Resize button with drag functionality
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
    
    // ====== ADDED: Helper method to reset auto-hide timer ======
    private fun resetAutoHideTimer() {
        if (controlsVisible && !controlsLocked) {
            hideControlsHandler.removeCallbacks(hideControlsRunnable)
            hideControlsHandler.postDelayed(hideControlsRunnable, HIDE_CONTROLS_DELAY)
        }
    }
    // ===========================================================
    
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
            hideControls()
            lockOverlay?.apply {
                visibility = View.VISIBLE
                isClickable = true
                isFocusable = true
            }
            showUnlockButton()
            lockBtn?.setImageResource(R.drawable.ic_lock_closed)
        } else {
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
        
        // ====== ADDED: Stop progress updates ======
        updateProgressHandler.removeCallbacks(updateProgressRunnable)
        // ==========================================
        
        player?.release()
        player = null
        
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
            floatingView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
