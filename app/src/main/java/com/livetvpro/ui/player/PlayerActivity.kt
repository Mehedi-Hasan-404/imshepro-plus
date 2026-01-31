package com.example.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.livetvpro.R
import com.example.livetvpro.databinding.ActivityPlayerBinding
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView

/**
 * PlayerActivity for Live TV Pro
 * 
 * Handles:
 * - Video playback with ExoPlayer
 * - Configuration changes (rotation, screen size)
 * - Picture-in-Picture (PiP) mode
 * - Audio focus management
 * - Proper lifecycle management
 * - State preservation
 * - System UI visibility
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlayerActivity"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
        const val EXTRA_CHANNEL_LOGO = "extra_channel_logo"
        
        private const val KEY_PLAYBACK_POSITION = "playback_position"
        private const val KEY_PLAY_WHEN_READY = "play_when_ready"
    }

    // ==================== View Binding ====================
    
    /**
     * ViewBinding - Lazy initialization to ensure it's only created when needed
     * IMPORTANT: Use lazy for binding to avoid memory leaks
     */
    private val binding by lazy { 
        ActivityPlayerBinding.inflate(layoutInflater) 
    }

    // ==================== Player ====================
    
    /**
     * ExoPlayer instance - nullable to properly handle lifecycle
     */
    private var player: ExoPlayer? = null
    
    /**
     * Player view reference for easy access
     */
    private val playerView: PlayerView by lazy { binding.playerView }

    // ==================== State Management ====================
    
    /**
     * Playback position for state restoration
     */
    private var playbackPosition: Long = 0L
    
    /**
     * Play when ready state for restoration
     */
    private var playWhenReady: Boolean = true
    
    /**
     * Stream URL to play
     */
    private var streamUrl: String? = null
    
    /**
     * Channel name for display
     */
    private var channelName: String? = null
    
    /**
     * Track if user is finishing the activity
     */
    private var isUserFinishing = false

    // ==================== Audio Focus ====================
    
    /**
     * Audio manager for focus handling
     */
    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    
    /**
     * Audio focus request (API 26+)
     */
    private var audioFocusRequest: AudioFocusRequest? = null
    
    /**
     * Restore audio focus callback
     */
    private var restoreAudioFocus: () -> Unit = {}

    // ==================== Picture-in-Picture ====================
    
    /**
     * Track if we're in PiP mode
     */
    private var isInPipMode = false

    // ==================== Broadcast Receivers ====================
    
    /**
     * Receiver for handling audio becoming noisy (headphones unplugged)
     */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.d(TAG, "Audio becoming noisy - pausing playback")
                player?.pause()
            }
        }
    }
    
    /**
     * Track if noisy receiver is registered
     */
    private var noisyReceiverRegistered = false

    /**
     * Audio focus change listener
     */
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost - pausing")
                val wasPlaying = player?.isPlaying == true
                player?.pause()
                restoreAudioFocus = {
                    if (wasPlaying) {
                        player?.play()
                    }
                }
            }
            
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus lost (can duck) - lowering volume")
                player?.volume = 0.3f
                restoreAudioFocus = {
                    player?.volume = 1.0f
                }
            }
            
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained - restoring")
                player?.volume = 1.0f
                restoreAudioFocus()
                restoreAudioFocus = {}
            }
        }
    }

    // ==================== Lifecycle Methods ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "onCreate")
        
        // Set content view using ViewBinding
        setContentView(binding.root)
        
        // Extract intent data
        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME)
        
        // Restore saved state if available
        savedInstanceState?.let {
            playbackPosition = it.getLong(KEY_PLAYBACK_POSITION, 0L)
            playWhenReady = it.getBoolean(KEY_PLAY_WHEN_READY, true)
        }
        
        // Setup UI
        setupSystemUI()
        setupWindowFlags()
        setupBackPressHandler()
        
        // Setup audio focus
        setupAudioFocus()
        
        // Initialize player in onStart (not here) to follow best practices
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
        
        // Initialize player
        if (player == null) {
            initializePlayer()
        }
        
        // Register noisy receiver
        if (!noisyReceiverRegistered) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            registerReceiver(noisyReceiver, filter)
            noisyReceiverRegistered = true
        }
        
        // Update PiP params
        updatePictureInPictureParams()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        
        // Resume playback if not in PiP
        if (!isInPipMode && player != null) {
            player?.playWhenReady = playWhenReady
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause - isInPipMode: $isInPipMode, isFinishing: $isFinishing")
        
        // Save playback state
        player?.let {
            playbackPosition = it.currentPosition
            playWhenReady = it.playWhenReady
        }
        
        // Pause if not in PiP mode and not finishing
        if (!isInPipMode && !isUserFinishing) {
            player?.pause()
        }
        
        // If finishing and not in PiP, restore system UI immediately for smooth exit
        if (isFinishing && !isInPipMode) {
            restoreSystemUI()
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")
        
        // Unregister noisy receiver
        if (noisyReceiverRegistered) {
            runCatching {
                unregisterReceiver(noisyReceiver)
            }
            noisyReceiverRegistered = false
        }
        
        // Release player if finishing
        if (isFinishing) {
            releasePlayer()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        
        // Ensure player is released
        releasePlayer()
        
        // Abandon audio focus
        abandonAudioFocus()
        
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        
        // Save playback state
        player?.let {
            outState.putLong(KEY_PLAYBACK_POSITION, it.currentPosition)
            outState.putBoolean(KEY_PLAY_WHEN_READY, it.playWhenReady)
        }
    }

    // ==================== Configuration Changes ====================

    /**
     * Handle configuration changes (rotation, screen size, etc.)
     * 
     * IMPORTANT: This is called when configChanges are handled manually in manifest:
     * android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenLayout|uiMode|screenSize|smallestScreenSize"
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        Log.d(TAG, "onConfigurationChanged - orientation: ${newConfig.orientation}")
        
        // Handle orientation change
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                Log.d(TAG, "Switched to landscape")
                setupFullscreen()
            }
            Configuration.ORIENTATION_PORTRAIT -> {
                Log.d(TAG, "Switched to portrait")
                setupFullscreen()
            }
        }
        
        // Update PiP params if not in PiP mode
        if (!isInPipMode) {
            updatePictureInPictureParams()
        }
        
        // Adjust player view for new configuration
        adjustPlayerView()
    }

    // ==================== Picture-in-Picture ====================

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        
        Log.d(TAG, "onUserLeaveHint - entering PiP if possible")
        
        // Enter PiP mode when user presses home
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player?.isPlaying == true) {
            enterPictureInPictureMode()
        }
    }

    /**
     * Called when PiP mode changes
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        Log.d(TAG, "onPictureInPictureModeChanged - isInPiP: $isInPictureInPictureMode")
        
        isInPipMode = isInPictureInPictureMode
        
        if (isInPictureInPictureMode) {
            // Entering PiP mode
            enterPipUIMode()
        } else {
            // Exiting PiP mode
            exitPipUIMode()
        }
    }

    /**
     * Enter PiP mode programmatically
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPictureInPictureMode() {
        runCatching {
            val params = buildPipParams()
            enterPictureInPictureMode(params)
        }.onFailure { e ->
            Log.e(TAG, "Failed to enter PiP mode", e)
        }
    }

    /**
     * Build PiP parameters
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        
        // Set aspect ratio
        val aspectRatio = getVideoAspectRatio()
        if (aspectRatio != null) {
            builder.setAspectRatio(aspectRatio)
        }
        
        // Set auto-enter on API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
        }
        
        return builder.build()
    }

    /**
     * Get video aspect ratio for PiP
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun getVideoAspectRatio(): Rational? {
        val videoFormat = player?.videoFormat ?: return null
        val width = videoFormat.width
        val height = videoFormat.height
        
        if (width == 0 || height == 0) return null
        
        return Rational(width, height).takeIf { 
            it.toFloat() in 0.5f..2.39f 
        }
    }

    /**
     * Update PiP parameters
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePictureInPictureParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                setPictureInPictureParams(buildPipParams())
            }
        }
    }

    /**
     * Configure UI for entering PiP mode
     */
    private fun enterPipUIMode() {
        // Hide controls
        binding.controlsContainer?.visibility = View.GONE
        
        // Clear fullscreen flags
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        
        // Show system bars
        windowInsetsController.apply {
            show(WindowInsetsCompat.Type.systemBars())
            show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    /**
     * Configure UI for exiting PiP mode
     */
    private fun exitPipUIMode() {
        // Show controls
        binding.controlsContainer?.visibility = View.VISIBLE
        
        // Restore fullscreen
        setupWindowFlags()
        setupSystemUI()
    }

    // ==================== Player Management ====================

    /**
     * Initialize the ExoPlayer
     */
    private fun initializePlayer() {
        Log.d(TAG, "initializePlayer")
        
        if (player != null) {
            Log.w(TAG, "Player already initialized")
            return
        }
        
        // Create player
        player = ExoPlayer.Builder(this)
            .build()
            .also { exoPlayer ->
                // Attach to player view
                playerView.player = exoPlayer
                
                // Add listener
                exoPlayer.addListener(playerListener)
                
                // Prepare media
                streamUrl?.let { url ->
                    val mediaItem = MediaItem.fromUri(url)
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    
                    // Restore playback position
                    if (playbackPosition > 0) {
                        exoPlayer.seekTo(playbackPosition)
                    }
                    
                    // Start playback
                    exoPlayer.playWhenReady = playWhenReady
                }
            }
        
        // Request audio focus
        requestAudioFocus()
    }

    /**
     * Release the ExoPlayer
     */
    private fun releasePlayer() {
        Log.d(TAG, "releasePlayer")
        
        player?.let { exoPlayer ->
            // Save state
            playbackPosition = exoPlayer.currentPosition
            playWhenReady = exoPlayer.playWhenReady
            
            // Remove listener
            exoPlayer.removeListener(playerListener)
            
            // Release
            exoPlayer.release()
        }
        
        player = null
    }

    /**
     * Player event listener
     */
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(TAG, "Playback state changed: $playbackState")
            
            when (playbackState) {
                Player.STATE_IDLE -> {
                    Log.d(TAG, "Player is idle")
                }
                Player.STATE_BUFFERING -> {
                    Log.d(TAG, "Player is buffering")
                    binding.loadingProgress?.visibility = View.VISIBLE
                }
                Player.STATE_READY -> {
                    Log.d(TAG, "Player is ready")
                    binding.loadingProgress?.visibility = View.GONE
                    
                    // Update PiP params when ready
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        updatePictureInPictureParams()
                    }
                }
                Player.STATE_ENDED -> {
                    Log.d(TAG, "Playback ended")
                    binding.loadingProgress?.visibility = View.GONE
                }
            }
        }

        override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
            Log.e(TAG, "Player error", error)
            binding.loadingProgress?.visibility = View.GONE
            
            // Show error to user
            showError("Playback error: ${error.message}")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "Is playing changed: $isPlaying")
            
            if (isPlaying) {
                // Keep screen on during playback
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                // Allow screen to turn off when paused
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // ==================== Audio Focus ====================

    /**
     * Setup audio focus request
     */
    private fun setupAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .setAcceptsDelayedFocusGain(true)
                .build()
        }
    }

    /**
     * Request audio focus
     */
    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
                ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * Abandon audio focus
     */
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        restoreAudioFocus = {}
    }

    // ==================== UI Setup ====================

    /**
     * Setup system UI for immersive playback
     */
    private fun setupSystemUI() {
        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // Hide system bars
        windowInsetsController.apply {
            hide(WindowInsetsCompat.Type.statusBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        
        setupFullscreen()
    }

    /**
     * Setup fullscreen mode
     */
    @Suppress("DEPRECATION")
    private fun setupFullscreen() {
        binding.root.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    /**
     * Restore system UI when exiting
     */
    private fun restoreSystemUI() {
        // Clear fullscreen flags
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        
        // Enable window insets
        WindowCompat.setDecorFitsSystemWindows(window, true)
        
        // Show system bars
        windowInsetsController.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            show(WindowInsetsCompat.Type.systemBars())
            show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    /**
     * Setup window flags
     */
    private fun setupWindowFlags() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Get window insets controller
     */
    private val windowInsetsController: WindowInsetsControllerCompat
        get() = WindowCompat.getInsetsController(window, window.decorView)

    /**
     * Adjust player view for configuration
     */
    private fun adjustPlayerView() {
        playerView.requestLayout()
    }

    // ==================== Back Press Handling ====================

    /**
     * Setup back press handler
     */
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })
    }

    /**
     * Handle back press
     */
    private fun handleBackPress() {
        // Try to enter PiP mode if playing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player?.isPlaying == true) {
            enterPictureInPictureMode()
        } else {
            // Finish activity
            isUserFinishing = true
            finish()
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Show error message to user
     */
    private fun showError(message: String) {
        // TODO: Implement error display (Toast, Snackbar, or dialog)
        Log.e(TAG, message)
    }
}
