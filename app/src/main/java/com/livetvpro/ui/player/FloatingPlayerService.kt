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
        var controlsLocked: Boolean = false,
        var isMuted: Boolean = false,
        var currentChannel: Channel? = null,
        var currentPlaybackPosition: Long = 0L,
        var initialX: Int = 0,
        var initialY: Int = 0,
        var initialTouchX: Float = 0f,
        var initialTouchY: Float = 0f
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
                val useTransferredPlayer = intent.getBooleanExtra("use_transferred_player", false)
                
                if (streamUrl.isEmpty() && !useTransferredPlayer) {
                    return START_NOT_STICKY
                }
                
                createFloatingInstance(instanceId, channel, streamUrl, title, playbackPosition, useTransferredPlayer)
                
                // Start foreground with first instance or update notification
                updateForegroundNotification()
            }
        }
        
        return START_STICKY
    }
    
    private fun createFloatingInstance(
        instanceId: String,
        channel: Channel?,
        streamUrl: String,
        title: String,
        playbackPosition: Long,
        useTransferredPlayer: Boolean
    ) {
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val themeContext = android.view.ContextThemeWrapper(this, R.style.Theme_LiveTVPro)
            val floatingView = LayoutInflater.from(themeContext).inflate(R.layout.floating_player_window, null)
            
            // Calculate window size
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
            
            val windowWidth = (screenWidth * 0.8).toInt().coerceIn(getMinWidth(), getMaxWidth())
            val windowHeight = (windowWidth * 9) / 16
            
            // Create window params
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            val params = WindowManager.LayoutParams(
                windowWidth,
                windowHeight,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            
            // Calculate initial position (stagger multiple windows)
            val instanceCount = instances.size
            val baseX = preferencesManager.getFloatingPlayerX()
            val baseY = preferencesManager.getFloatingPlayerY()
            
            params.x = baseX + (instanceCount * 50)  // Offset each window
            params.y = baseY + (instanceCount * 50)
            
            // Setup player
            val playerView = floatingView.findViewById<PlayerView>(R.id.player_view)
            val lockOverlay = floatingView.findViewById<View>(R.id.lock_overlay)
            val unlockButton = floatingView.findViewById<ImageButton>(R.id.unlock_button)
            
            val player: ExoPlayer? = if (useTransferredPlayer) {
                com.livetvpro.utils.PlayerHolder.getTransferredPlayer()?.also {
                    com.livetvpro.utils.PlayerHolder.clearTransferredPlayer()
                }
            } else {
                ExoPlayer.Builder(this).build().apply {
                    val mediaItem = MediaItem.fromUri(streamUrl)
                    setMediaItem(mediaItem)
                    prepare()
                    seekTo(playbackPosition)
                    playWhenReady = true
                }
            }
            
            playerView?.player = player
            
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
                currentChannel = channel,
                currentPlaybackPosition = playbackPosition
            )
            
            instances[instanceId] = instance
            
            // Add to FloatingPlayerManager
            FloatingPlayerManager.addPlayer(instanceId, title, "channel")
            
            // Add view to window manager
            windowManager.addView(floatingView, params)
            
            // Setup touch listeners
            setupTouchListeners(instance)
            
            // Setup control buttons
            setupControlButtons(instance)
            
        } catch (e: Exception) {
            android.util.Log.e("FloatingPlayerService", "Error creating instance: ${e.message}", e)
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListeners(instance: FloatingPlayerInstance) {
        val floatingView = instance.floatingView ?: return
        val params = instance.params ?: return
        
        var isDragging = false
        var hasMoved = false
        
        // Create hide unlock button runnable for this instance
        val hideRunnable = Runnable {
            instance.unlockButton?.visibility = View.GONE
        }
        hideUnlockButtonRunnables[instance.instanceId] = hideRunnable
        
        floatingView.setOnTouchListener { _, event ->
            if (instance.controlsLocked) {
                // Locked mode - only unlock button and drag
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
    
    private fun setupControlButtons(instance: FloatingPlayerInstance) {
        val playerView = instance.playerView ?: return
        
        // Setup lock button
        val lockBtn = playerView.findViewById<ImageButton>(R.id.btn_lock)
        lockBtn?.setOnClickListener {
            toggleLock(instance.instanceId)
        }
        
        // Setup unlock button
        instance.unlockButton?.setOnClickListener {
            toggleLock(instance.instanceId)
        }
        
        // Setup close button  
        val closeBtn = playerView.findViewById<ImageButton>(R.id.btn_close)
        closeBtn?.setOnClickListener {
            stopInstance(instance.instanceId)
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
