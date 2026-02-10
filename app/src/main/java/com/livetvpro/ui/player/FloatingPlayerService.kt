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
                // (smaller value = width, larger value = height)
                if (width < height) {
                    // Already in portrait
                    screenWidth = width
                    screenHeight = height
                } else {
                    // Currently landscape - swap to get portrait dimensions
                    screenWidth = height
                    screenHeight = width
                }
            } else {
                // Android 10 and below (API 29-)
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
            
            android.util.Log.d("FloatingPlayerService", "Portrait screen dimensions: $screenWidth x $screenHeight")
            
            // Calculate position (center of screen in portrait mode)
            val defaultWidth = dpToPx(300)
            val defaultHeight = defaultWidth * 9 / 16
            
            // FIXED: Restore saved position, or use default centered position
            val savedX = preferencesManager.getFloatingPlayerX()
            val savedY = preferencesManager.getFloatingPlayerY()
            
            // Check if saved position is valid (not default -1)
            val hasValidSavedPosition = savedX != -1 && savedY != -1
            
            val windowX: Int
            val windowY: Int
            
            if (hasValidSavedPosition) {
                // Use saved position
                windowX = savedX
                windowY = savedY
                android.util.Log.d("FloatingPlayerService", "Using saved position: ($windowX, $windowY)")
            } else {
                // Use centered position (in portrait mode)
                windowX = (screenWidth - defaultWidth) / 2
                windowY = (screenHeight - defaultHeight) / 2
                android.util.Log.d("FloatingPlayerService", "Using centered position: ($windowX, $windowY)")
            }
            
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            params = WindowManager.LayoutParams(
                defaultWidth,
                defaultHeight,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = windowX
                y = windowY
            }
            
            windowManager?.addView(floatingView, params)
            
            playerView = floatingView?.findViewById(R.id.floating_player_view)
            lockOverlay = floatingView?.findViewById(R.id.lock_overlay)
            unlockButton = floatingView?.findViewById(R.id.btn_unlock)
            
            // Setup UI
            setupPlayer(streamUrl, useTransferredPlayer)
            setupControls()
            setupGestures()
            
            // Set title
            floatingView?.findViewById<TextView>(R.id.tv_channel_name)?.text = title
            
            // Setup unlock button
            unlockButton?.setOnClickListener {
                toggleLock()
            }
            
            // Add ExoPlayer controller visibility listener to sync top controls
            playerView?.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                // Top controls (channel name, close, fullscreen) follow ExoPlayer controller visibility
                val topControls = floatingView?.findViewById<View>(R.id.top_controls)
                topControls?.visibility = visibility
            })
            
        } catch (e: Exception) {
            android.util.Log.e("FloatingPlayerService", "Error creating floating view", e)
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun setupPlayer(streamUrl: String, useTransferredPlayer: Boolean) {
        try {
            if (useTransferredPlayer) {
                // Use the transferred player from PlayerHolder
                player = PlayerHolder.getPlayer()
                android.util.Log.d("FloatingPlayerService", "Using transferred player from PlayerHolder")
            } else {
                // Create new player
                player = ExoPlayer.Builder(this).build().apply {
                    setMediaItem(MediaItem.fromUri(streamUrl))
                    prepare()
                    playWhenReady = true
                    if (currentPlaybackPosition > 0) {
                        seekTo(currentPlaybackPosition)
                    }
                }
                android.util.Log.d("FloatingPlayerService", "Created new player with URL: $streamUrl")
            }
            
            playerView?.player = player
            
        } catch (e: Exception) {
            android.util.Log.e("FloatingPlayerService", "Error setting up player", e)
            e.printStackTrace()
        }
    }

    private fun setupControls() {
        val closeBtn = floatingView?.findViewById<ImageButton>(R.id.btn_close)
        val fullscreenBtn = floatingView?.findViewById<ImageButton>(R.id.btn_fullscreen)
        val lockBtn = playerView?.findViewById<ImageButton>(R.id.btn_lock)
        val pipBtn = playerView?.findViewById<ImageButton>(R.id.btn_pip)
        
        // Close button
        closeBtn?.setOnClickListener {
            // Save position before closing
            params?.let { p ->
                preferencesManager.setFloatingPlayerX(p.x)
                preferencesManager.setFloatingPlayerY(p.y)
            }
            stopSelf()
        }
        
        // Fullscreen button - launch FloatingPlayerActivity
        fullscreenBtn?.setOnClickListener {
            try {
                // Get current playback position
                val position = player?.currentPosition ?: 0L
                
                // Save window position
                params?.let { p ->
                    preferencesManager.setFloatingPlayerX(p.x)
                    preferencesManager.setFloatingPlayerY(p.y)
                    preferencesManager.setFloatingPlayerWidth(p.width)
                    preferencesManager.setFloatingPlayerHeight(p.height)
                }
                
                // Transfer player to PlayerHolder for FloatingPlayerActivity
                PlayerHolder.setPlayer(player)
                
                val intent = Intent(this, FloatingPlayerActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(FloatingPlayerActivity.EXTRA_CHANNEL, currentChannel)
                    putExtra(FloatingPlayerActivity.EXTRA_PLAYBACK_POSITION, position)
                    putExtra("from_floating_service", true)
                }
                
                startActivity(intent)
                
                // Stop the service (FloatingPlayerActivity will take over)
                stopSelf()
                
            } catch (e: Exception) {
                android.util.Log.e("FloatingPlayerService", "Error launching fullscreen", e)
                e.printStackTrace()
            }
        }
        
        // Lock button
        lockBtn?.setOnClickListener {
            toggleLock()
        }
        
        // PIP button - hidden in floating mode
        pipBtn?.visibility = View.GONE
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
                            showUnlockButton()
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
                                // Tap when locked - just show unlock button
                                showUnlockButton()
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
                                // This was a tap - toggle controls
                                if (playerView?.isControllerFullyVisible == true) {
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
            showUnlockButton()
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
