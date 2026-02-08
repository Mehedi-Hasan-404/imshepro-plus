package com.livetvpro.ui.player

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
    
    private var controlsContainer: View? = null
    private var bottomControlsContainer: View? = null
    
    companion object {
        const val EXTRA_CHANNEL = "extra_channel"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val ACTION_STOP = "action_stop"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_player_channel"
        
        fun start(context: Context, channel: Channel) {
            android.util.Log.e("DEBUG_SERVICE", "FloatingPlayerService.start() called")
            
            try {
                if (channel.links == null || channel.links.isEmpty()) {
                    android.util.Log.e("DEBUG_SERVICE", "ERROR: Channel has no links!")
                    android.widget.Toast.makeText(
                        context,
                        "ERROR: No stream available",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return
                }
                
                val streamUrl = channel.links.firstOrNull()?.url ?: ""
                
                if (streamUrl.isEmpty()) {
                    android.util.Log.e("DEBUG_SERVICE", "ERROR: Stream URL is empty!")
                    android.widget.Toast.makeText(
                        context,
                        "ERROR: Invalid stream URL",
                        android.widget.Toast.LENGTH_LONG
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
                android.util.Log.e("DEBUG_SERVICE", "EXCEPTION IN start()!", e)
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
            // ✅ CRITICAL FIX: Wrap Service context with app theme
            val themeContext = android.view.ContextThemeWrapper(this, R.style.Theme_LiveTVPro)
            floatingView = LayoutInflater.from(themeContext).inflate(R.layout.floating_player_window, null)
            
            // ✅ FIX 1: Use WRAP_CONTENT instead of fixed sizes to respect CardView radius
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            // ✅ FIX 2: Set specific width/height (not MATCH_PARENT which causes full screen)
            val params = WindowManager.LayoutParams(
                dpToPx(400),  // Width in pixels
                dpToPx(300),  // Height in pixels
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,  // ✅ FIX 3: NOT_FOCUSABLE allows touches
                PixelFormat.TRANSLUCENT
            )
            
            // ✅ FIX 4: Position at top-right corner instead of filling screen
            params.gravity = Gravity.TOP or Gravity.END
            params.x = dpToPx(16)  // 16dp margin from right
            params.y = dpToPx(100) // 100dp from top
            
            windowManager?.addView(floatingView, params)
            
            setupControls(params)
            setupPlayer(streamUrl, title)
            
        } catch (e: Exception) {
            android.util.Log.e("DEBUG_SERVICE", "Error creating floating view", e)
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
                useController = false
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
            android.util.Log.e("DEBUG_SERVICE", "Error setting up player", e)
            e.printStackTrace()
        }
    }

    private fun setupControls(params: WindowManager.LayoutParams) {
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
        
        // ✅ FIX 5: Lock button properly toggles controls AND updates params
        lockBtn?.setOnClickListener {
            controlsLocked = !controlsLocked
            lockBtn.setImageResource(
                if (controlsLocked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open
            )
            
            // Update window params to enable/disable touch events
            if (controlsLocked) {
                // Hide controls
                controlsContainer?.visibility = View.GONE
                bottomControlsContainer?.visibility = View.GONE
                // Make window non-touchable (can't accidentally tap controls)
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                // Show controls
                controlsContainer?.visibility = View.VISIBLE
                bottomControlsContainer?.visibility = View.VISIBLE
                // Make window touchable
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            windowManager?.updateViewLayout(floatingView, params)
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
        
        // ✅ FIX 6: Resize button with proper size cycling
        var currentSize = 0
        val sizes = arrayOf(
            dpToPx(320) to dpToPx(240),  // Small
            dpToPx(400) to dpToPx(300),  // Medium (default)
            dpToPx(480) to dpToPx(360),  // Large
            dpToPx(560) to dpToPx(420)   // Extra Large
        )
        
        resizeBtn?.setOnClickListener {
            currentSize = (currentSize + 1) % sizes.size
            params.width = sizes[currentSize].first
            params.height = sizes[currentSize].second
            windowManager?.updateViewLayout(floatingView, params)
            android.widget.Toast.makeText(
                this, 
                "Size: ${sizes[currentSize].first}x${sizes[currentSize].second}", 
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        
        // ✅ FIX 7: Drag functionality - attach to the drag_handle View
        setupDragListener(params)
        
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playPauseBtn?.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        })
    }

    // ✅ FIX 8: Proper drag implementation
    private fun setupDragListener(params: WindowManager.LayoutParams) {
        val dragHandle = floatingView?.findViewById<View>(R.id.drag_handle)
        
        dragHandle?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!controlsLocked) {
                        params.x = initialX + (initialTouchX - event.rawX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                    }
                    true
                }
                else -> false
            }
        }
        
        // Also allow dragging from the top controls container
        controlsContainer?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    false // Allow child views to receive clicks
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!controlsLocked) {
                        params.x = initialX + (initialTouchX - event.rawX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                    }
                    true
                }
                else -> false
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
