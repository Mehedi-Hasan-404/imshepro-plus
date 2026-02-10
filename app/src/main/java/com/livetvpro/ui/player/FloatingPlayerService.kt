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
                // If width > height, we're in landscape, swap them to get portrait dimensions
                screenWidth = if (width < height) width else height
                screenHeight = if (width < height) height else width
            } else {
                // Android 10 and below
                val display = windowManager?.defaultDisplay
                val size = Point()
                display?.getSize(size)
                
                // CRITICAL FIX: Ensure we always use portrait dimensions
                screenWidth = if (size.x < size.y) size.x else size.y
                screenHeight = if (size.x < size.y) size.y else size.x
            }
            
            android.util.Log.d("FloatingPlayerService", "Screen dimensions (portrait): ${screenWidth}x${screenHeight}")
            
            // ============================================
            // FIX #1: Restore saved size when opening
            // ============================================
            // Get saved size, or use default
            val savedWidth = preferencesManager.getFloatingPlayerWidth()
            val savedHeight = preferencesManager.getFloatingPlayerHeight()
            
            val initialWidth: Int
            val initialHeight: Int
            
            if (savedWidth > 0 && savedHeight > 0) {
                // Use saved size (only if user resized during this session)
                initialWidth = savedWidth.coerceIn(getMinWidth(), getMaxWidth())
                initialHeight = savedHeight.coerceIn(getMinHeight(), getMaxHeight())
                android.util.Log.d("FloatingPlayerService", "Using saved size: ${initialWidth}x${initialHeight}")
            } else {
                // Use default size - 60% of screen width (adapts to different screen sizes)
                initialWidth = (screenWidth * 0.6f).toInt().coerceIn(getMinWidth(), getMaxWidth())
                initialHeight = initialWidth * 9 / 16
                android.util.Log.d("FloatingPlayerService", "Using default size (60% screen): ${initialWidth}x${initialHeight}")
            }
            
            // ============================================
            // Restore saved position or center
            // ============================================
            val savedX = preferencesManager.getFloatingPlayerX()
            val savedY = preferencesManager.getFloatingPlayerY()
            
            val initialX: Int
            val initialY: Int
            
            // Check if saved position is valid (not default/cleared values)
            if (savedX != 50 && savedY != 100) {
                // Use saved position
                initialX = savedX
                initialY = savedY
                android.util.Log.d("FloatingPlayerService", "Using saved position: ($initialX, $initialY)")
            } else {
                // Center the window (in portrait orientation)
                initialX = (screenWidth - initialWidth) / 2
                initialY = (screenHeight - initialHeight) / 2
                android.util.Log.d("FloatingPlayerService", "Centering window at: ($initialX, $initialY)")
            }
            
            params = WindowManager.LayoutParams(
                initialWidth,
                initialHeight,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initialX
                y = initialY
            }
            
            windowManager?.addView(floatingView, params)
            
            if (useTransferredPlayer) {
                setupTransferredPlayer(title)
            } else {
                setupPlayer(streamUrl, title)
            }
            
            setupControls()
            setupGestures()
            
        } catch (e: Exception) {
            android.util.Log.e("FloatingPlayerService", "Error creating floating view", e)
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun setupPlayer(streamUrl: String, title: String) {
        try {
            player = ExoPlayer.Builder(this).build()
            
            playerView = floatingView?.findViewById(R.id.player_view)
            playerView?.apply {
                player = this@FloatingPlayerService.player
                // Enable controller - ExoPlayer manages BOTH top and bottom controls automatically
                useController = true
                controllerAutoShow = true  // Show controls on start
                controllerShowTimeoutMs = 3000 // Hide after 3 seconds
            }
            
            val titleText = playerView?.findViewById<TextView>(R.id.tv_title)
            titleText?.text = title
            
            val mediaItem = MediaItem.fromUri(streamUrl)
            player?.setMediaItem(mediaItem)
            player?.prepare()
            
            if (currentPlaybackPosition > 0) {
                player?.seekTo(currentPlaybackPosition)
            }
            
            player?.playWhenReady = true
            
        } catch (e: Exception) {
            android.util.Log.e("FloatingPlayerService", "Error setting up player", e)
            e.printStackTrace()
            android.widget.Toast.makeText(
                this,
                "Failed to setup player: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
            stopSelf()
        }
    }

    private fun setupTransferredPlayer(title: String) {
        try {
            // FIXED: Retrieve the transferred player (no loading!)
            val (transferredPlayer, transferredUrl, transferredName) = PlayerHolder.retrievePlayer()
            
            if (transferredPlayer != null) {
                android.util.Log.d("FloatingPlayerService", "Using transferred player - NO LOADING!")
                
                // Use the existing player instance
                player = transferredPlayer
                
                playerView = floatingView?.findViewById(R.id.player_view)
                playerView?.apply {
                    player = this@FloatingPlayerService.player
                    // Enable controller - ExoPlayer manages BOTH top and bottom controls automatically
                    useController = true
                    controllerAutoShow = true  // Show controls on start
                    controllerShowTimeoutMs = 3000 // Hide after 3 seconds
                }
                
                val titleText = playerView?.findViewById<TextView>(R.id.tv_title)
                titleText?.text = transferredName ?: title
                
                // Player is already prepared and playing!
                // No need to load, prepare, or seek - it continues seamlessly!
                
                // Clear the holder now that service has taken ownership
                PlayerHolder.clearReferences()
                
            } else {
                // Fallback to normal setup if transfer failed
                android.util.Log.w("FloatingPlayerService", "No transferred player, creating new one")
                val url = currentChannel?.links?.firstOrNull()?.url ?: ""
                setupPlayer(url, title)
            }
            
        } catch (e: Exception) {
            android.util.Log.e("FloatingPlayerService", "Error using transferred player", e)
            // Fallback to creating new player
            val url = currentChannel?.links?.firstOrNull()?.url ?: ""
            setupPlayer(url, title)
        }
    }


    private fun setupControls() {
        // Note: All controls (top and bottom) are now inside the ExoPlayer controller
        lockOverlay = floatingView?.findViewById(R.id.lock_overlay)
        unlockButton = floatingView?.findViewById(R.id.unlock_button)
        
        // Find controls inside the ExoPlayer controller view
        val closeBtn = playerView?.findViewById<ImageButton>(R.id.btn_close)
        val fullscreenBtn = playerView?.findViewById<ImageButton>(R.id.btn_fullscreen)
        val muteBtn = playerView?.findViewById<ImageButton>(R.id.btn_mute)
        val lockBtn = playerView?.findViewById<ImageButton>(R.id.btn_lock)
        val playPauseBtn = playerView?.findViewById<ImageButton>(R.id.btn_play_pause)
        val seekBackBtn = playerView?.findViewById<ImageButton>(R.id.btn_seek_back)
        val seekForwardBtn = playerView?.findViewById<ImageButton>(R.id.btn_seek_forward)
        
        // Setup lock overlay and unlock button
        unlockButton?.setOnClickListener {
            toggleLock()
        }
        
        lockOverlay?.setOnClickListener {
            if (unlockButton?.visibility == View.VISIBLE) {
                hideUnlockButton()
            } else {
                showUnlockButton()
            }
        }
        
        closeBtn?.setOnClickListener {
            // FIXED: Clear saved position AND size when user closes, so it resets to defaults
            preferencesManager.setFloatingPlayerX(50)  // Reset to default
            preferencesManager.setFloatingPlayerY(100) // Reset to default
            preferencesManager.setFloatingPlayerWidth(0)  // Reset to 60% screen width
            preferencesManager.setFloatingPlayerHeight(0) // Reset to 60% screen width
            android.util.Log.d("FloatingPlayerService", "Position and size cleared - will reset to 60% screen on next open")
            stopSelf()
        }
        
        fullscreenBtn?.setOnClickListener {
            if (currentChannel != null) {
                val currentPlayer = player
                val channel = currentChannel  // Local copy to avoid smart cast issues
                
                if (currentPlayer != null && channel != null) {
                    // ============================================
                    // FIX #2: Save current size before going fullscreen
                    // ============================================
                    params?.let { p ->
                        preferencesManager.setFloatingPlayerWidth(p.width)
                        preferencesManager.setFloatingPlayerHeight(p.height)
                        android.util.Log.d("FloatingPlayerService", "Saved size before fullscreen: ${p.width}x${p.height}")
                    }
                    
                    // FIXED: Transfer player to activity - no recreation!
                    val streamUrl = channel.links?.firstOrNull()?.url ?: ""
                    PlayerHolder.transferPlayer(currentPlayer, streamUrl, channel.name)
                    
                    android.util.Log.d("FloatingPlayerService", "Player transferred to activity")
                    
                    val intent = Intent(this, FloatingPlayerActivity::class.java).apply {
                        putExtra("extra_channel", channel)
                        putExtra("use_transferred_player", true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    
                    // Release local reference (activity now owns it)
                    player = null
                    stopSelf()
                }
            }
        }
        
        muteBtn?.setOnClickListener {
            val currentPlayer = player
            if (currentPlayer != null) {
                isMuted = !isMuted
                currentPlayer.volume = if (isMuted) 0f else 1f
                muteBtn.setImageResource(
                    if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
                )
            }
        }
        
        lockBtn?.setOnClickListener {
            toggleLock()
        }
        
        playPauseBtn?.setOnClickListener {
            val currentPlayer = player
            if (currentPlayer != null) {
                if (currentPlayer.isPlaying) {
                    currentPlayer.pause()
                } else {
                    currentPlayer.play()
                }
            }
        }
        
        seekBackBtn?.setOnClickListener {
            player?.seekBack()
        }
        
        seekForwardBtn?.setOnClickListener {
            player?.seekForward()
        }
        
        // Resize button
        val resizeBtn = playerView?.findViewById<ImageButton>(R.id.btn_resize)
        var resizeInitialWidth = 0
        var resizeInitialHeight = 0
        var resizeInitialTouchX = 0f
        var resizeInitialTouchY = 0f
        
        resizeBtn?.setOnTouchListener { v, event ->
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
                        // ============================================
                        // FIX #3: Save size after resizing
                        // ============================================
                        preferencesManager.setFloatingPlayerWidth(p.width)
                        preferencesManager.setFloatingPlayerHeight(p.height)
                        android.util.Log.d("FloatingPlayerService", "Saved size after resize: ${p.width}x${p.height}")
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

    // ============================================
    // CRITICAL FIX #2: Touch Listener Implementation
    // ============================================
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        // Track drag state
        var isDragging = false
        var hasMoved = false
        
        // CRITICAL: Attach to PlayerView to always intercept touches
        // Window should ALWAYS be draggable, even when controls are locked
        playerView?.setOnTouchListener { view, event ->
            // When locked, only handle dragging, ignore all other interactions
            if (controlsLocked) {
                params?.let { p ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = p.x
                            initialY = p.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            hasMoved = false
                            
                            // ============================================
                            // FIX #4: Only show unlock button on DOWN, not always
                            // ============================================
                            // Don't call showUnlockButton() here to avoid always showing
                            true  // Consume to start tracking
                        }
                        
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()
                            
                            // Start dragging after threshold
                            if (abs(dx) > 10 || abs(dy) > 10) {
                                isDragging = true
                                hasMoved = true
                                
                                // Move the window
                                p.x = initialX + dx
                                p.y = initialY + dy
                                
                                // Save position in real-time
                                preferencesManager.setFloatingPlayerX(p.x)
                                preferencesManager.setFloatingPlayerY(p.y)
                                
                                windowManager?.updateViewLayout(floatingView, p)
                            }
                            true  // Consume all move events when locked
                        }
                        
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (!hasMoved) {
                                // ============================================
                                // FIX #5: Only show unlock on tap, not always
                                // ============================================
                                // Tap when locked - toggle unlock button visibility
                                if (unlockButton?.visibility == View.VISIBLE) {
                                    hideUnlockButton()
                                } else {
                                    showUnlockButton()
                                }
                            } else {
                                // Save final position after drag
                                preferencesManager.setFloatingPlayerX(p.x)
                                preferencesManager.setFloatingPlayerY(p.y)
                                android.util.Log.d("FloatingPlayerService", "Dragged while locked - saved position: (${p.x}, ${p.y})")
                            }
                            
                            isDragging = false
                            hasMoved = false
                            true  // Consume when locked
                        }
                        
                        else -> true  // Consume all events when locked
                    }
                } ?: true
            } else {
                // When unlocked, allow dragging AND control interaction
                params?.let { p ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = p.x
                            initialY = p.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            hasMoved = false
                            true  // Start tracking
                        }
                        
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()
                            
                            // Start dragging after threshold
                            if (abs(dx) > 10 || abs(dy) > 10) {
                                isDragging = true
                                hasMoved = true
                                
                                // Move the window
                                p.x = initialX + dx
                                p.y = initialY + dy
                                
                                // Save position in real-time
                                preferencesManager.setFloatingPlayerX(p.x)
                                preferencesManager.setFloatingPlayerY(p.y)
                                
                                windowManager?.updateViewLayout(floatingView, p)
                            }
                            
                            // If dragging, consume. Otherwise let controls handle
                            isDragging
                        }
                        
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (!hasMoved) {
                                // ============================================
                                // FIX #6: Toggle controls visibility properly
                                // ============================================
                                // This was a tap - toggle controls visibility
                                val currentlyVisible = playerView?.isControllerFullyVisible == true
                                
                                if (currentlyVisible) {
                                    playerView?.hideController()
                                } else {
                                    playerView?.showController()
                                }
                            } else {
                                // Save final position after drag
                                preferencesManager.setFloatingPlayerX(p.x)
                                preferencesManager.setFloatingPlayerY(p.y)
                                android.util.Log.d("FloatingPlayerService", "Dragged while unlocked - saved position: (${p.x}, ${p.y})")
                            }
                            
                            val wasMoving = hasMoved
                            isDragging = false
                            hasMoved = false
                            
                            // Consume only if we were dragging
                            wasMoving
                        }
                        
                        else -> false
                    }
                } ?: false
            }
        }
        
        // Setup lock overlay to pass through touches (it should not interfere)
        lockOverlay?.apply {
            isClickable = false
            isFocusable = false
        }
    }

    

    private fun showControls() {
        if (controlsLocked) return
        
        // Show ExoPlayer's controller - it will auto-hide after timeout
        // The visibility listener will automatically show top controls
        playerView?.showController()
    }
    
    private fun hideControls() {
        // Hide ExoPlayer's controller
        // The visibility listener will automatically hide top controls
        playerView?.hideController()
    }
    
    private fun toggleLock() {
        val lockBtn = playerView?.findViewById<ImageButton>(R.id.btn_lock)
        
        if (controlsLocked) {
            // Currently locked → UNLOCK
            controlsLocked = false
            lockOverlay?.visibility = View.GONE
            hideUnlockButton()
            lockBtn?.setImageResource(R.drawable.ic_lock_open)
            showControls()
            android.util.Log.d("FloatingPlayerService", "Controls unlocked")
        } else {
            // Currently unlocked → LOCK
            controlsLocked = true
            lockBtn?.setImageResource(R.drawable.ic_lock_closed)
            
            // Hide ExoPlayer controller
            playerView?.hideController()
            
            // Show lock overlay - CRITICAL: Make it non-interactive
            lockOverlay?.apply {
                visibility = View.VISIBLE
                isClickable = false   // Doesn't consume touches
                isFocusable = false   // Doesn't consume touches
            }
            
            // ============================================
            // FIX #7: Don't auto-show unlock button when locking
            // ============================================
            // DON'T show unlock button automatically - let user tap to show it
            // showUnlockButton()  // REMOVED THIS LINE
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
