package com.livetvpro.ui.player

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import kotlin.math.abs

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
    private val HIDE_CONTROLS_DELAY = 3000L // 3 seconds
    
    // UI components
    private var controlsContainer: View? = null
    private var bottomControlsContainer: View? = null
    private var params: WindowManager.LayoutParams? = null
    
    // Store the channel data for passing to FloatingPlayerActivity
    private var currentChannel: Channel? = null
    
    // Store the current playback position for seamless transition
    private var currentPlaybackPosition: Long = 0L
    
    // Size limits (in DP for better cross-device compatibility)
    private fun getMinWidth() = dpToPx(180)   // Small but controls still usable
    private fun getMaxWidth() = dpToPx(400)   // Large but not fullscreen
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
                
                // Pass the entire channel object and playback position
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
                android.util.Log.e("FloatingPlayer", "Error starting service", e)
                e.printStackTrace()
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
        
        // Extract the channel object
        currentChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_CHANNEL)
        }
        
        val streamUrl = intent?.getStringExtra(EXTRA_STREAM_URL) ?: ""
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Live Stream"
        currentPlaybackPosition = intent?.getLongExtra(EXTRA_PLAYBACK_POSITION, 0L) ?: 0L
        
        if (streamUrl.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        
        startForeground(NOTIFICATION_ID, createNotification(title))
        
        if (floatingView == null) {
            createFloatingView(streamUrl, title)
        }
        
        return START_STICKY
    }

    private fun createFloatingView(streamUrl: String, title: String) {
        try {
            // Use ContextThemeWrapper for proper theming
            val themeContext = android.view.ContextThemeWrapper(this, R.style.Theme_LiveTVPro)
            floatingView = LayoutInflater.from(themeContext).inflate(R.layout.floating_player_window, null)
            
            // Calculate proper initial size (16:9 aspect ratio)
            val metrics = resources.displayMetrics
            val initialWidth = dpToPx(320)  // Start at a good viewing size
            val initialHeight = (initialWidth * 9 / 16)  // Maintain 16:9 aspect ratio
            
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            params = WindowManager.LayoutParams(
                initialWidth,
                initialHeight,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,  // Allows dragging slightly off-screen
                PixelFormat.TRANSLUCENT
            )
            
            // FIXED: Position at screen center (both horizontally AND vertically)
            params?.apply {
                gravity = Gravity.TOP or Gravity.START
                // Center horizontally: (screen width - window width) / 2
                x = (metrics.widthPixels - initialWidth) / 2
                // Center vertically: (screen height - window height) / 2
                y = (metrics.heightPixels - initialHeight) / 2
            }
            
            windowManager?.addView(floatingView, params)
            
            setupPlayer(streamUrl, title)
            setupControls()
            setupGestures()
            
        } catch (e: Exception) {
            android.util.Log.e("FloatingPlayer", "Error creating floating view", e)
            e.printStackTrace()
            android.widget.Toast.makeText(
                this,
                "Failed to create floating player: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
            stopSelf()
        }
    }

    private fun setupPlayer(streamUrl: String, title: String) {
        try {
            player = ExoPlayer.Builder(this).build()
            
            playerView = floatingView?.findViewById(R.id.player_view)
            
            playerView?.apply {
                player = this@FloatingPlayerService.player
                // DISABLE built-in controls - we're using custom controls only
                useController = false
                controllerAutoShow = false
            }
            
            val titleText = floatingView?.findViewById<TextView>(R.id.tv_title)
            titleText?.text = title
            
            val mediaItem = MediaItem.fromUri(streamUrl)
            player?.apply {
                setMediaItem(mediaItem)
                prepare()
                
                // FIXED: Seek to the saved position if available
                if (currentPlaybackPosition > 0) {
                    seekTo(currentPlaybackPosition)
                }
                
                playWhenReady = true
            }
            
        } catch (e: Exception) {
            android.util.Log.e("FloatingPlayer", "Error setting up player", e)
            e.printStackTrace()
        }
    }

    private fun setupControls() {
        controlsContainer = floatingView?.findViewById(R.id.top_controls_container)
        bottomControlsContainer = floatingView?.findViewById(R.id.bottom_controls_container)
        
        val closeBtn = floatingView?.findViewById<ImageButton>(R.id.btn_close)
        val fullscreenBtn = floatingView?.findViewById<ImageButton>(R.id.btn_fullscreen)
        val muteBtn = floatingView?.findViewById<ImageButton>(R.id.btn_mute)
        val lockBtn = floatingView?.findViewById<ImageButton>(R.id.btn_lock)
        val playPauseBtn = floatingView?.findViewById<ImageButton>(R.id.btn_play_pause)
        val seekBackBtn = floatingView?.findViewById<ImageButton>(R.id.btn_seek_back)
        val seekForwardBtn = floatingView?.findViewById<ImageButton>(R.id.btn_seek_forward)
        
        closeBtn?.setOnClickListener {
            stopSelf()
        }
        
        // FIXED: Pass channel and preserve playback position for seamless transition
        fullscreenBtn?.setOnClickListener {
            if (currentChannel != null) {
                // Save current playback position
                val position = player?.currentPosition ?: 0L
                
                // Pass channel and position to FloatingPlayerActivity
                val intent = Intent(this, FloatingPlayerActivity::class.java).apply {
                    putExtra("extra_channel", currentChannel)
                    putExtra("playback_position", position)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                
                // FIXED: Small delay to allow activity to start before stopping service
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    stopSelf()
                }, 300) // Reduced to 300ms for faster transition
            } else {
                android.widget.Toast.makeText(
                    this,
                    "Unable to open fullscreen mode",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        
        muteBtn?.setOnClickListener {
            isMuted = !isMuted
            player?.volume = if (isMuted) 0f else 1f
            muteBtn.setImageResource(
                if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
            )
            showControls() // Reset timer
        }
        
        lockBtn?.setOnClickListener {
            controlsLocked = !controlsLocked
            lockBtn.setImageResource(
                if (controlsLocked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open
            )
            
            if (controlsLocked) {
                hideControls()
            } else {
                showControls()
            }
        }
        
        playPauseBtn?.setOnClickListener {
            if (player?.isPlaying == true) {
                player?.pause()
                playPauseBtn.setImageResource(R.drawable.ic_play)
            } else {
                player?.play()
                playPauseBtn.setImageResource(R.drawable.ic_pause)
            }
            showControls() // Reset timer
        }
        
        seekBackBtn?.setOnClickListener {
            player?.seekBack()
            showControls() // Reset timer
        }
        
        seekForwardBtn?.setOnClickListener {
            player?.seekForward()
            showControls() // Reset timer
        }
        
        // Resize button - drag to resize (no tap action)
        val resizeBtn = floatingView?.findViewById<ImageButton>(R.id.btn_resize)
        
        var resizeInitialWidth = 0
        var resizeInitialHeight = 0
        var resizeInitialTouchX = 0f
        var resizeInitialTouchY = 0f
        
        resizeBtn?.setOnTouchListener { view, event ->
            if (controlsLocked) return@setOnTouchListener false
            
            params?.let { p ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // Record initial size and touch position
                        resizeInitialWidth = p.width
                        resizeInitialHeight = p.height
                        resizeInitialTouchX = event.rawX
                        resizeInitialTouchY = event.rawY
                        true
                    }
                    
                    MotionEvent.ACTION_MOVE -> {
                        // Calculate how much finger moved in X and Y
                        val dx = event.rawX - resizeInitialTouchX
                        val dy = event.rawY - resizeInitialTouchY
                        
                        // Use average of both directions for smoother resize
                        val delta = ((dx + dy) / 2).toInt()
                        
                        // Calculate new width
                        var newWidth = resizeInitialWidth + delta
                        
                        // Apply width bounds
                        newWidth = newWidth.coerceIn(getMinWidth(), getMaxWidth())
                        
                        // Calculate height maintaining 16:9 ratio
                        val newHeight = newWidth * 9 / 16
                        
                        // Only update if size actually changed (prevents micro-jumps)
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
        
        // Update play/pause icon based on player state
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
        // Attach touch listener to the player view for dragging
        var isDragging = false
        var hasMoved = false
        
        playerView?.setOnTouchListener { view, event ->
            // If locked, only allow unlock tap
            if (controlsLocked) {
                if (event.action == MotionEvent.ACTION_UP && !hasMoved) {
                    // Unlock on tap
                    controlsLocked = false
                    floatingView?.findViewById<ImageButton>(R.id.btn_lock)?.setImageResource(R.drawable.ic_lock_open)
                    showControls()
                }
                hasMoved = false
                return@setOnTouchListener true
            }
            
            params?.let { p ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // Record initial positions
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
                        
                        // Check if user actually moved (threshold to distinguish from tap)
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isDragging = true
                            hasMoved = true
                            
                            // Update window position
                            p.x = initialX + dx
                            p.y = initialY + dy
                            windowManager?.updateViewLayout(floatingView, p)
                        }
                        true
                    }
                    
                    MotionEvent.ACTION_UP -> {
                        // If it was just a tap (not a drag), toggle controls
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
    
    private fun showControls() {
        if (controlsLocked) return
        
        controlsContainer?.visibility = View.VISIBLE
        bottomControlsContainer?.visibility = View.VISIBLE
        controlsVisible = true
        
        // Reset auto-hide timer
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, HIDE_CONTROLS_DELAY)
    }
    
    private fun hideControls() {
        controlsContainer?.visibility = View.GONE
        bottomControlsContainer?.visibility = View.GONE
        controlsVisible = false
        
        // Cancel auto-hide timer
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
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
        
        // Clean up auto-hide timer
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        
        player?.release()
        player = null
        
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
            floatingView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
