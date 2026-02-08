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
import android.view.ViewGroup
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
            android.util.Log.e("DEBUG_SERVICE", "========================================")
            android.util.Log.e("DEBUG_SERVICE", "FloatingPlayerService.start() CALLED")
            android.util.Log.e("DEBUG_SERVICE", "========================================")
            
            android.widget.Toast.makeText(
                context,
                "Service start() called",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            
            try {
                android.util.Log.e("DEBUG_SERVICE", "Channel: ${channel.name}")
                android.util.Log.e("DEBUG_SERVICE", "Links: ${channel.links?.size ?: 0}")
                
                // Validate channel has links
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
                android.util.Log.e("DEBUG_SERVICE", "Stream URL: $streamUrl")
                
                if (streamUrl.isEmpty()) {
                    android.util.Log.e("DEBUG_SERVICE", "ERROR: Stream URL is empty!")
                    android.widget.Toast.makeText(
                        context,
                        "ERROR: Invalid stream URL",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return
                }
                
                android.util.Log.e("DEBUG_SERVICE", "Creating intent...")
                val intent = Intent(context, FloatingPlayerService::class.java).apply {
                    putExtra(EXTRA_STREAM_URL, streamUrl)
                    putExtra(EXTRA_TITLE, channel.name)
                }
                
                android.util.Log.e("DEBUG_SERVICE", "Intent created, starting service...")
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.util.Log.e("DEBUG_SERVICE", "Starting FOREGROUND service (Android O+)")
                    context.startForegroundService(intent)
                } else {
                    android.util.Log.e("DEBUG_SERVICE", "Starting service (Pre-O)")
                    context.startService(intent)
                }
                
                android.util.Log.e("DEBUG_SERVICE", "Service started successfully!")
                
                android.widget.Toast.makeText(
                    context,
                    "Service startForegroundService called!",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                
            } catch (e: Exception) {
                android.util.Log.e("DEBUG_SERVICE", "========================================")
                android.util.Log.e("DEBUG_SERVICE", "EXCEPTION IN start()!", e)
                android.util.Log.e("DEBUG_SERVICE", "========================================")
                android.util.Log.e("DEBUG_SERVICE", "Exception type: ${e.javaClass.simpleName}")
                android.util.Log.e("DEBUG_SERVICE", "Exception message: ${e.message}")
                e.printStackTrace()
                
                android.widget.Toast.makeText(
                    context,
                    "EXCEPTION: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            
            android.util.Log.e("DEBUG_SERVICE", "========================================")
            android.util.Log.e("DEBUG_SERVICE", "FloatingPlayerService.start() COMPLETED")
            android.util.Log.e("DEBUG_SERVICE", "========================================")
        }
        
        fun stop(context: Context) {
            android.util.Log.e("DEBUG_SERVICE", "Stopping service")
            context.stopService(Intent(context, FloatingPlayerService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.e("DEBUG_SERVICE", "===>>> SERVICE onCreate() CALLED <<<===")
        android.widget.Toast.makeText(this, "Service onCreate!", android.widget.Toast.LENGTH_SHORT).show()
        
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            android.util.Log.e("DEBUG_SERVICE", "WindowManager obtained: ${windowManager != null}")
            
            createNotificationChannel()
            android.util.Log.e("DEBUG_SERVICE", "Notification channel created")
            
        } catch (e: Exception) {
            android.util.Log.e("DEBUG_SERVICE", "❌ EXCEPTION in onCreate()!", e)
            e.printStackTrace()
            android.widget.Toast.makeText(this, "onCreate failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
        
        android.util.Log.e("DEBUG_SERVICE", "onCreate() completed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.e("DEBUG_SERVICE", "===>>> SERVICE onStartCommand() CALLED <<<===")
        android.util.Log.e("DEBUG_SERVICE", "Intent: $intent")
        android.util.Log.e("DEBUG_SERVICE", "Action: ${intent?.action}")
        
        android.widget.Toast.makeText(this, "Service onStartCommand!", android.widget.Toast.LENGTH_SHORT).show()
        
        try {
            if (intent?.action == ACTION_STOP) {
                android.util.Log.e("DEBUG_SERVICE", "Stop action received")
                stopSelf()
                return START_NOT_STICKY
            }
            
            val streamUrl = intent?.getStringExtra(EXTRA_STREAM_URL) ?: ""
            val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Live Stream"
            
            android.util.Log.e("DEBUG_SERVICE", "Stream URL: $streamUrl")
            android.util.Log.e("DEBUG_SERVICE", "Title: $title")
            
            if (streamUrl.isEmpty()) {
                android.util.Log.e("DEBUG_SERVICE", "Stream URL is empty, stopping service")
                android.widget.Toast.makeText(this, "No stream URL provided", android.widget.Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }
            
            android.util.Log.e("DEBUG_SERVICE", "Creating notification...")
            val notification = createNotification(title)
            
            android.util.Log.e("DEBUG_SERVICE", "Calling startForeground()...")
            startForeground(NOTIFICATION_ID, notification)
            android.util.Log.e("DEBUG_SERVICE", "✅ Started in foreground with notification")
            
            android.widget.Toast.makeText(this, "Foreground service started!", android.widget.Toast.LENGTH_SHORT).show()
            
            if (floatingView == null) {
                android.util.Log.e("DEBUG_SERVICE", "Floating view is null, creating...")
                android.widget.Toast.makeText(this, "Creating floating view...", android.widget.Toast.LENGTH_SHORT).show()
                createFloatingView(streamUrl, title)
            } else {
                android.util.Log.e("DEBUG_SERVICE", "Floating view already exists")
            }
            
        } catch (e: Exception) {
            android.util.Log.e("DEBUG_SERVICE", "❌ EXCEPTION in onStartCommand()!", e)
            android.util.Log.e("DEBUG_SERVICE", "Exception type: ${e.javaClass.simpleName}")
            android.util.Log.e("DEBUG_SERVICE", "Exception message: ${e.message}")
            e.printStackTrace()
            android.widget.Toast.makeText(this, "onStartCommand failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        
        return START_STICKY
    }

    private fun createFloatingView(streamUrl: String, title: String) {
        android.util.Log.e("DEBUG_SERVICE", "===>>> createFloatingView() CALLED <<<===")
        android.util.Log.e("DEBUG_SERVICE", "Stream URL: $streamUrl")
        android.util.Log.e("DEBUG_SERVICE", "Title: $title")
        
        try {
            android.widget.Toast.makeText(this, "Step 1: Inflating layout...", android.widget.Toast.LENGTH_SHORT).show()
            android.util.Log.e("DEBUG_SERVICE", "Step 1: Creating themed context...")
            
            // ✅ CRITICAL FIX: Wrap Service context with app theme
            val themeContext = android.view.ContextThemeWrapper(this, R.style.Theme_LiveTVPro)
            android.util.Log.e("DEBUG_SERVICE", "Themed context created: ${themeContext != null}")
            
            android.util.Log.e("DEBUG_SERVICE", "Step 2: Inflating layout with themed context...")
            floatingView = LayoutInflater.from(themeContext).inflate(R.layout.floating_player_window, null)
            android.util.Log.e("DEBUG_SERVICE", "✅ Layout inflated successfully! FloatingView: ${floatingView != null}")
            
            android.widget.Toast.makeText(this, "Step 2: Layout inflated!", android.widget.Toast.LENGTH_SHORT).show()
            
            android.util.Log.e("DEBUG_SERVICE", "Step 3: Setting up window params...")
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            android.util.Log.e("DEBUG_SERVICE", "Layout flag: $layoutFlag")
            
            val params = WindowManager.LayoutParams(
                dpToPx(400),
                dpToPx(300),
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 100
            params.y = 100
            
            android.util.Log.e("DEBUG_SERVICE", "Window params created - Width: ${params.width}, Height: ${params.height}")
            
            android.widget.Toast.makeText(this, "Step 3: Adding to window...", android.widget.Toast.LENGTH_SHORT).show()
            
            android.util.Log.e("DEBUG_SERVICE", "Step 4: Adding view to window manager...")
            android.util.Log.e("DEBUG_SERVICE", "WindowManager: ${windowManager != null}")
            android.util.Log.e("DEBUG_SERVICE", "FloatingView: ${floatingView != null}")
            
            windowManager?.addView(floatingView, params)
            
            android.util.Log.e("DEBUG_SERVICE", "✅✅✅ VIEW ADDED TO WINDOW SUCCESSFULLY! ✅✅✅")
            android.widget.Toast.makeText(this, "✅ Floating window created!", android.widget.Toast.LENGTH_LONG).show()
            
            android.util.Log.e("DEBUG_SERVICE", "Step 5: Setting up controls...")
            setupControls(params)
            android.util.Log.e("DEBUG_SERVICE", "Controls setup complete")
            
            android.util.Log.e("DEBUG_SERVICE", "Step 6: Setting up player...")
            setupPlayer(streamUrl, title)
            android.util.Log.e("DEBUG_SERVICE", "Player setup complete")
            
            android.util.Log.e("DEBUG_SERVICE", "✅ createFloatingView() COMPLETED SUCCESSFULLY!")
            
        } catch (e: SecurityException) {
            android.util.Log.e("DEBUG_SERVICE", "❌ SECURITY EXCEPTION - Overlay permission not granted!", e)
            android.widget.Toast.makeText(this, "❌ Overlay permission denied!", android.widget.Toast.LENGTH_LONG).show()
            e.printStackTrace()
            stopSelf()
        } catch (e: WindowManager.BadTokenException) {
            android.util.Log.e("DEBUG_SERVICE", "❌ BAD TOKEN EXCEPTION - Window manager error!", e)
            android.widget.Toast.makeText(this, "❌ Window error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            e.printStackTrace()
            stopSelf()
        } catch (e: IllegalStateException) {
            android.util.Log.e("DEBUG_SERVICE", "❌ ILLEGAL STATE EXCEPTION!", e)
            android.widget.Toast.makeText(this, "❌ Illegal state: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            e.printStackTrace()
            stopSelf()
        } catch (e: Exception) {
            android.util.Log.e("DEBUG_SERVICE", "❌❌❌ EXCEPTION in createFloatingView()! ❌❌❌", e)
            android.util.Log.e("DEBUG_SERVICE", "Exception type: ${e.javaClass.simpleName}")
            android.util.Log.e("DEBUG_SERVICE", "Exception message: ${e.message}")
            android.util.Log.e("DEBUG_SERVICE", "Stack trace:")
            e.printStackTrace()
            android.widget.Toast.makeText(this, "❌ Failed: ${e.javaClass.simpleName}: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun setupPlayer(streamUrl: String, title: String) {
        try {
            android.util.Log.e("DEBUG_SERVICE", "Setting up ExoPlayer...")
            
            player = ExoPlayer.Builder(this).build()
            android.util.Log.e("DEBUG_SERVICE", "ExoPlayer created: ${player != null}")
            
            playerView = floatingView?.findViewById(R.id.player_view)
            android.util.Log.e("DEBUG_SERVICE", "PlayerView found: ${playerView != null}")
            
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
            
            android.util.Log.e("DEBUG_SERVICE", "✅ Player setup complete")
            
        } catch (e: Exception) {
            android.util.Log.e("DEBUG_SERVICE", "❌ Error setting up player", e)
            e.printStackTrace()
            android.widget.Toast.makeText(this, "Player setup failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun setupControls(params: WindowManager.LayoutParams) {
        try {
            android.util.Log.e("DEBUG_SERVICE", "Setting up controls...")
            
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
                android.util.Log.e("DEBUG_SERVICE", "Close button clicked")
                stopSelf()
            }
            
            fullscreenBtn?.setOnClickListener {
                android.util.Log.e("DEBUG_SERVICE", "Fullscreen button clicked")
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
                toggleControlsVisibility()
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
            
            var currentSize = 0
            val sizes = arrayOf(
                dpToPx(320) to dpToPx(240),
                dpToPx(400) to dpToPx(300),
                dpToPx(480) to dpToPx(360)
            )
            
            resizeBtn?.setOnClickListener {
                currentSize = (currentSize + 1) % sizes.size
                params.width = sizes[currentSize].first
                params.height = sizes[currentSize].second
                windowManager?.updateViewLayout(floatingView, params)
            }
            
            setupDragListener(params)
            
            player?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playPauseBtn?.setImageResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                }
            })
            
            android.util.Log.e("DEBUG_SERVICE", "✅ Controls setup complete")
            
        } catch (e: Exception) {
            android.util.Log.e("DEBUG_SERVICE", "❌ Error setting up controls", e)
            e.printStackTrace()
        }
    }

    private fun toggleControlsVisibility() {
        if (controlsLocked) {
            controlsContainer?.visibility = View.GONE
            bottomControlsContainer?.visibility = View.GONE
        } else {
            controlsContainer?.visibility = View.VISIBLE
            bottomControlsContainer?.visibility = View.VISIBLE
        }
    }

    private fun setupDragListener(params: WindowManager.LayoutParams) {
        val dragHandle = floatingView?.findViewById<View>(R.id.drag_handle)
        
        dragHandle?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(floatingView, params)
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
        android.util.Log.e("DEBUG_SERVICE", "===>>> onDestroy() called <<<===")
        
        try {
            player?.release()
            player = null
            
            if (floatingView != null) {
                windowManager?.removeView(floatingView)
                floatingView = null
                android.util.Log.e("DEBUG_SERVICE", "Floating view removed")
            }
        } catch (e: Exception) {
            android.util.Log.e("DEBUG_SERVICE", "Error in onDestroy", e)
            e.printStackTrace()
        }
        
        android.util.Log.e("DEBUG_SERVICE", "onDestroy completed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
