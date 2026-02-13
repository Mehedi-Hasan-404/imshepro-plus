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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    data class FloatingPlayerInstance(
        val instanceId: String,
        val floatingView: View,
        val player: ExoPlayer,
        val playerView: PlayerView,
        val params: WindowManager.LayoutParams,
        var currentChannel: Channel,
        var controlsLocked: Boolean = false,
        var isMuted: Boolean = false,
        val lockOverlay: View?,
        val unlockButton: ImageButton?
    )
    
    private var windowManager: WindowManager? = null
    
    private val activeInstances = mutableMapOf<String, FloatingPlayerInstance>()
    private val hideControlsHandlers = mutableMapOf<String, android.os.Handler>()
    
    @javax.inject.Inject
    lateinit var preferencesManager: com.livetvpro.data.local.PreferencesManager
    
    private fun getMinWidth() = dpToPx(240)
    private fun getMaxWidth() = dpToPx(400)
    private fun getMinHeight() = getMinWidth() * 9 / 16
    private fun getMaxHeight() = getMaxWidth() * 9 / 16
    
    companion object {
        const val EXTRA_CHANNEL = "extra_channel"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PLAYBACK_POSITION = "extra_playback_position"
        const val EXTRA_LINK_INDEX = "extra_link_index"
        const val EXTRA_INSTANCE_ID = "extra_instance_id"
        const val EXTRA_RESTORE_POSITION = "extra_restore_position"
        const val ACTION_STOP = "action_stop"
        const val ACTION_STOP_INSTANCE = "action_stop_instance"
        const val ACTION_UPDATE_STREAM = "action_update_stream"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_player_channel"
        
        fun start(context: Context, channel: Channel, linkIndex: Int = 0, playbackPosition: Long = 0L) {
            try {
                if (channel.links == null || channel.links?.isEmpty() == true) {
                    android.widget.Toast.makeText(
                        context,
                        "No stream available",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                
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
                    return
                }
                
                val intent = Intent(context, FloatingPlayerService::class.java).apply {
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
                
            } catch (e: Exception) {
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
        
        fun startFloatingPlayer(
            context: Context,
            instanceId: String,
            channel: Channel? = null,
            event: com.livetvpro.data.models.LiveEvent? = null,
            linkIndex: Int = 0
        ): Boolean {
            try {
                val actualChannel = channel ?: return false
                
                if (actualChannel.links == null || actualChannel.links?.isEmpty() == true) {
                    android.widget.Toast.makeText(
                        context,
                        "No stream available",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return false
                }
                
                val selectedLink = if (linkIndex in actualChannel.links!!.indices) {
                    actualChannel.links!![linkIndex]
                } else {
                    actualChannel.links?.firstOrNull()
                }
                val streamUrl = selectedLink?.url ?: ""
                
                if (streamUrl.isEmpty()) {
                    android.widget.Toast.makeText(
                        context,
                        "Invalid stream URL",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return false
                }
                
                val intent = Intent(context, FloatingPlayerService::class.java).apply {
                    putExtra(EXTRA_INSTANCE_ID, instanceId)
                    putExtra(EXTRA_CHANNEL, actualChannel)
                    putExtra(EXTRA_STREAM_URL, streamUrl)
                    putExtra(EXTRA_TITLE, actualChannel.name)
                    putExtra(EXTRA_PLAYBACK_POSITION, 0L)
                    putExtra(EXTRA_LINK_INDEX, linkIndex)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                
                return true
                
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    context,
                    "Failed to start floating player: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return false
            }
        }
        
        fun updateFloatingPlayer(
            context: Context,
            instanceId: String,
            channel: Channel,
            linkIndex: Int = 0
        ): Boolean {
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                action = ACTION_UPDATE_STREAM
                putExtra(EXTRA_INSTANCE_ID, instanceId)
                putExtra(EXTRA_CHANNEL, channel)
                putExtra(EXTRA_LINK_INDEX, linkIndex)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            
            return true
        }
        
        fun stopFloatingPlayer(context: Context, instanceId: String) {
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                action = ACTION_STOP_INSTANCE
                putExtra(EXTRA_INSTANCE_ID, instanceId)
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAllInstances()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_INSTANCE -> {
                val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
                if (instanceId != null) {
                    stopInstance(instanceId)
                }
                if (activeInstances.isEmpty()) {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_UPDATE_STREAM -> {
                val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
                val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_CHANNEL)
                }
                val linkIndex = intent.getIntExtra(EXTRA_LINK_INDEX, 0)
                
                if (instanceId != null && channel != null) {
                    updateInstanceStream(instanceId, channel, linkIndex)
                }
                return START_STICKY
            }
        }
        
        val instanceId = intent?.getStringExtra(EXTRA_INSTANCE_ID) ?: java.util.UUID.randomUUID().toString()
        
        if (activeInstances.containsKey(instanceId)) {
            return START_STICKY
        }
        
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_CHANNEL)
        }
        
        val linkIndex = intent?.getIntExtra(EXTRA_LINK_INDEX, 0) ?: 0
        val playbackPosition = intent?.getLongExtra(EXTRA_PLAYBACK_POSITION, 0L) ?: 0L
        val restorePosition = intent?.getBooleanExtra(EXTRA_RESTORE_POSITION, false) ?: false
        
        if (channel != null) {
            createFloatingPlayerInstance(instanceId, channel, linkIndex, playbackPosition, restorePosition)
            updateNotification()
        }
        
        return START_STICKY
    }

    private fun createFloatingPlayerInstance(
        instanceId: String,
        channel: Channel,
        linkIndex: Int,
        playbackPosition: Long,
        restorePosition: Boolean = false
    ) {
        try {
            val selectedLink = if (channel.links != null && linkIndex in channel.links!!.indices) {
                channel.links!![linkIndex]
            } else {
                channel.links?.firstOrNull()
            }
            val streamUrl = selectedLink?.url ?: return
            
            val floatingView = LayoutInflater.from(this).inflate(R.layout.floating_player_window, null)
            
            val screenWidth = getScreenWidth()
            val screenHeight = getScreenHeight()
            
            val savedWidth = preferencesManager.getFloatingPlayerWidth()
            val savedHeight = preferencesManager.getFloatingPlayerHeight()
            
            val initialWidth: Int
            val initialHeight: Int
            
            if (savedWidth > 0 && savedHeight > 0) {
                initialWidth = savedWidth.coerceIn(getMinWidth(), getMaxWidth())
                initialHeight = savedHeight.coerceIn(getMinHeight(), getMaxHeight())
            } else {
                initialWidth = (screenWidth * 0.6f).toInt().coerceIn(getMinWidth(), getMaxWidth())
                initialHeight = initialWidth * 9 / 16
            }
            
            val initialX: Int
            val initialY: Int
            
            initialX = (screenWidth - initialWidth) / 2
            initialY = (screenHeight - initialHeight) / 2
            
            val params = WindowManager.LayoutParams(
                initialWidth,
                initialHeight,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initialX
                y = initialY
            }
            
            val player = ExoPlayer.Builder(this).build()
            val playerView = floatingView.findViewById<PlayerView>(R.id.player_view)
            playerView.player = player
            
            val mediaItem = MediaItem.Builder()
                .setUri(streamUrl)
                .build()
            
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
            
            if (playbackPosition > 0) {
                player.seekTo(playbackPosition)
            }
            
            val titleText = floatingView.findViewById<TextView>(R.id.tv_title)
            titleText.text = channel.name
            
            val lockOverlay = floatingView.findViewById<View>(R.id.lock_overlay)
            val unlockButton = floatingView.findViewById<ImageButton>(R.id.unlock_button)
            
            val instance = FloatingPlayerInstance(
                instanceId = instanceId,
                floatingView = floatingView,
                player = player,
                playerView = playerView,
                params = params,
                currentChannel = channel,
                lockOverlay = lockOverlay,
                unlockButton = unlockButton
            )
            
            activeInstances[instanceId] = instance
            hideControlsHandlers[instanceId] = Handler(Looper.getMainLooper())
            
            windowManager?.addView(floatingView, params)
            
            setupFloatingControls(floatingView, playerView, params, instanceId, player, lockOverlay, unlockButton, channel)
            
        } catch (e: Exception) {
            android.util.Log.e("FloatingPlayerService", "Error creating instance", e)
            android.widget.Toast.makeText(
                this,
                "Failed to create floating player: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateInstanceStream(instanceId: String, channel: Channel, linkIndex: Int) {
        val instance = activeInstances[instanceId] ?: return
        
        val selectedLink = if (channel.links != null && linkIndex in channel.links!!.indices) {
            channel.links!![linkIndex]
        } else {
            channel.links?.firstOrNull()
        }
        val streamUrl = selectedLink?.url ?: return
        
        instance.currentChannel = channel
        
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .build()
        
        instance.player.setMediaItem(mediaItem)
        instance.player.prepare()
        instance.player.playWhenReady = true
        
        val titleText = instance.floatingView.findViewById<TextView>(R.id.tv_title)
        titleText.text = channel.name
        
        android.widget.Toast.makeText(
            this,
            "Updated to ${channel.name}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun setupFloatingControls(
        floatingView: View,
        playerView: PlayerView,
        params: WindowManager.LayoutParams,
        instanceId: String,
        player: ExoPlayer,
        lockOverlay: View?,
        unlockButton: ImageButton?,
        channel: Channel
    ) {
        val btnClose = playerView.findViewById<ImageButton>(R.id.btn_close)
        val btnFullscreen = playerView.findViewById<ImageButton>(R.id.btn_fullscreen)
        val btnMute = playerView.findViewById<ImageButton>(R.id.btn_mute)
        val btnLock = playerView.findViewById<ImageButton>(R.id.btn_lock)
        val btnPlayPause = playerView.findViewById<ImageButton>(R.id.btn_play_pause)
        val btnSeekBack = playerView.findViewById<ImageButton>(R.id.btn_seek_back)
        val btnSeekForward = playerView.findViewById<ImageButton>(R.id.btn_seek_forward)
        val btnResize = floatingView.findViewById<ImageButton>(R.id.btn_resize)
        
        btnClose?.setOnClickListener {
            stopInstance(instanceId)
        }
        
        btnFullscreen?.setOnClickListener {
            try {
                android.util.Log.d("FloatingPlayerService", "Fullscreen clicked")
                
                preferencesManager.setFloatingPlayerWidth(params.width)
                preferencesManager.setFloatingPlayerHeight(params.height)
                
                val streamUrl = channel.links?.firstOrNull()?.url ?: ""
                PlayerHolder.transferPlayer(player, streamUrl, channel.name)
                
                val intent = Intent(this@FloatingPlayerService, FloatingPlayerActivity::class.java).apply {
                    putExtra("channel", channel)
                    putExtra("use_transferred_player", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                
                startActivity(intent)
                
                Handler(Looper.getMainLooper()).postDelayed({
                    hideControlsHandlers[instanceId]?.removeCallbacksAndMessages(null)
                    hideControlsHandlers.remove(instanceId)
                    
                    try {
                        windowManager?.removeView(floatingView)
                    } catch (e: Exception) {
                        android.util.Log.e("FloatingPlayerService", "Error removing view", e)
                    }
                    
                    activeInstances.remove(instanceId)
                    com.livetvpro.utils.FloatingPlayerManager.removePlayer(instanceId)
                    
                    if (activeInstances.isEmpty()) {
                        stopSelf()
                    }
                }, 300)
                
            } catch (e: Exception) {
                android.util.Log.e("FloatingPlayerService", "Error in fullscreen", e)
                android.widget.Toast.makeText(
                    this@FloatingPlayerService,
                    "Failed to open fullscreen: ${e.message}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        
        btnMute?.setOnClickListener {
            val instance = activeInstances[instanceId] ?: return@setOnClickListener
            instance.isMuted = !instance.isMuted
            player.volume = if (instance.isMuted) 0f else 1f
            btnMute.setImageResource(
                if (instance.isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
            )
        }
        
        btnLock?.setOnClickListener {
            toggleLock(instanceId)
        }
        
        unlockButton?.setOnClickListener {
            toggleLock(instanceId)
        }
        
        btnPlayPause?.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
                btnPlayPause.setImageResource(R.drawable.ic_play)
            } else {
                player.play()
                btnPlayPause.setImageResource(R.drawable.ic_pause)
            }
        }
        
        btnSeekBack?.setOnClickListener {
            player.seekBack()
        }
        
        btnSeekForward?.setOnClickListener {
            player.seekForward()
        }
        
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause?.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        })
        
        setupResizeFunctionality(floatingView, btnResize, params, instanceId)
        setupDragFunctionality(floatingView, params, playerView, lockOverlay, unlockButton, instanceId)
    }

    private fun setupResizeFunctionality(
        floatingView: View,
        btnResize: ImageButton?,
        params: WindowManager.LayoutParams,
        instanceId: String
    ) {
        var resizeInitialWidth = 0
        var resizeInitialHeight = 0
        var resizeInitialTouchX = 0f
        var resizeInitialTouchY = 0f
        
        btnResize?.setOnTouchListener { v, event ->
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
                    val delta = ((dx + dy) / 2).toInt()
                    var newWidth = resizeInitialWidth + delta
                    newWidth = newWidth.coerceIn(getMinWidth(), getMaxWidth())
                    val newHeight = newWidth * 9 / 16
                    
                    if (newWidth != params.width || newHeight != params.height) {
                        params.width = newWidth
                        params.height = newHeight
                        windowManager?.updateViewLayout(floatingView, params)
                    }
                    true
                }
                
                MotionEvent.ACTION_UP -> {
                    preferencesManager.setFloatingPlayerWidth(params.width)
                    preferencesManager.setFloatingPlayerHeight(params.height)
                    true
                }
                
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragFunctionality(
        floatingView: View,
        params: WindowManager.LayoutParams,
        playerView: PlayerView,
        lockOverlay: View?,
        unlockButton: ImageButton?,
        instanceId: String
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var hasMoved = false
        
        playerView.setOnTouchListener { _, event ->
            val instance = activeInstances[instanceId]
            
            if (instance?.controlsLocked == true) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    showUnlockButton(instanceId)
                }
                return@setOnTouchListener true
            }
            
            instance?.let { p ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        hasMoved = false
                        false
                    }
                    
                    MotionEvent.ACTION_MOVE -> {
                        if (isDragging || hasMoved) {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()
                            
                            if (abs(dx) > 10 || abs(dy) > 10) {
                                isDragging = true
                                hasMoved = true
                                
                                params.x = initialX + dx
                                params.y = initialY + dy
                                
                                preferencesManager.setFloatingPlayerX(params.x)
                                preferencesManager.setFloatingPlayerY(params.y)
                                
                                windowManager?.updateViewLayout(floatingView, params)
                            }
                            
                            isDragging
                        } else {
                            false
                        }
                    }
                    
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!hasMoved) {
                            val currentlyVisible = playerView.isControllerFullyVisible == true
                            
                            if (currentlyVisible) {
                                playerView.hideController()
                            } else {
                                playerView.showController()
                            }
                        } else {
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
            } ?: false
        }
        
        lockOverlay?.apply {
            isClickable = false
            isFocusable = false
        }
    }

    private fun toggleLock(instanceId: String) {
        val instance = activeInstances[instanceId] ?: return
        val lockBtn = instance.playerView.findViewById<ImageButton>(R.id.btn_lock)
        
        if (instance.controlsLocked) {
            instance.controlsLocked = false
            instance.lockOverlay?.visibility = View.GONE
            hideUnlockButton(instanceId)
            lockBtn?.setImageResource(R.drawable.ic_lock_open)
            instance.playerView.showController()
        } else {
            instance.controlsLocked = true
            lockBtn?.setImageResource(R.drawable.ic_lock_closed)
            instance.playerView.hideController()
            instance.lockOverlay?.apply {
                visibility = View.VISIBLE
                isClickable = false
                isFocusable = false
            }
        }
    }

    private fun showUnlockButton(instanceId: String) {
        val instance = activeInstances[instanceId] ?: return
        val handler = hideControlsHandlers[instanceId] ?: return
        
        instance.unlockButton?.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            instance.unlockButton?.visibility = View.GONE
        }, 3000)
    }
    
    private fun hideUnlockButton(instanceId: String) {
        val instance = activeInstances[instanceId] ?: return
        val handler = hideControlsHandlers[instanceId] ?: return
        
        handler.removeCallbacksAndMessages(null)
        instance.unlockButton?.visibility = View.GONE
    }

    private fun stopInstance(instanceId: String) {
        val instance = activeInstances[instanceId] ?: return
        
        hideControlsHandlers[instanceId]?.removeCallbacksAndMessages(null)
        hideControlsHandlers.remove(instanceId)
        
        instance.player.release()
        
        try {
            windowManager?.removeView(instance.floatingView)
        } catch (e: Exception) {
        }
        
        activeInstances.remove(instanceId)
        com.livetvpro.utils.FloatingPlayerManager.removePlayer(instanceId)
        
        preferencesManager.setFloatingPlayerX(Int.MIN_VALUE)
        preferencesManager.setFloatingPlayerY(Int.MIN_VALUE)
        preferencesManager.setFloatingPlayerWidth(0)
        preferencesManager.setFloatingPlayerHeight(0)
        
        updateNotification()
        
        if (activeInstances.isEmpty()) {
            stopSelf()
        }
    }

    private fun stopAllInstances() {
        val instanceIds = activeInstances.keys.toList()
        instanceIds.forEach { stopInstance(it) }
    }

    private fun updateNotification() {
        if (activeInstances.isEmpty()) return
        
        val count = activeInstances.size
        val title = if (count == 1) {
            activeInstances.values.first().currentChannel.name
        } else {
            "$count Floating Players Active"
        }
        
        val notification = createNotification(title)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
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
            .addAction(R.drawable.ic_close, "Stop All", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun getScreenWidth(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.currentWindowMetrics?.bounds?.width() ?: 1080
        } else {
            val display = windowManager?.defaultDisplay
            val size = Point()
            display?.getSize(size)
            size.x
        }
    }
    
    private fun getScreenHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.currentWindowMetrics?.bounds?.height() ?: 1920
        } else {
            val display = windowManager?.defaultDisplay
            val size = Point()
            display?.getSize(size)
            size.y
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllInstances()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
