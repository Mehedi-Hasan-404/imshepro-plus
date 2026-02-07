package com.livetvpro.ui.player

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.R
import com.livetvpro.data.local.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * FloatingPlayerActivity
 * Manages floating video player windows that can be dragged, resized, and controlled
 */
@AndroidEntryPoint
class FloatingPlayerActivity : AppCompatActivity() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if floating player is enabled
        if (!preferencesManager.isFloatingPlayerEnabled()) {
            Toast.makeText(this, "Please enable Floating Player from sidebar", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Check overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            showPermissionDialog()
            return
        }
        
        // Get stream data from intent
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""
        val streamTitle = intent.getStringExtra(EXTRA_STREAM_TITLE) ?: "Live Stream"
        
        if (streamUrl.isEmpty()) {
            Toast.makeText(this, "No stream URL provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Start floating player service
        val serviceIntent = Intent(this, FloatingPlayerService::class.java).apply {
            putExtra(EXTRA_STREAM_URL, streamUrl)
            putExtra(EXTRA_STREAM_TITLE, streamTitle)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        // Close this activity immediately (service will handle the floating window)
        finish()
    }
    
    private fun showPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Permission Required")
            .setMessage("Floating Player requires permission to display over other apps.")
            .setPositiveButton("Grant Permission") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                // Permission granted, restart the activity
                recreate()
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    companion object {
        private const val REQUEST_CODE_OVERLAY_PERMISSION = 5001
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_STREAM_TITLE = "extra_stream_title"
        
        /**
         * Start floating player with stream
         */
        fun start(context: Context, streamUrl: String, streamTitle: String) {
            val intent = Intent(context, FloatingPlayerActivity::class.java).apply {
                putExtra(EXTRA_STREAM_URL, streamUrl)
                putExtra(EXTRA_STREAM_TITLE, streamTitle)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

/**
 * FloatingPlayerService
 * Service that manages multiple floating player windows
 */
@UnstableApi
class FloatingPlayerService : Service() {

    private val floatingWindows = mutableListOf<FloatingWindow>()
    private lateinit var windowManager: WindowManager
    private lateinit var preferencesManager: PreferencesManager
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        preferencesManager = PreferencesManager(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val streamUrl = it.getStringExtra(FloatingPlayerActivity.EXTRA_STREAM_URL) ?: return START_NOT_STICKY
            val streamTitle = it.getStringExtra(FloatingPlayerActivity.EXTRA_STREAM_TITLE) ?: "Live Stream"
            
            // Check max windows limit
            val maxWindows = preferencesManager.getMaxFloatingWindows()
            
            if (maxWindows == 0) {
                Toast.makeText(this, "Multiple floating windows disabled", Toast.LENGTH_SHORT).show()
                return START_NOT_STICKY
            }
            
            // Remove oldest window if limit reached
            if (floatingWindows.size >= maxWindows) {
                floatingWindows.firstOrNull()?.close()
                floatingWindows.removeFirstOrNull()
            }
            
            // Create new floating window
            val floatingWindow = FloatingWindow(
                context = this,
                windowManager = windowManager,
                streamUrl = streamUrl,
                streamTitle = streamTitle,
                onClose = { window ->
                    floatingWindows.remove(window)
                    if (floatingWindows.isEmpty()) {
                        stopSelf()
                    }
                }
            )
            
            floatingWindow.show()
            floatingWindows.add(floatingWindow)
        }
        
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        floatingWindows.forEach { it.close() }
        floatingWindows.clear()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * FloatingWindow
 * Individual floating player window with drag, resize, and playback controls
 */
@UnstableApi
@SuppressLint("ClickableViewAccessibility")
class FloatingWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val streamUrl: String,
    private val streamTitle: String,
    private val onClose: (FloatingWindow) -> Unit
) {
    
    private var floatingView: View? = null
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    
    // Window position and size
    private var windowX = 100
    private var windowY = 100
    private var windowWidth = 800
    private var windowHeight = 450
    
    // Drag handling
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    
    // Resize handling
    private var isResizing = false
    private var resizeInitialWidth = 0
    private var resizeInitialHeight = 0
    private var resizeInitialTouchX = 0f
    private var resizeInitialTouchY = 0f
    
    private lateinit var layoutParams: WindowManager.LayoutParams
    
    fun show() {
        // Inflate floating player layout
        val inflater = LayoutInflater.from(context)
        floatingView = inflater.inflate(R.layout.floating_player_window, null)
        
        // Setup window parameters
        layoutParams = WindowManager.LayoutParams(
            windowWidth,
            windowHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = windowX
            y = windowY
        }
        
        // Add view to window manager
        windowManager.addView(floatingView, layoutParams)
        
        // Setup player
        setupPlayer()
        
        // Setup controls
        setupControls()
        
        // Setup drag and resize
        setupDragAndResize()
    }
    
    private fun setupPlayer() {
        playerView = floatingView?.findViewById(R.id.floating_player_view)
        
        // Create ExoPlayer
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(DefaultHttpDataSource.Factory())
            )
            .build()
        
        // Attach player to view
        playerView?.player = player
        
        // Prepare media
        val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.playWhenReady = true
        
        // Add listener
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        // Stream is ready
                    }
                    Player.STATE_ENDED -> {
                        // Stream ended
                    }
                    Player.STATE_BUFFERING -> {
                        // Buffering
                    }
                    Player.STATE_IDLE -> {
                        // Idle
                    }
                }
            }
        })
    }
    
    private fun setupControls() {
        val floatingView = floatingView ?: return
        
        // Close button
        val btnClose = floatingView.findViewById<ImageButton>(R.id.btn_close)
        btnClose?.setOnClickListener {
            close()
        }
        
        // Fullscreen button (open in PlayerActivity)
        val btnFullscreen = floatingView.findViewById<ImageButton>(R.id.btn_fullscreen)
        btnFullscreen?.setOnClickListener {
            openInPlayerActivity()
        }
        
        // Play/Pause button
        val btnPlayPause = floatingView.findViewById<ImageButton>(R.id.btn_play_pause)
        btnPlayPause?.setOnClickListener {
            player?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    btnPlayPause.setImageResource(R.drawable.ic_play)
                } else {
                    player.play()
                    btnPlayPause.setImageResource(R.drawable.ic_pause)
                }
            }
        }
        
        // Mute button
        val btnMute = floatingView.findViewById<ImageButton>(R.id.btn_mute)
        btnMute?.setOnClickListener {
            player?.let { player ->
                val newVolume = if (player.volume > 0) 0f else 1f
                player.volume = newVolume
                btnMute.setImageResource(
                    if (newVolume > 0) R.drawable.ic_volume_up else R.drawable.ic_volume_off
                )
            }
        }
        
        // Update play/pause icon based on player state
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause?.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        })
    }
    
    private fun setupDragAndResize() {
        val floatingView = floatingView ?: return
        
        // Drag handle
        val dragHandle = floatingView.findViewById<View>(R.id.drag_handle)
        dragHandle?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    
                    // Check if user is dragging (moved more than 10px)
                    if (!isDragging && (abs(deltaX) > 10 || abs(deltaY) > 10)) {
                        isDragging = true
                    }
                    
                    if (isDragging) {
                        layoutParams.x = initialX + deltaX.toInt()
                        layoutParams.y = initialY + deltaY.toInt()
                        windowManager.updateViewLayout(floatingView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // It was a click, toggle controls
                        toggleControls()
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
        
        // Resize handle
        val resizeHandle = floatingView.findViewById<View>(R.id.resize_handle)
        resizeHandle?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resizeInitialWidth = layoutParams.width
                    resizeInitialHeight = layoutParams.height
                    resizeInitialTouchX = event.rawX
                    resizeInitialTouchY = event.rawY
                    isResizing = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isResizing) {
                        val deltaX = event.rawX - resizeInitialTouchX
                        val deltaY = event.rawY - resizeInitialTouchY
                        
                        // Calculate new size (maintain 16:9 aspect ratio)
                        val newWidth = max(400, resizeInitialWidth + deltaX.toInt())
                        val newHeight = (newWidth * 9 / 16)
                        
                        // Limit maximum size
                        val screenWidth = context.resources.displayMetrics.widthPixels
                        val screenHeight = context.resources.displayMetrics.heightPixels
                        
                        layoutParams.width = min(newWidth, screenWidth - 100)
                        layoutParams.height = min(newHeight, screenHeight - 100)
                        
                        windowManager.updateViewLayout(floatingView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isResizing = false
                    true
                }
                else -> false
            }
        }
    }
    
    private fun toggleControls() {
        val controlsLayout = floatingView?.findViewById<ViewGroup>(R.id.controls_layout)
        controlsLayout?.let {
            it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }
    
    private fun openInPlayerActivity() {
        // Stop current playback
        val currentPosition = player?.currentPosition ?: 0
        
        // Open PlayerActivity
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("stream_url", streamUrl)
            putExtra("stream_title", streamTitle)
            putExtra("start_position", currentPosition)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        
        // Close floating window
        close()
    }
    
    fun close() {
        try {
            // Release player
            player?.release()
            player = null
            
            // Remove view from window manager
            floatingView?.let {
                windowManager.removeView(it)
            }
            floatingView = null
            
            // Notify that this window is closed
            onClose(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
