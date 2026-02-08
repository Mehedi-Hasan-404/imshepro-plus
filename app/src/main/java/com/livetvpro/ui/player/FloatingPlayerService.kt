package com.livetvpro.livetvpro.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.ImageButton
import android.widget.ImageView
import androidx.media3.ui.PlayerView
import com.livetvpro.livetvpro.R
import kotlin.math.abs
import kotlin.math.max

class FloatingPlayerService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var playerView: PlayerView
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    // State
    private var isLocked = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var startClickTime: Long = 0

    // Size limits (in pixels)
    private val MIN_WIDTH = 300
    private val MAX_WIDTH = 1200

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()

        floatingView = LayoutInflater.from(this).inflate(R.layout.service_floating_player, null)
        playerView = floatingView.findViewById(R.id.player_view)
        
        // Initialize Window Manager
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // --- FIX 1: Initial Size (Not Full Screen) ---
        // We set a fixed starting width (e.g., 300dp converted to px)
        val metrics = resources.displayMetrics
        val initialWidth = (300 * metrics.density).toInt()
        val initialHeight = (169 * metrics.density).toInt() // 16:9 ratio

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
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, // Allows dragging off-screen slightly
            PixelFormat.TRANSLUCENT
        )

        // Center initially
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (metrics.widthPixels - initialWidth) / 2
        params.y = (metrics.heightPixels - initialHeight) / 2

        windowManager.addView(floatingView, params)

        setupControls()
        setupGestures()
    }

    private fun setupControls() {
        // Setup Close Button
        floatingView.findViewById<ImageButton>(R.id.btn_pip_close).setOnClickListener {
            stopSelf()
        }

        // --- FIX 2: Lock Button Logic ---
        val btnLock = floatingView.findViewById<ImageButton>(R.id.btn_pip_lock)
        val imgLockedStatus = floatingView.findViewById<ImageView>(R.id.img_locked_status)

        btnLock.setOnClickListener {
            isLocked = !isLocked
            if (isLocked) {
                // Lock mode: Hide controls, show lock icon briefly
                playerView.useController = false
                imgLockedStatus.visibility = View.VISIBLE
                btnLock.setImageResource(R.drawable.ic_lock) // Change icon to closed lock
            } else {
                // Unlock mode
                playerView.useController = true
                imgLockedStatus.visibility = View.GONE
                btnLock.setImageResource(R.drawable.ic_lock_open)
            }
        }
        
        // Handle unlocking when the view is tapped while locked
        floatingView.findViewById<View>(R.id.floating_container).setOnClickListener {
             if (isLocked) {
                 isLocked = false
                 playerView.useController = true
                 imgLockedStatus.visibility = View.GONE
                 btnLock.setImageResource(R.drawable.ic_lock_open)
             }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        // --- FIX 3: Slipping/Pinching Resize ---
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (isLocked) return false

                val scaleFactor = detector.scaleFactor
                
                // Calculate new width/height based on pinch
                val newWidth = (params.width * scaleFactor).toInt()
                val newHeight = (params.height * scaleFactor).toInt()

                // Prevent making it too small or too big
                if (newWidth in MIN_WIDTH..MAX_WIDTH) {
                    params.width = newWidth
                    params.height = newHeight
                    windowManager.updateViewLayout(floatingView, params)
                }
                return true
            }
        })

        // --- FIX 4: Draggable Logic ---
        playerView.setOnTouchListener { view, event ->
            // Pass event to scale detector first
            scaleGestureDetector.onTouchEvent(event)
            
            // If dragging with two fingers (zooming), don't move window
            if (scaleGestureDetector.isInProgress) return@setOnTouchListener true

            if (isLocked) return@setOnTouchListener true // Consume touch if locked

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    startClickTime = System.currentTimeMillis()
                    return@setOnTouchListener false // Return false so PlayerView can handle click
                }
                
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    // Only move if drag is significant (prevent jitter on clicks)
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(floatingView, params)
                        return@setOnTouchListener true // Consume event (don't pass to player controls)
                    }
                }
                
                MotionEvent.ACTION_UP -> {
                    // Detect click vs drag
                    val duration = System.currentTimeMillis() - startClickTime
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    
                    if (duration < 200 && dx < 10 && dy < 10) {
                        // It was a click -> Toggle controls manually or let PlayerView handle it
                        if (playerView.isControllerFullyVisible) {
                            playerView.hideController()
                        } else {
                            playerView.showController()
                        }
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatingView.isInitialized) windowManager.removeView(floatingView)
    }
}
