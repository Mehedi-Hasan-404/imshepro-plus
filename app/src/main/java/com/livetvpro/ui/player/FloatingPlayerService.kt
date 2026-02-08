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
import android.view.ScaleGestureDetector
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
import kotlin.math.max
import kotlin.math.min

class FloatingPlayerService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    
    // Position and touch tracking
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var startClickTime: Long = 0
    
    // State
    private var controlsLocked = false
    private var isMuted = false
    private var isDragging = false
    
    // UI components
    private var controlsContainer: View? = null
    private var bottomControlsContainer: View? = null
    private var params: WindowManager.LayoutParams? = null
    
    // Size limits (in pixels)
    private val MIN_WIDTH = 240
    private val MAX_WIDTH = 1200
    private val MIN_HEIGHT = 180
    private val MAX_HEIGHT = 900
    
    companion object {
        const val EXTRA_CHANNEL = "extra_channel"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val ACTION_STOP = "action_stop"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_player_channel"
        
        fun start(context: Context, channel: Channel) {
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
                    putExtra(EXTRA_STREAM_URL, streamUrl)
                    putExtra(EXTRA_TITLE, channel.name)
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
        
        val streamUrl = intent?.getStringExtra(EXTRA_STREAM_URL) ?: ""
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Live Stream"
        
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
            val initialWidth = dpToPx(320)  // Start with a reasonable size
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
            
            // Position at top-right corner
            params?.apply {
                gravity = Gravity.TOP or Gravity.START
                x = (metrics.widthPixels - initialWidth) / 2  // Center horizontally
                y = dpToPx(100)  // Start 100dp from top
            }
            
            windowManager?.addView(floatingView, params)
            
            setupControls()
            setupGestures()
            setupPlayer(streamUrl, title)
            
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
                useController = false  // We're using custom controls
            }
            
            val titleText = floatingView?.findViewById<TextView>(R.id.tv_title)
            titleText?.text = title
            
            val mediaItem = MediaItem.fromUri(streamUrl)
            player?.apply {
                setMediaItem(mediaItem)
                prepare()
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
        val resizeBtn = floatingView?.findViewById<ImageButton>(R.id.btn_resize)
        
        closeBtn?.setOnClickListener {
            stopSelf()
        }
        
        fullscreenBtn?.setOnClickListener {
            val intent = Intent(this, FloatingPlayerActivity::class.java).apply {
                putExtra("stream_url", player?.currentMediaItem?.localConfiguration?.uri.toString())
                putExtra("title", floatingView?.findViewById<TextView>(R.id.tv_title)?.text.toString())
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            stopSelf()
        }
        
        muteBtn?.setOnClickListener {
            isMuted = !isMuted
            player?.volume = if (isMuted) 0f else 1f
            muteBtn.setImageResource(
                if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
            )
        }
        
        lockBtn?.setOnClickListener {
            controlsLocked = !controlsLocked
            lockBtn.setImageResource(
                if (controlsLocked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open
            )
            
            if (controlsLocked) {
                // Hide controls when locked
                controlsContainer?.visibility = View.GONE
                bottomControlsContainer?.visibility = View.GONE
            } else {
                // Show controls when unlocked
                controlsContainer?.visibility = View.VISIBLE
                bottomControlsContainer?.visibility = View.VISIBLE
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
        }
        
        seekBackBtn?.setOnClickListener {
            player?.seekBack()
        }
        
        seekForwardBtn?.setOnClickListener {
            player?.seekForward()
        }
        
        // Resize button cycles through preset sizes
        var currentSize = 1  // Start at index 1 (320x180)
        val sizes = arrayOf(
            dpToPx(240) to dpToPx(135),   // Small
            dpToPx(320) to dpToPx(180),   // Medium (default)
            dpToPx(480) to dpToPx(270),   // Large
            dpToPx(640) to dpToPx(360)    // Extra Large
        )
        
        resizeBtn?.setOnClickListener {
            currentSize = (currentSize + 1) % sizes.size
            params?.apply {
                width = sizes[currentSize].first
                height = sizes[currentSize].second
                windowManager?.updateViewLayout(floatingView, this)
            }
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
        // Setup pinch-to-resize gesture detector
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (controlsLocked) return false
                
                val scaleFactor = detector.scaleFactor
                
                params?.let { p ->
                    // Calculate new dimensions while maintaining aspect ratio
                    val newWidth = (p.width * scaleFactor).toInt()
                    val newHeight = (newWidth * 9 / 16)  // Maintain 16:9 aspect ratio
                    
                    // Clamp to min/max bounds
                    if (newWidth in MIN_WIDTH..MAX_WIDTH && newHeight in MIN_HEIGHT..MAX_HEIGHT) {
                        p.width = newWidth
                        p.height = newHeight
                        windowManager?.updateViewLayout(floatingView, p)
                    }
                }
                return true
            }
        })
        
        // Setup drag and click handling on player view
        playerView?.setOnTouchListener { view, event ->
            // Pass event to scale detector first
            scaleGestureDetector.onTouchEvent(event)
            
            // If pinch-zooming, don't handle drag
            if (scaleGestureDetector.isInProgress) {
                return@setOnTouchListener true
            }
            
            // If locked, consume touch events but don't do anything
            if (controlsLocked) {
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
                        startClickTime = System.currentTimeMillis()
                        isDragging = false
                        false  // Allow other touch handlers to see this event
                    }
                    
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        
                        // Only consider it a drag if movement is significant
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isDragging = true
                            p.x = initialX + dx
                            p.y = initialY + dy
                            windowManager?.updateViewLayout(floatingView, p)
                            true  // Consume event during drag
                        } else {
                            false
                        }
                    }
                    
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - startClickTime
                        val dx = abs(event.rawX - initialTouchX)
                        val dy = abs(event.rawY - initialTouchY)
                        
                        // Distinguish between click and drag
                        if (!isDragging && duration < 300 && dx < 20 && dy < 20) {
                            // It was a click - toggle play/pause
                            if (player?.isPlaying == true) {
                                player?.pause()
                            } else {
                                player?.play()
                            }
                        }
                        isDragging = false
                        true
                    }
                    
                    else -> false
                }
            } ?: false
        }
        
        // Alternative: Handle unlock on locked screen by tapping container
        floatingView?.setOnClickListener {
            if (controlsLocked) {
                controlsLocked = false
                floatingView?.findViewById<ImageButton>(R.id.btn_lock)?.setImageResource(R.drawable.ic_lock_open)
                controlsContainer?.visibility = View.VISIBLE
                bottomControlsContainer?.visibility = View.VISIBLE
            }
        }
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
        
        player?.release()
        player = null
        
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
            floatingView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
