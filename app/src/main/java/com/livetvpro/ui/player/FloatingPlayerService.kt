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
import com.livetvpro.utils.FloatingPlayerManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@dagger.hilt.android.AndroidEntryPoint
class FloatingPlayerService : Service() {

    companion object {
        const val EXTRA_CHANNEL = "extra_channel"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PLAYBACK_POSITION = "extra_playback_position"
        const val EXTRA_LINK_INDEX = "extra_link_index"
        const val EXTRA_INSTANCE_ID = "extra_instance_id"
        const val ACTION_STOP = "action_stop"
        private const val NOTIFICATION_ID_BASE = 2000
        private const val CHANNEL_ID = "floating_player_channel"
        
        // Alias methods for compatibility with FloatingPlayerHelper
        fun startFloatingPlayer(
            context: Context,
            instanceId: String,
            channel: Channel?,
            event: com.livetvpro.data.models.LiveEvent?
        ): Boolean {
            return try {
                if (channel != null) {
                    start(context, channel) != null
                } else {
                    // Handle event case if needed
                    false
                }
            } catch (e: Exception) {
                false
            }
        }
        
        fun stopFloatingPlayer(context: Context, instanceId: String) {
            stop(context, instanceId)
        }
        
        fun start(context: Context, channel: Channel, linkIndex: Int = 0, playbackPosition: Long = 0L): String? {
            try {
                // Check if we can add more players
                if (!FloatingPlayerManager.canAddNewPlayer()) {
                    android.widget.Toast.makeText(
                        context,
                        "Maximum ${FloatingPlayerManager.getMaxPlayerCount()} floating players reached",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return null
                }
                
                // FIX: Use safe call operator for isEmpty() check
                if (channel.links == null || channel.links?.isEmpty() == true) {
                    android.widget.Toast.makeText(
                        context,
                        "No stream available",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return null
                }
                
                // FIX: Use non-null assertion after null check for indices access
                val selectedLink = if (channel.links != null && linkIndex in channel.links!!.indices) {
                    channel.links!![linkIndex]
                } else {
                    channel.links?.firstOrNull()
                }
                
                val streamUrl = selectedLink?.url ?: ""
                
                if (streamUrl.isEmpty()) {
                    android.widget.Toast.makeText(
                        context,
                        "Invalid stream URL",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return null
                }
                
                // Generate unique instance ID
                val instanceId = "floating_${System.currentTimeMillis()}_${channel.id}"
                
                val intent = Intent(context, FloatingPlayerService::class.java).apply {
                    putExtra(EXTRA_INSTANCE_ID, instanceId)
                    putExtra(EXTRA_CHANNEL, channel)
                    putExtra(EXTRA_STREAM_URL, streamUrl)
                    putExtra(EXTRA_TITLE, channel.name)
                    putExtra(EXTRA_PLAYBACK_POSITION, playbackPosition)
                    putExtra(EXTRA_LINK_INDEX, linkIndex)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                
                return instanceId
                
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    context,
                    "Failed to start floating player: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return null
            }
        }
        
        fun stop(context: Context, instanceId: String) {
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_INSTANCE_ID, instanceId)
            }
            context.startService(intent)
        }
    }
    
    // Multi-instance support - each instance has its own data
    data class FloatingPlayerInstance(
        val instanceId: String,
        var windowManager: WindowManager?,
        var floatingView: View?,
        var player: ExoPlayer?,
        var playerView: PlayerView?,
        var params: WindowManager.LayoutParams?,
        var lockOverlay: View?,
        var unlockButton: ImageButton?,
        var resizeHandle: ImageView?,
        var controlsLocked: Boolean = false,
        var isMuted: Boolean = false,
        var currentChannel: Channel? = null,
        var currentPlaybackPosition: Long = 0L,
        var initialX: Int = 0,
        var initialY: Int = 0,
        var initialTouchX: Float = 0f,
        var initialTouchY: Float = 0f,
        var initialWidth: Int = 0,
        var initialHeight: Int = 0
    )
    
    private val instances = mutableMapOf<String, FloatingPlayerInstance>()
    private val hideControlsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideUnlockButtonRunnables = mutableMapOf<String, Runnable>()
    
    @javax.inject.Inject
    lateinit var preferencesManager: com.livetvpro.data.local.PreferencesManager
    
    private fun getMinWidth() = dpToPx(240)
    private fun getMaxWidth() = dpToPx(400)
    private fun getMinHeight() = getMinWidth() * 9 / 16
    private fun getMaxHeight() = getMaxWidth() * 9 / 16

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
                if (instanceId != null) {
                    stopInstance(instanceId)
                }
            }
            else -> {
                // Start new instance
                val instanceId = intent?.getStringExtra(EXTRA_INSTANCE_ID) ?: return START_NOT_STICKY
                
                // Don't create duplicate
                if (instances.containsKey(instanceId)) {
                    return START_STICKY
                }
                
                val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_CHANNEL)
                }
                
