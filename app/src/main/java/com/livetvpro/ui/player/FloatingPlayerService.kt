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
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                putExtra(EXTRA_STREAM_URL, channel.links.firstOrNull() ?: "")
                putExtra(EXTRA_TITLE, channel.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
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
        
        startForeground(NOTIFICATION_ID, createNotification(title))
        
        if (floatingView == null) {
            createFloatingView(streamUrl, title)
        }
        
        return START_STICKY
    }

    private fun createFloatingView(streamUrl: String, title: String) {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_player_window, null)
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100
        params.width = dpToPx(320)
        params.height = dpToPx(240)
        
        windowManager?.addView(floatingView, params)
        
        setupPlayer(streamUrl, title)
        setupControls(params)
    }

    private fun setupPlayer(streamUrl: String, title: String) {
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
        player?.release()
        player = null
        
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
            floatingView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
