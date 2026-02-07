package com.livetvpro.ui.player

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.livetvpro.R
import com.livetvpro.data.local.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class FloatingPlayerService : Service() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    private lateinit var windowManager: WindowManager
    private val floatingPlayers = mutableListOf<FloatingPlayerWindow>()
    private var maxFloatingWindows = 1

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_STOP_ALL = "ACTION_STOP_ALL"
        const val EXTRA_STREAM_URL = "EXTRA_STREAM_URL"
        const val EXTRA_STREAM_TITLE = "EXTRA_STREAM_TITLE"
        const val EXTRA_PLAYER_ID = "EXTRA_PLAYER_ID"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        maxFloatingWindows = preferencesManager.getMaxFloatingWindows()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_STREAM_URL) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_STREAM_TITLE) ?: "Live Stream"
                val playerId = intent.getIntExtra(EXTRA_PLAYER_ID, floatingPlayers.size)
                
                if (floatingPlayers.size >= maxFloatingWindows) {
                    // Remove oldest player if max reached
                    floatingPlayers.firstOrNull()?.close()
                    floatingPlayers.removeFirstOrNull()
                }
                
                val floatingPlayer = FloatingPlayerWindow(this, windowManager, url, title, playerId)
                floatingPlayer.show()
                floatingPlayers.add(floatingPlayer)
            }
            ACTION_STOP -> {
                val playerId = intent.getIntExtra(EXTRA_PLAYER_ID, -1)
                floatingPlayers.find { it.playerId == playerId }?.let {
                    it.close()
                    floatingPlayers.remove(it)
                }
                if (floatingPlayers.isEmpty()) {
                    stopSelf()
                }
            }
            ACTION_STOP_ALL -> {
                floatingPlayers.forEach { it.close() }
                floatingPlayers.clear()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        floatingPlayers.forEach { it.close() }
        floatingPlayers.clear()
        super.onDestroy()
    }

    inner class FloatingPlayerWindow(
        private val service: Service,
        private val windowManager: WindowManager,
        private val streamUrl: String,
        private val streamTitle: String,
        val playerId: Int
    ) {
        private var floatingView: View? = null
        private var player: ExoPlayer? = null
        private var params: WindowManager.LayoutParams? = null
        
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var isMoving = false
        
        private var currentWidth = 0
        private var currentHeight = 0
        private val minWidth = 300
        private val minHeight = 200

        @OptIn(UnstableApi::class)
        @SuppressLint("ClickableViewAccessibility", "InflateParams")
        fun show() {
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val displayMetrics = service.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            currentWidth = (screenWidth * 0.7f).toInt()
            currentHeight = (currentWidth * 9 / 16)

            params = WindowManager.LayoutParams(
                currentWidth,
                currentHeight,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 50
                y = 100
            }

            floatingView = LayoutInflater.from(service).inflate(R.layout.floating_player_window, null)
            
            val playerView = floatingView?.findViewById<PlayerView>(R.id.floating_player_view)
            val btnClose = floatingView?.findViewById<ImageButton>(R.id.btn_floating_close)
            val btnFullscreen = floatingView?.findViewById<ImageButton>(R.id.btn_floating_fullscreen)
            val btnResize = floatingView?.findViewById<ImageButton>(R.id.btn_floating_resize)
            val dragHandle = floatingView?.findViewById<View>(R.id.floating_drag_handle)

            // Initialize player
            player = ExoPlayer.Builder(service).build().apply {
                val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }
            
            playerView?.player = player

            // Close button
            btnClose?.setOnClickListener {
                close()
                floatingPlayers.remove(this)
                if (floatingPlayers.isEmpty()) {
                    service.stopSelf()
                }
            }

            // Fullscreen button
            btnFullscreen?.setOnClickListener {
                val intent = Intent(service, PlayerActivity::class.java).apply {
                    putExtra("stream_url", streamUrl)
                    putExtra("stream_title", streamTitle)
                    putExtra("from_floating", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(intent)
                close()
                floatingPlayers.remove(this)
                if (floatingPlayers.isEmpty()) {
                    service.stopSelf()
                }
            }

            // Resize functionality
            var isResizing = false
            var resizeStartX = 0f
            var resizeStartY = 0f
            var resizeStartWidth = 0
            var resizeStartHeight = 0

            btnResize?.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isResizing = true
                        resizeStartX = event.rawX
                        resizeStartY = event.rawY
                        resizeStartWidth = currentWidth
                        resizeStartHeight = currentHeight
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isResizing) {
                            val deltaX = (event.rawX - resizeStartX).toInt()
                            val newWidth = (resizeStartWidth + deltaX).coerceIn(minWidth, displayMetrics.widthPixels - 100)
                            val newHeight = (newWidth * 9 / 16)
                            
                            params?.let { p ->
                                p.width = newWidth
                                p.height = newHeight
                                currentWidth = newWidth
                                currentHeight = newHeight
                                floatingView?.let { windowManager.updateViewLayout(it, p) }
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isResizing = false
                        view.parent.requestDisallowInterceptTouchEvent(false)
                        true
                    }
                    else -> false
                }
            }

            // Drag functionality
            dragHandle?.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params?.x ?: 0
                        initialY = params?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        
                        if (abs(deltaX) > 10 || abs(deltaY) > 10) {
                            isMoving = true
                        }
                        
                        params?.let { p ->
                            p.x = initialX + deltaX.toInt()
                            p.y = initialY + deltaY.toInt()
                            floatingView?.let { windowManager.updateViewLayout(it, p) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoving) {
                            // Single tap - show/hide controls
                            playerView?.let {
                                if (it.isControllerFullyVisible) {
                                    it.hideController()
                                } else {
                                    it.showController()
                                }
                            }
                        }
                        isMoving = false
                        true
                    }
                    else -> false
                }
            }

            floatingView?.let { windowManager.addView(it, params) }
        }

        fun close() {
            player?.release()
            player = null
            floatingView?.let { 
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            floatingView = null
        }
    }
}