                val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Live Stream"
                val playbackPosition = intent.getLongExtra(EXTRA_PLAYBACK_POSITION, 0L)
                
                if (streamUrl.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                
                createFloatingView(instanceId, streamUrl, title, playbackPosition, channel)
                
                // Start foreground for first instance
                if (instances.size == 1) {
                    startForeground(NOTIFICATION_ID_BASE, createNotification())
                } else {
                    updateForegroundNotification()
                }
            }
        }
        
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createFloatingView(
        instanceId: String,
        streamUrl: String, 
        title: String, 
        playbackPosition: Long,
        channel: Channel?
    ) {
        try {
            val themeContext = android.view.ContextThemeWrapper(this, R.style.Theme_LiveTVPro)
            val floatingView = LayoutInflater.from(themeContext).inflate(R.layout.floating_player_window, null)
            
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            val screenWidth: Int
            val screenHeight: Int
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = windowManager.currentWindowMetrics
                val bounds = windowMetrics.bounds
                val width = bounds.width()
                val height = bounds.height()
                
                screenWidth = if (width < height) width else height
                screenHeight = if (width < height) height else width
            } else {
                val display = windowManager.defaultDisplay
                val size = Point()
                display.getSize(size)
                
                screenWidth = if (size.x < size.y) size.x else size.y
                screenHeight = if (size.x < size.y) size.y else size.x
            }
            
            // Get saved dimensions or use defaults
            val savedWidth = preferencesManager.getFloatingPlayerWidth()
            val savedHeight = preferencesManager.getFloatingPlayerHeight()
            
            val defaultWidth = dpToPx(300)
            val defaultHeight = dpToPx(169) // 16:9 aspect ratio
            
            val width = if (savedWidth > 0) {
                savedWidth.coerceIn(getMinWidth(), getMaxWidth())
            } else {
                defaultWidth
            }
            
            val height = if (savedHeight > 0) {
                savedHeight.coerceIn(getMinHeight(), getMaxHeight())
            } else {
                defaultHeight
            }
            
            // Get saved position or center it
            val savedX = preferencesManager.getFloatingPlayerX()
            val savedY = preferencesManager.getFloatingPlayerY()
            
            val initialX: Int
            val initialY: Int
            
            if (savedX != 50 || savedY != 100) {
                // Use saved position
                initialX = savedX
                initialY = savedY
            } else {
                // Center the window on first launch
                initialX = (screenWidth - width) / 2
                initialY = (screenHeight - height) / 2
                
                // Save the centered position
                preferencesManager.setFloatingPlayerX(initialX)
                preferencesManager.setFloatingPlayerY(initialY)
            }
            
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            val params = WindowManager.LayoutParams(
                width,
                height,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initialX
                y = initialY
            }
            
            // Setup player
            val player = ExoPlayer.Builder(this).build()
            val playerView = floatingView.findViewById<PlayerView>(R.id.player_view)
            playerView.player = player
            playerView.useController = true
            playerView.controllerAutoShow = true
            playerView.controllerShowTimeoutMs = 3000
            
            // Set the title
            val titleText = playerView.findViewById<TextView>(R.id.tv_title)
            titleText?.text = title
            
            val mediaItem = MediaItem.fromUri(streamUrl)
            player.setMediaItem(mediaItem)
            player.prepare()
            
            if (playbackPosition > 0) {
                player.seekTo(playbackPosition)
            }
            
            player.playWhenReady = true
            
            // Setup lock overlay and unlock button - using correct ID from your layout
            val lockOverlay = floatingView.findViewById<View>(R.id.lock_overlay)
            val unlockButton = floatingView.findViewById<ImageButton>(R.id.unlock_button)
            
            // Create instance object
            val instance = FloatingPlayerInstance(
                instanceId = instanceId,
                windowManager = windowManager,
                floatingView = floatingView,
                player = player,
                playerView = playerView,
                params = params,
                lockOverlay = lockOverlay,
                unlockButton = unlockButton,
                resizeHandle = null, // Will be set in setupAllControls
                currentChannel = channel,
                currentPlaybackPosition = playbackPosition
            )
            
            // Create hide unlock button runnable for this instance
            hideUnlockButtonRunnables[instanceId] = Runnable {
                instance.unlockButton?.visibility = View.GONE
            }
            
            // Setup ALL controls and touch handlers
            setupAllControls(instance)
            setupTouchHandlers(instance)
            
            // Add to window manager
            windowManager.addView(floatingView, params)
            
            // Register with manager - use content name and type instead of player object
            FloatingPlayerManager.addPlayer(instanceId, title, "channel")
            
            // Store instance
            instances[instanceId] = instance
            
        } catch (e: Exception) {
            android.util.Log.e("FloatingPlayerService", "Error creating floating view: ${e.message}", e)
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private fun setupAllControls(instance: FloatingPlayerInstance) {
        val playerView = instance.playerView ?: return
        val floatingView = instance.floatingView ?: return
        val params = instance.params ?: return
        
        // Find all controls
        val closeBtn = playerView.findViewById<ImageButton>(R.id.btn_close)
        val fullscreenBtn = playerView.findViewById<ImageButton>(R.id.btn_fullscreen)
        val muteBtn = playerView.findViewById<ImageButton>(R.id.btn_mute)
        val lockBtn = playerView.findViewById<ImageButton>(R.id.btn_lock)
        val playPauseBtn = playerView.findViewById<ImageButton>(R.id.btn_play_pause)
        val seekBackBtn = playerView.findViewById<ImageButton>(R.id.btn_seek_back)
        val seekForwardBtn = playerView.findViewById<ImageButton>(R.id.btn_seek_forward)
        val resizeBtn = playerView.findViewById<ImageButton>(R.id.btn_resize)
        
        // Setup unlock button
        instance.unlockButton?.setOnClickListener {
            toggleLock(instance.instanceId)
        }
        
        // Setup lock overlay tap
        instance.lockOverlay?.setOnClickListener {
            if (instance.unlockButton?.visibility == View.VISIBLE) {
                hideUnlockButton(instance.instanceId)
            } else {
                showUnlockButton(instance.instanceId)
            }
        }
        
        // Close button
        closeBtn?.setOnClickListener {
            stopInstance(instance.instanceId)
        }
        
        // Fullscreen button - return to full player activity
        fullscreenBtn?.setOnClickListener {
            if (instance.currentChannel != null) {
                val currentPlayer = instance.player
                val channel = instance.currentChannel
                
                if (currentPlayer != null && channel != null) {
                    // Save position and size before transitioning
                    preferencesManager.setFloatingPlayerX(params.x)
                    preferencesManager.setFloatingPlayerY(params.y)
                    preferencesManager.setFloatingPlayerWidth(params.width)
                    preferencesManager.setFloatingPlayerHeight(params.height)
                    
                    val streamUrl = channel.links?.firstOrNull()?.url ?: ""
                    val currentPosition = currentPlayer.currentPosition
                    
                    // Create intent to open PlayerActivity
                    val intent = Intent(this, PlayerActivity::class.java).apply {
                        putExtra("EXTRA_CHANNEL", channel)
                        putExtra("EXTRA_PLAYBACK_POSITION", currentPosition)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    
                    // Stop this floating instance
                    stopInstance(instance.instanceId)
                }
            }
        }
        
        // Mute button
        muteBtn?.setOnClickListener {
            val currentPlayer = instance.player
            if (currentPlayer != null) {
                instance.isMuted = !instance.isMuted
                currentPlayer.volume = if (instance.isMuted) 0f else 1f
                muteBtn.setImageResource(
                    if (instance.isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
                )
            }
        }
        
        // Lock button
        lockBtn?.setOnClickListener {
            toggleLock(instance.instanceId)
        }
        
        // Play/Pause button
        playPauseBtn?.setOnClickListener {
            val currentPlayer = instance.player
            if (currentPlayer != null) {
                if (currentPlayer.isPlaying) {
                    currentPlayer.pause()
                } else {
                    currentPlayer.play()
                }
            }
        }
        
        // Seek back button
        seekBackBtn?.setOnClickListener {
            instance.player?.seekBack()
        }
        
        // Seek forward button
        seekForwardBtn?.setOnClickListener {
            instance.player?.seekForward()
        }
        
        // Resize button - touch and drag to resize
        var resizeInitialWidth = 0
        var resizeInitialHeight = 0
        var resizeInitialTouchX = 0f
        var resizeInitialTouchY = 0f
        
        resizeBtn?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resizeInitialWidth = params.width
                    resizeInitialHeight = params.height
                    resizeInitialTouchX = event.rawX
                    resizeInitialTouchY = event.rawY
                    true
                }
                
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - resizeInitialTouchX
                    val dy = event.rawY - resizeInitialTouchY
                    // Average both deltas for smooth diagonal resize
                    val delta = ((dx + dy) / 2).toInt()
                    var newWidth = resizeInitialWidth + delta
                    newWidth = newWidth.coerceIn(getMinWidth(), getMaxWidth())
                    val newHeight = newWidth * 9 / 16
                    
                    if (newWidth != params.width || newHeight != params.height) {
                        params.width = newWidth
                        params.height = newHeight
                        instance.windowManager?.updateViewLayout(floatingView, params)
                    }
                    true
                }
                
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Save final size
                    preferencesManager.setFloatingPlayerWidth(params.width)
                    preferencesManager.setFloatingPlayerHeight(params.height)
                    true
                }
                
                else -> false
            }
        }
        
        // Add player listener for play/pause button icon updates
        instance.player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playPauseBtn?.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        })
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchHandlers(instance: FloatingPlayerInstance) {
        val floatingView = instance.floatingView ?: return
        val params = instance.params ?: return
        
        var isDragging = false
        var hasMoved = false
        
        floatingView.setOnTouchListener { _, event ->
            if (instance.controlsLocked) {
                // Locked mode - only drag
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        instance.initialX = params.x
                        instance.initialY = params.y
                        instance.initialTouchX = event.rawX
                        instance.initialTouchY = event.rawY
                        isDragging = false
                        hasMoved = false
                        true
                    }
                    
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - instance.initialTouchX).toInt()
                        val dy = (event.rawY - instance.initialTouchY).toInt()
                        
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isDragging = true
                            hasMoved = true
                            
                            params.x = instance.initialX + dx
                            params.y = instance.initialY + dy
                            
                            // Save position during drag
                            preferencesManager.setFloatingPlayerX(params.x)
                            preferencesManager.setFloatingPlayerY(params.y)
                            
                            instance.windowManager?.updateViewLayout(floatingView, params)
                        }
                        true
                    }
                    
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!hasMoved) {
                            if (instance.unlockButton?.visibility == View.VISIBLE) {
                                hideUnlockButton(instance.instanceId)
                            } else {
                                showUnlockButton(instance.instanceId)
                            }
                        } else {
                            // Save final position
                            preferencesManager.setFloatingPlayerX(params.x)
                            preferencesManager.setFloatingPlayerY(params.y)
                        }
                        
                        isDragging = false
                        hasMoved = false
                        true
                    }
                    
                    else -> true
                }
            } else {
                // Unlocked mode - normal controls
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        instance.initialX = params.x
                        instance.initialY = params.y
                        instance.initialTouchX = event.rawX
                        instance.initialTouchY = event.rawY
                        isDragging = false
                        hasMoved = false
                        true
                    }
                    
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - instance.initialTouchX).toInt()
                        val dy = (event.rawY - instance.initialTouchY).toInt()
                        
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isDragging = true
                            hasMoved = true
                            
                            params.x = instance.initialX + dx
                            params.y = instance.initialY + dy
                            
                            // Save position during drag
                            preferencesManager.setFloatingPlayerX(params.x)
                            preferencesManager.setFloatingPlayerY(params.y)
                            
                            instance.windowManager?.updateViewLayout(floatingView, params)
                        }
                        
                        isDragging
                    }
                    
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!hasMoved) {
                            val currentlyVisible = instance.playerView?.isControllerFullyVisible == true
                            
                            if (currentlyVisible) {
                                instance.playerView?.hideController()
                            } else {
                                instance.playerView?.showController()
                            }
                        } else {
                            // Save final position
                            preferencesManager.setFloatingPlayerX(params.x)
                            preferencesManager.setFloatingPlayerY(params.y)
                        }
                        
                        val wasMoving = hasMoved
                        isDragging = false
                        hasMoved = false
                        
                        wasMoving
                    }
                    
                    else -> false
                }
            }
        }
        
        instance.lockOverlay?.apply {
            isClickable = false
            isFocusable = false
        }
    }


    
    private fun toggleLock(instanceId: String) {
        val instance = instances[instanceId] ?: return
        val lockBtn = instance.playerView?.findViewById<ImageButton>(R.id.btn_lock)
        
        if (instance.controlsLocked) {
            instance.controlsLocked = false
            instance.lockOverlay?.visibility = View.GONE
            hideUnlockButton(instanceId)
            lockBtn?.setImageResource(R.drawable.ic_lock_open)
            instance.playerView?.showController()
        } else {
            instance.controlsLocked = true
            lockBtn?.setImageResource(R.drawable.ic_lock_closed)
            
            instance.playerView?.hideController()
            
            instance.lockOverlay?.apply {
                visibility = View.VISIBLE
                isClickable = false
                isFocusable = false
            }
        }
    }
    
    private fun showUnlockButton(instanceId: String) {
        val instance = instances[instanceId] ?: return
        instance.unlockButton?.visibility = View.VISIBLE
        
        hideUnlockButtonRunnables[instanceId]?.let { runnable ->
            hideControlsHandler.removeCallbacks(runnable)
            hideControlsHandler.postDelayed(runnable, 3000)
        }
    }
    
    private fun hideUnlockButton(instanceId: String) {
        val instance = instances[instanceId] ?: return
        hideUnlockButtonRunnables[instanceId]?.let { runnable ->
            hideControlsHandler.removeCallbacks(runnable)
        }
        instance.unlockButton?.visibility = View.GONE
    }
    
    private fun stopInstance(instanceId: String) {
        val instance = instances[instanceId] ?: return
        
        // Save playback position
        instance.player?.let { player ->
            instance.currentPlaybackPosition = player.currentPosition
        }
        
        // Save final position and size
        instance.params?.let { params ->
            preferencesManager.setFloatingPlayerX(params.x)
            preferencesManager.setFloatingPlayerY(params.y)
            preferencesManager.setFloatingPlayerWidth(params.width)
            preferencesManager.setFloatingPlayerHeight(params.height)
        }
        
        // Cleanup
        instance.player?.release()
        
        try {
            instance.floatingView?.let { 
                instance.windowManager?.removeView(it) 
            }
        } catch (e: Exception) {
            android.util.Log.e("FloatingPlayerService", "Error removing view: ${e.message}")
        }
        
        // Remove from manager
        FloatingPlayerManager.removePlayer(instanceId)
        
        // Remove runnable
        hideUnlockButtonRunnables[instanceId]?.let { runnable ->
            hideControlsHandler.removeCallbacks(runnable)
        }
        hideUnlockButtonRunnables.remove(instanceId)
        
        instances.remove(instanceId)
        
        // Update notification or stop service
        if (instances.isEmpty()) {
            stopForeground(true)
            stopSelf()
        } else {
            updateForegroundNotification()
        }
    }
    
    private fun stopAllInstances() {
        val instanceIds = instances.keys.toList()
        instanceIds.forEach { stopInstance(it) }
    }
    
    private fun updateForegroundNotification() {
        if (instances.isEmpty()) return
        
        val notification = createNotification()
        startForeground(NOTIFICATION_ID_BASE, notification)
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

    private fun createNotification(): Notification {
        val count = instances.size
        val title = if (count == 1) {
            "1 Floating Player Active"
        } else {
            "$count Floating Players Active"
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Playing in floating window")
            .setSmallIcon(R.drawable.ic_play)
            .setOngoing(true)
            .build()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllInstances()
        hideControlsHandler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
