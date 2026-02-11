package com.livetvpro.ui.player

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import android.view.*
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.utils.FloatingPlayerManager

@UnstableApi
class FloatingPlayerService : Service() {

    companion object {
        private const val ACTION_START = "com.livetvpro.START_FLOATING"
        private const val ACTION_STOP = "com.livetvpro.STOP_FLOATING"
        private const val EXTRA_INSTANCE_ID = "extra_instance_id"
        internal const val EXTRA_CHANNEL = "extra_channel"
        private const val EXTRA_EVENT = "extra_event"
        private const val EXTRA_WINDOW_INDEX = "extra_window_index"
        private const val NOTIFICATION_CHANNEL_ID = "floating_player_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "Floating Player"

        fun startFloatingPlayer(
            context: Context,
            instanceId: String,
            channel: Channel? = null,
            event: LiveEvent? = null,
            windowIndex: Int = 0
        ): Boolean {
            return try {
                val intent = Intent(context, FloatingPlayerService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_INSTANCE_ID, instanceId)
                    putExtra(EXTRA_WINDOW_INDEX, windowIndex)
                    channel?.let { putExtra(EXTRA_CHANNEL, it as Parcelable) }
                    event?.let { putExtra(EXTRA_EVENT, it as Parcelable) }
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                false
            }
        }

        fun stopFloatingPlayer(context: Context, instanceId: String) {
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_INSTANCE_ID, instanceId)
            }
            context.startService(intent)
        }
    }

    private var instanceId: String = ""
    private var windowIndex: Int = 0
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var playerView: PlayerView? = null
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var channel: Channel? = null
    private var event: LiveEvent? = null
    private var contentName: String = ""
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStartAction(intent)
            ACTION_STOP -> handleStopAction(intent)
        }
        return START_NOT_STICKY
    }

    private fun handleStartAction(intent: Intent) {
        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: return
        windowIndex = intent.getIntExtra(EXTRA_WINDOW_INDEX, 0)

        channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CHANNEL)
        }

        event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_EVENT, LiveEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_EVENT)
        }

        contentName = channel?.name ?: event?.title ?: "Unknown"

        startForeground(instanceId.hashCode(), createNotification())
        createFloatingWindow()
        setupPlayer()
    }

    private fun handleStopAction(intent: Intent) {
        val targetInstanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
        if (targetInstanceId == instanceId) {
            cleanup()
            stopSelf()
        }
    }

    @SuppressLint("InflateParams")
    private fun createFloatingWindow() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            floatingView = LayoutInflater.from(this).inflate(R.layout.floating_player_window, null)
            playerView = floatingView?.findViewById(R.id.floating_player_view)

            val params = createWindowLayoutParams()
            calculateInitialPosition(params)
            windowManager?.addView(floatingView, params)

            setupTouchListeners(params)
            setupControlButtons()
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun createWindowLayoutParams(): WindowManager.LayoutParams {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = resources.displayMetrics.density
        val windowWidth = (320 * density).toInt()
        val windowHeight = (windowWidth * 9 / 16)

        return WindowManager.LayoutParams(
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
    }

    private fun calculateInitialPosition(params: WindowManager.LayoutParams) {
        val display = windowManager?.defaultDisplay
        val size = Point()
        display?.getSize(size)

        params.x = (size.x - params.width) / 2
        params.y = (size.y - params.height) / 2
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListeners(params: WindowManager.LayoutParams) {
        floatingView?.setOnTouchListener { view, event ->
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

    private fun setupControlButtons() {
        floatingView?.apply {
            findViewById<ImageButton>(R.id.btn_close)?.setOnClickListener {
                cleanup()
                stopSelf()
            }

            findViewById<ImageButton>(R.id.btn_fullscreen)?.setOnClickListener {
                openFullscreen()
            }

            findViewById<ImageButton>(R.id.btn_play_pause)?.setOnClickListener {
                player?.let { p ->
                    if (p.isPlaying) p.pause() else p.play()
                }
            }
        }
    }

    private fun setupPlayer() {
        try {
            trackSelector = DefaultTrackSelector(this)
            player = ExoPlayer.Builder(this).setTrackSelector(trackSelector!!).build()
            playerView?.player = player

            val streamUrl = channel?.streamUrl ?: event?.links?.firstOrNull()?.url

            if (streamUrl != null) {
                val mediaItem = MediaItem.fromUri(streamUrl)
                player?.apply {
                    setMediaItem(mediaItem)
                    prepare()
                    playWhenReady = true
                }
            } else {
                stopSelf()
            }
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun openFullscreen() {
        if (channel != null) {
            FloatingPlayerActivity.startWithChannel(this, channel!!)
        } else if (event != null) {
            FloatingPlayerActivity.startWithEvent(this, event!!)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows floating video player is active"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, FloatingPlayerActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            instanceId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Floating Player")
            .setContentText("Playing: $contentName")
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun cleanup() {
        player?.release()
        player = null
        trackSelector = null

        try {
            floatingView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
        }

        floatingView = null
        playerView = null
        windowManager = null

        FloatingPlayerManager.removePlayer(instanceId)
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
