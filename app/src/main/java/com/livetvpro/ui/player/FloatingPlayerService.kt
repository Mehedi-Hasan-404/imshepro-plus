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
    
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    
    private var controlsLocked = false
    private var isMuted = false
    
    private val hideControlsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    private val hideUnlockButtonRunnable = Runnable {
        unlockButton?.visibility = View.GONE
    }
    
    private var lockOverlay: View? = null
    private var unlockButton: ImageButton? = null
    private var params: WindowManager.LayoutParams? = null
    
    private var currentChannel: Channel? = null
    private var currentPlaybackPosition: Long = 0L
    
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
        const val ACTION_STOP = "action_stop"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_player_channel"
        
        fun start(context: Context, channel: Channel, linkIndex: Int = 0, playbackPosition: Long = 0L) {
            try {
                // FIX: Use safe call operator for isEmpty() check
                if (channel.links == null || channel.links?.isEmpty() == true) {
                    android.widget.Toast.makeText(
                        context,
                        "No stream available",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return
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
        
        val linkIndex = intent?.getIntExtra(EXTRA_LINK_INDEX, 0) ?: 0
        
        val selectedLink = if (currentChannel != null && 
                              currentChannel!!.links != null && 
                              linkIndex in currentChannel!!.links!!.indices) {
            currentChannel!!.links!![linkIndex]
        } else {
            currentChannel?.links?.firstOrNull()
        }
        
        val streamUrl = intent?.getStringExtra(EXTRA_STREAM_URL) ?: selectedLink?.url ?: ""
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: currentChannel?.name ?: "Live Stream"
        currentPlaybackPosition = intent?.getLongExtra(EXTRA_PLAYBACK_POSITION, 0L) ?: 0L
        
        val useTransferredPlayer = intent?.getBooleanExtra("use_transferred_player", false) ?: false
        
        if (streamUrl.isEmpty() && !useTransferredPlayer) {
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
            
            val screenWidth: Int
            val screenHeight: Int
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = windowManager?.currentWindowMetrics
                val bounds = windowMetrics?.bounds
                val width = bounds?.width() ?: 1080
                val height = bounds?.height() ?: 1920
                
                screenWidth = if (width < height) width else height
                screenHeight = if (width < height) height else width
            } else {
                val display = windowManager?.defaultDisplay
                val size = Point()
                display?.getSize(size)
                
                screenWidth = if (size.x < size.y) size.x else size.y
                screenHeight = if (size.x < size.y) size.y else size.x
            }
            
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
            
            val savedX = preferencesManager.getFloatingPlayerX()
            val savedY = preferencesManager.getFloatingPlayerY()
            
            val initialX: Int
            val initialY: Int
            
            if (savedX != 50 && savedY != 100) {
                initialX = savedX
                initialY = savedY
            } else {
                initialX = (screenWidth - initialWidth) / 2
                initialY = (screenHeight - initialHeight) / 2
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
            stopSelf()
        }
    }

    private fun setupPlayer(streamUrl: String, title: String) {
        try {
            player = ExoPlayer.Builder(this).build()
            
            playerView = floatingView?.findViewById(R.id.player_view)
            playerView?.apply {
                player = this@FloatingPlayerService.player
                useController = true
                controllerAutoShow = true
                controllerShowTimeoutMs = 3000
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
            val (transferredPlayer, transferredUrl, transferredName) = PlayerHolder.retrievePlayer()
            
            if (transferredPlayer != null) {
                player = transferredPlayer
                
                playerView = floatingView?.findViewById(R.id.player_view)
                playerView?.apply {
                    player = this@FloatingPlayerService.player
                    useController = true
                    controllerAutoShow = true
                    controllerShowTimeoutMs = 3000
                }
                
                val titleText = playerView?.findViewById<TextView>(R.id.tv_title)
                titleText?.text = transferredName ?: title
                
                PlayerHolder.clearReferences()
                
            } else {
                val url = currentChannel?.links?.firstOrNull()?.url ?: ""
                setupPlayer(url, title)
            }
            
        } catch (e: Exception) {
            val url = currentChannel?.links?.firstOrNull()?.url ?: ""
            setupPlayer(url, title)
        }
    }

    private fun setupControls() {
        lockOverlay = floatingView?.findViewById(R.id.lock_overlay)
        unlockButton = floatingView?.findViewById(R.id.unlock_button)
        
        val closeBtn = playerView?.findViewById<ImageButton>(R.id.btn_close)
        val fullscreenBtn = playerView?.findViewById<ImageButton>(R.id.btn_fullscreen)
        val muteBtn = playerView?.findViewById<ImageButton>(R.id.btn_mute)
        val lockBtn = playerView?.findViewById<ImageButton>(R.id.btn_lock)
        val playPauseBtn = playerView?.findViewById<ImageButton>(R.id.btn_play_pause)
        val seekBackBtn = playerView?.findViewById<ImageButton>(R.id.btn_seek_back)
        val seekForwardBtn = playerView?.findViewById<ImageButton>(R.id.btn_seek_forward)
        
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
            preferencesManager.setFloatingPlayerX(50)
            preferencesManager.setFloatingPlayerY(100)
            preferencesManager.setFloatingPlayerWidth(0)
            preferencesManager.setFloatingPlayerHeight(0)
            stopSelf()
        }
        
        fullscreenBtn?.setOnClickListener {
            if (currentChannel != null) {
                val currentPlayer = player
                val channel = currentChannel
                
                if (currentPlayer != null && channel != null) {
                    params?.let { p ->
                        preferencesManager.setFloatingPlayerWidth(p.width)
                        preferencesManager.setFloatingPlayerHeight(p.height)
                    }
                    
                    val streamUrl = channel.links?.firstOrNull()?.url ?: ""
                    PlayerHolder.transferPlayer(currentPlayer, streamUrl, channel.name)
                    
                    val intent = Intent(this, FloatingPlayerActivity::class.java).apply {
                        putExtra("extra_channel", channel)
                        putExtra("use_transferred_player", true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    
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
                        preferencesManager.setFloatingPlayerWidth(p.width)
                        preferencesManager.setFloatingPlayerHeight(p.height)
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
                                
                                preferencesManager.setFloatingPlayerX(p.x)
                                preferencesManager.setFloatingPlayerY(p.y)
                                
                                windowManager?.updateViewLayout(floatingView, p)
                            }
                            true
                        }
                        
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (!hasMoved) {
                                if (unlockButton?.visibility == View.VISIBLE) {
                                    hideUnlockButton()
                                } else {
                                    showUnlockButton()
                                }
                            } else {
                                preferencesManager.setFloatingPlayerX(p.x)
                                preferencesManager.setFloatingPlayerY(p.y)
                            }
                            
                            isDragging = false
                            hasMoved = false
                            true
                        }
                        
                        else -> true
                    }
                } ?: true
            } else {
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
                                
                                preferencesManager.setFloatingPlayerX(p.x)
                                preferencesManager.setFloatingPlayerY(p.y)
                                
                                windowManager?.updateViewLayout(floatingView, p)
                            }
                            
                            isDragging
                        }
                        
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (!hasMoved) {
                                val currentlyVisible = playerView?.isControllerFullyVisible == true
                                
                                if (currentlyVisible) {
                                    playerView?.hideController()
                                } else {
                                    playerView?.showController()
                                }
                            } else {
                                preferencesManager.setFloatingPlayerX(p.x)
                                preferencesManager.setFloatingPlayerY(p.y)
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
        }
        
        lockOverlay?.apply {
            isClickable = false
            isFocusable = false
        }
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
        } else {
            controlsLocked = true
            lockBtn?.setImageResource(R.drawable.ic_lock_closed)
            
            playerView?.hideController()
            
            lockOverlay?.apply {
                visibility = View.VISIBLE
                isClickable = false
                isFocusable = false
            }
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
