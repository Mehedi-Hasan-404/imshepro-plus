package com.example.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.livetvpro.R
import com.example.livetvpro.databinding.ActivityPlayerBinding
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Player.Listener
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.util.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PlayerActivity for Live TV Pro - Full Implementation
 * 
 * This activity manages:
 * - Video playback with ExoPlayer
 * - Configuration changes (rotation, screen size, orientation)
 * - Picture-in-Picture (PiP) mode
 * - Audio focus management
 * - Background playback service
 * - Playlist management (channels)
 * - Playback state persistence and restoration
 * - Audio and subtitle track management
 * - Hardware key event handling (remote controls)
 * - Brightness and volume gesture controls
 * - Player controls UI
 * - Network stream handling
 * - Adaptive bitrate streaming
 * - Error handling and recovery
 * 
 * @see PlayerViewModel for UI state management
 * @see MediaPlaybackService for background playback functionality
 */
@Suppress("TooManyFunctions", "LargeClass")
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LiveTVPlayerActivity"
        
        // Intent extras
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
        const val EXTRA_CHANNEL_LOGO = "extra_channel_logo"
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        const val EXTRA_PLAYLIST = "extra_playlist"
        const val EXTRA_PLAYLIST_INDEX = "extra_playlist_index"
        const val EXTRA_PLAYBACK_POSITION = "extra_playback_position"
        const val EXTRA_HEADERS = "extra_headers"
        const val EXTRA_USER_AGENT = "extra_user_agent"
        
        // State keys
        private const val KEY_PLAYBACK_POSITION = "playback_position"
        private const val KEY_PLAY_WHEN_READY = "play_when_ready"
        private const val KEY_CURRENT_WINDOW = "current_window"
        private const val KEY_PLAYLIST_INDEX = "playlist_index"
        
        // Constants
        private const val BRIGHTNESS_NOT_SET = -1f
        private const val POSITION_NOT_SET = 0L
        private const val MILLISECONDS_TO_SECONDS = 1000
        private const val DEFAULT_SEEK_INCREMENT = 10000L // 10 seconds
        private const val CONTROLS_TIMEOUT = 5000L // 5 seconds
        private const val BRIGHTNESS_STEP = 0.05f
        private const val VOLUME_STEP = 1
        
        // Player preferences
        private const val PREF_REMEMBER_BRIGHTNESS = "remember_brightness"
        private const val PREF_REMEMBER_POSITION = "remember_position"
        private const val PREF_AUTO_PIP = "auto_pip"
        private const val PREF_BACKGROUND_PLAYBACK = "background_playback"
        private const val PREF_PREFERRED_AUDIO_LANGUAGE = "preferred_audio_language"
        private const val PREF_PREFERRED_SUBTITLE_LANGUAGE = "preferred_subtitle_language"
    }

    // ==================== ViewModels and Bindings ====================

    /**
     * ViewBinding - Lazy initialization to ensure it's only created when needed
     */
    private val binding by lazy { 
        ActivityPlayerBinding.inflate(layoutInflater) 
    }

    // ==================== Player Components ====================

    /**
     * ExoPlayer instance - nullable for proper lifecycle handling
     */
    private var player: ExoPlayer? = null
    
    /**
     * Track selector for quality and track selection
     */
    private var trackSelector: DefaultTrackSelector? = null
    
    /**
     * Player view reference
     */
    private val playerView: PlayerView by lazy { binding.playerView }

    // ==================== State Management ====================

    /**
     * Current playback position for state restoration
     */
    private var playbackPosition: Long = 0L
    
    /**
     * Play when ready state
     */
    private var playWhenReady: Boolean = true
    
    /**
     * Current window index (for playlists)
     */
    private var currentWindow: Int = 0
    
    /**
     * Stream URL to play
     */
    private var streamUrl: String? = null
    
    /**
     * Channel name for display
     */
    private var channelName: String? = null
    
    /**
     * Channel ID
     */
    private var channelId: Long = -1
    
    /**
     * Playlist of channel URIs for sequential playback
     */
    private var playlist: List<Uri> = emptyList()
    
    /**
     * Current index in the playlist
     */
    private var playlistIndex: Int = 0
    
    /**
     * Track if user is finishing the activity
     */
    private var isUserFinishing = false
    
    /**
     * Track if player is ready
     */
    private var isReady = false
    
    /**
     * Track if player has been initialized
     */
    private var playerInitialized = false
    
    /**
     * Save playback state job tracking
     */
    private var savePlaybackStateJob: Job? = null
    
    /**
     * Was playing before pause
     */
    private var wasPlayingBeforePause = false

    // ==================== Brightness and Volume ====================

    /**
     * Current brightness level
     */
    private var currentBrightness: Float = BRIGHTNESS_NOT_SET
    
    /**
     * Original brightness before changes
     */
    private var originalBrightness: Float = BRIGHTNESS_NOT_SET
    
    /**
     * Current volume level
     */
    private var currentVolume: Int = 0
    
    /**
     * Maximum volume
     */
    private var maxVolume: Int = 0

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
     * Helper for managing Picture-in-Picture mode
     */
    private lateinit var pipHelper: PipHelper
    
    /**
     * Track if we're in PiP mode
     */
    private var isInPipMode = false

    // ==================== Background Playback ====================

    /**
     * Reference to the background playback service
     */
    private var mediaPlaybackService: MediaPlaybackService? = null
    
    /**
     * Tracks whether we're currently bound to the background playback service
     */
    private var serviceBound = false
    
    /**
     * Service connection for background playback
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as? MediaPlaybackService.MediaPlaybackBinder
            mediaPlaybackService = binder?.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            mediaPlaybackService = null
            serviceBound = false
        }
    }

    // ==================== Broadcast Receivers ====================

    /**
     * Receiver for handling noisy audio events (headphones unplugged)
     */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.d(TAG, "Audio becoming noisy - pausing playback")
                player?.pause()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                Log.d(TAG, "Audio focus request failed")
            }
        }
    }

    // ==================== UI State ====================

    /**
     * Controls visibility timeout job
     */
    private var hideControlsJob: Job? = null
    
    /**
     * Track if controls are shown
     */
    private var controlsShown = false

    // ==================== Lifecycle Methods ====================

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "onCreate")
        
        // Set content view using ViewBinding
        setContentView(binding.root)
        
        // Extract intent data
        extractIntentData(intent)
        
        // Restore saved state if available
        savedInstanceState?.let {
            playbackPosition = it.getLong(KEY_PLAYBACK_POSITION, 0L)
            playWhenReady = it.getBoolean(KEY_PLAY_WHEN_READY, true)
            currentWindow = it.getInt(KEY_CURRENT_WINDOW, 0)
            playlistIndex = it.getInt(KEY_PLAYLIST_INDEX, 0)
        }
        
        // Setup UI
        setupSystemUI()
        setupWindowFlags()
        setupBackPressHandler()
        setupGestureDetectors()
        
        // Setup audio focus
        setupAudioFocus()
        
        // Initialize PiP helper
        setupPipHelper()
        
        // Setup player controls
        setupPlayerControls()
        
        // Get volume info
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        
        // Setup cutout mode
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
        
        // Setup window flags and system UI
        setupWindowFlags()
        setupSystemUI()
        
        // Initialize player if not already created
        if (player == null) {
            initializePlayer()
        }
        
        // Register noisy receiver
        if (!noisyReceiverRegistered) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            registerReceiver(noisyReceiver, filter)
            noisyReceiverRegistered = true
        }
        
        // Restore brightness if enabled
        if (getPreferenceBoolean(PREF_REMEMBER_BRIGHTNESS, false)) {
            if (currentBrightness != BRIGHTNESS_NOT_SET) {
                setBrightness(currentBrightness)
            }
        }
        
        // End background playback if service is bound
        if (serviceBound) {
            endBackgroundPlayback()
        }
        
        // Update PiP params
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pipHelper.updatePictureInPictureParams()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        
        // Resume playback if not in PiP
        if (!isInPipMode && player != null) {
            player?.playWhenReady = playWhenReady
        }
        
        // Update volume
        updateVolume()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause - isInPipMode: $isInPipMode, isFinishing: $isFinishing")
        
        runCatching {
            val isInPip = isInPictureInPictureMode
            val shouldPause = !getPreferenceBoolean(PREF_BACKGROUND_PLAYBACK, false) || isUserFinishing
            
            // Save state
            player?.let {
                playbackPosition = it.currentPosition
                playWhenReady = it.playWhenReady
            }
            
            // Pause if not in PiP and should pause
            if (!isInPip && shouldPause) {
                wasPlayingBeforePause = player?.isPlaying == true
                player?.pause()
            }
            
            // Restore UI immediately when user is finishing for instant feedback
            if (isUserFinishing && !isInPip) {
                restoreSystemUI()
            }
            
            // Save playback state
            savePlaybackState()
            
        }.onFailure { e ->
            Log.e(TAG, "Error during onPause", e)
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")
        
        runCatching {
            // Stop PiP helper
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                pipHelper.onStop()
            }
            
            // Save playback state
            savePlaybackState()
            
            // Unregister noisy receiver
            if (noisyReceiverRegistered) {
                unregisterReceiver(noisyReceiver)
                noisyReceiverRegistered = false
            }
            
            // Handle background playback
            val backgroundPlaybackEnabled = getPreferenceBoolean(PREF_BACKGROUND_PLAYBACK, false)
            
            if (!serviceBound && backgroundPlaybackEnabled && !isUserFinishing && !isFinishing) {
                // Start background playback
                startBackgroundPlayback()
            } else {
                // Stop playback if not in background mode
                if (!backgroundPlaybackEnabled || isUserFinishing || isFinishing) {
                    player?.pause()
                }
                
                // Unbind service if bound
                if (serviceBound) {
                    runCatching {
                        unbindService(serviceConnection)
                        serviceBound = false
                    }
                }
            }
            
            // Release player if finishing
            if (isFinishing) {
                releasePlayer()
            }
            
        }.onFailure { e ->
            Log.e(TAG, "Error during onStop", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        
        runCatching {
            // Stop and unbind service if finishing
            if (isUserFinishing || isFinishing) {
                if (serviceBound) {
                    runCatching { unbindService(serviceConnection) }
                    serviceBound = false
                }
                stopService(Intent(this, MediaPlaybackService::class.java))
                mediaPlaybackService = null
            }
            
            // Wait for any pending save operation to complete
            savePlaybackStateJob?.let { job ->
                Log.d(TAG, "Waiting for save playback state job to complete...")
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        job.join()
                    }
                }
                Log.d(TAG, "Save playback state job completed")
            }
            
            // Cleanup
            cleanupPlayer()
            cleanupAudio()
            cleanupReceivers()
            
        }.onFailure { e ->
            Log.e(TAG, "Error during onDestroy", e)
        }
        
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        
        // Save playback state
        player?.let {
            outState.putLong(KEY_PLAYBACK_POSITION, it.currentPosition)
            outState.putBoolean(KEY_PLAY_WHEN_READY, it.playWhenReady)
            outState.putInt(KEY_CURRENT_WINDOW, it.currentMediaItemIndex)
        }
        outState.putInt(KEY_PLAYLIST_INDEX, playlistIndex)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun finish() {
        runCatching {
            // Restore UI immediately for responsive exit
            if (!isInPictureInPictureMode) {
                restoreSystemUI()
            }
            isReady = false
        }.onFailure { e ->
            Log.e(TAG, "Error during finish", e)
        }
        
        super.finish()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun finishAndRemoveTask() {
        runCatching {
            // Restore UI immediately for responsive exit
            if (!isInPictureInPictureMode) {
                restoreSystemUI()
            }
            isReady = false
            isUserFinishing = true
        }.onFailure { e ->
            Log.e(TAG, "Error during finishAndRemoveTask", e)
        }
        
        super.finishAndRemoveTask()
    }

    // ==================== Configuration Changes ====================

    /**
     * Handle configuration changes (rotation, screen size, etc.)
     * 
     * This is called when configChanges are handled manually in manifest
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        Log.d(TAG, "onConfigurationChanged - orientation: ${newConfig.orientation}")
        
        if (isReady) {
            handleConfigurationChange(newConfig)
        }
    }

    /**
     * Handles configuration changes by updating UI
     */
    private fun handleConfigurationChange(newConfig: Configuration) {
        if (!isInPictureInPictureMode) {
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
            
            // Update PiP params
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                pipHelper.updatePictureInPictureParams()
            }
            
            // Adjust player view
            adjustPlayerView()
        } else {
            // Hide controls in PiP mode
            hideControls()
        }
    }

    // ==================== Picture-in-Picture ====================

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        
        Log.d(TAG, "onUserLeaveHint - entering PiP if possible")
        
        // Enter PiP mode when user presses home button if auto PiP is enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val autoPip = getPreferenceBoolean(PREF_AUTO_PIP, true)
            if (autoPip && isReady && !isFinishing && player?.isPlaying == true) {
                pipHelper.enterPipMode()
            }
        }
    }

    /**
     * Called when PiP mode changes
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        Log.d(TAG, "onPictureInPictureModeChanged - isInPiP: $isInPictureInPictureMode")
        
        isInPipMode = isInPictureInPictureMode
        
        pipHelper.onPictureInPictureModeChanged(isInPictureInPictureMode)
        
        runCatching {
            if (isInPictureInPictureMode) {
                // Entering PiP mode
                enterPipUIMode()
            } else {
                // Exiting PiP mode
                exitPipUIMode()
            }
        }.onFailure { e ->
            Log.e(TAG, "Error handling PiP mode change", e)
        }
    }

    /**
     * Configure UI for entering PiP mode
     */
    private fun enterPipUIMode() {
        // Hide controls
        binding.controlsContainer?.visibility = View.GONE
        binding.playerControlsLayout?.visibility = View.GONE
        
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
    @RequiresApi(Build.VERSION_CODES.P)
    private fun exitPipUIMode() {
        // Show controls
        binding.controlsContainer?.visibility = View.VISIBLE
        
        // Restore fullscreen
        setupWindowFlags()
        setupSystemUI()
        
        // Show player controls briefly
        showControls()
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
        
        try {
            // Create track selector
            trackSelector = DefaultTrackSelector(this).apply {
                setParameters(
                    buildUponParameters()
                        .setMaxVideoSizeSd()
                        .setPreferredAudioLanguage(
                            getPreferenceString(PREF_PREFERRED_AUDIO_LANGUAGE, "")
                        )
                        .setPreferredTextLanguage(
                            getPreferenceString(PREF_PREFERRED_SUBTITLE_LANGUAGE, "")
                        )
                )
            }
            
            // Create player
            player = ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector!!)
                .build()
                .also { exoPlayer ->
                    // Attach to player view
                    playerView.player = exoPlayer
                    
                    // Add listener
                    exoPlayer.addListener(playerListener)
                    
                    // Prepare media
                    preparePlaylist()
                    
                    // Restore playback position
                    if (playbackPosition > 0) {
                        exoPlayer.seekTo(currentWindow, playbackPosition)
                    }
                    
                    // Start playback
                    exoPlayer.playWhenReady = playWhenReady
                    exoPlayer.prepare()
                }
            
            playerInitialized = true
            
            // Request audio focus
            requestAudioFocus()
            
            // Load saved playback state
            lifecycleScope.launch(Dispatchers.IO) {
                loadPlaybackState()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing player", e)
            showError("Failed to initialize player: ${e.message}")
        }
    }

    /**
     * Prepare playlist or single item
     */
    private fun preparePlaylist() {
        val player = this.player ?: return
        
        if (playlist.isNotEmpty()) {
            // Add all playlist items
            val mediaItems = playlist.map { uri ->
                buildMediaItem(uri)
            }
            player.setMediaItems(mediaItems, playlistIndex, playbackPosition)
        } else {
            // Single stream
            streamUrl?.let { url ->
                val mediaItem = buildMediaItem(Uri.parse(url))
                player.setMediaItem(mediaItem, playbackPosition)
            }
        }
    }

    /**
     * Build media item with headers
     */
    private fun buildMediaItem(uri: Uri): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(uri)
        
        // Add headers if provided
        val headers = intent.getStringArrayExtra(EXTRA_HEADERS)
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        
        if (headers != null || userAgent != null) {
            val requestHeaders = mutableMapOf<String, String>()
            
            userAgent?.let {
                requestHeaders["User-Agent"] = it
            }
            
            headers?.let { headerArray ->
                var i = 0
                while (i < headerArray.size - 1) {
                    requestHeaders[headerArray[i]] = headerArray[i + 1]
                    i += 2
                }
            }
            
            if (requestHeaders.isNotEmpty()) {
                builder.setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setExtras(Bundle().apply {
                            putSerializable("headers", HashMap(requestHeaders))
                        })
                        .build()
                )
            }
        }
        
        // Set MIME type for live streams
        if (uri.toString().contains(".m3u8")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        
        return builder.build()
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
            currentWindow = exoPlayer.currentMediaItemIndex
            
            // Remove listener
            exoPlayer.removeListener(playerListener)
            
            // Release
            exoPlayer.release()
        }
        
        player = null
        trackSelector = null
        playerInitialized = false
    }

    /**
     * Cleanup player resources
     */
    private fun cleanupPlayer() {
        if (!playerInitialized) return
        
        runCatching {
            if (isReady) {
                // Pause first
                player?.pause()
                
                // Brief wait for cleanup
                Thread.sleep(100)
            }
            
            releasePlayer()
            
        }.onFailure { e ->
            Log.e(TAG, "Error cleaning up player", e)
        }
    }

    /**
     * Player event listener
     */
    private val playerListener = object : Listener {
        
        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(TAG, "Playback state changed: $playbackState")
            
            when (playbackState) {
                Player.STATE_IDLE -> {
                    Log.d(TAG, "Player is idle")
                    isReady = false
                }
                Player.STATE_BUFFERING -> {
                    Log.d(TAG, "Player is buffering")
                    binding.loadingProgress?.visibility = View.VISIBLE
                }
                Player.STATE_READY -> {
                    Log.d(TAG, "Player is ready")
                    binding.loadingProgress?.visibility = View.GONE
                    isReady = true
                    
                    // Update PiP params when ready
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        pipHelper.updatePictureInPictureParams()
                    }
                    
                    // Show controls briefly
                    showControls()
                }
                Player.STATE_ENDED -> {
                    Log.d(TAG, "Playback ended")
                    binding.loadingProgress?.visibility = View.GONE
                    isReady = false
                    
                    // Play next if in playlist
                    playNext()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error", error)
            binding.loadingProgress?.visibility = View.GONE
            isReady = false
            
            // Show error
            showError("Playback error: ${error.message}")
            
            // Try to recover
            recoverFromError(error)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "Is playing changed: $isPlaying")
            
            if (isPlaying) {
                // Keep screen on during playback
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                
                // Auto-hide controls
                scheduleHideControls()
            } else {
                // Allow screen to turn off when paused
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                
                // Cancel auto-hide
                cancelHideControls()
            }
            
            // Update background service if bound
            if (serviceBound) {
                mediaPlaybackService?.updatePlaybackState(isPlaying)
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            Log.d(TAG, "Timeline changed - reason: $reason")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            Log.d(TAG, "Media item transition - reason: $reason")
            
            // Update channel info
            mediaItem?.let {
                updateChannelInfo(it)
            }
        }

        override fun onTracksChanged(tracks: com.google.android.exoplayer2.Tracks) {
            Log.d(TAG, "Tracks changed")
            
            // Auto-select preferred audio and subtitle tracks
            selectPreferredTracks()
        }
    }

    // ==================== Playback Control ====================

    /**
     * Play next item in playlist
     */
    private fun playNext() {
        if (playlist.isEmpty()) {
            // No playlist, just finish
            finish()
            return
        }
        
        playlistIndex++
        if (playlistIndex >= playlist.size) {
            // End of playlist
            finish()
            return
        }
        
        // Play next item
        player?.seekToNext()
    }

    /**
     * Play previous item in playlist
     */
    private fun playPrevious() {
        if (playlist.isEmpty()) return
        
        val position = player?.currentPosition ?: 0
        
        if (position > 3000) {
            // More than 3 seconds in, restart current
            player?.seekTo(0)
        } else {
            // Go to previous
            playlistIndex--
            if (playlistIndex < 0) {
                playlistIndex = 0
            }
            player?.seekToPrevious()
        }
    }

    /**
     * Seek forward
     */
    private fun seekForward(increment: Long = DEFAULT_SEEK_INCREMENT) {
        player?.let {
            val newPosition = (it.currentPosition + increment).coerceAtMost(it.duration)
            it.seekTo(newPosition)
        }
    }

    /**
     * Seek backward
     */
    private fun seekBackward(increment: Long = DEFAULT_SEEK_INCREMENT) {
        player?.let {
            val newPosition = (it.currentPosition - increment).coerceAtLeast(0)
            it.seekTo(newPosition)
        }
    }

    /**
     * Toggle play/pause
     */
    private fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
            }
        }
    }

    // ==================== Track Selection ====================

    /**
     * Select preferred audio and subtitle tracks
     */
    private fun selectPreferredTracks() {
        val player = this.player ?: return
        val trackSelector = this.trackSelector ?: return
        
        // Auto-select based on preferences
        val preferredAudioLanguage = getPreferenceString(PREF_PREFERRED_AUDIO_LANGUAGE, "")
        val preferredSubtitleLanguage = getPreferenceString(PREF_PREFERRED_SUBTITLE_LANGUAGE, "")
        
        if (preferredAudioLanguage.isNotEmpty() || preferredSubtitleLanguage.isNotEmpty()) {
            trackSelector.setParameters(
                trackSelector.buildUponParameters()
                    .setPreferredAudioLanguage(preferredAudioLanguage)
                    .setPreferredTextLanguage(preferredSubtitleLanguage)
            )
        }
    }

    // ==================== Error Handling ====================

    /**
     * Attempt to recover from playback error
     */
    private fun recoverFromError(error: PlaybackException) {
        Log.d(TAG, "Attempting to recover from error")
        
        lifecycleScope.launch {
            delay(1000)
            
            runCatching {
                player?.let {
                    val position = playbackPosition
                    it.stop()
                    it.prepare()
                    it.seekTo(position)
                    it.play()
                }
            }.onFailure { e ->
                Log.e(TAG, "Recovery failed", e)
            }
        }
    }

    // ==================== Playback State Persistence ====================

    /**
     * Save playback state to preferences
     */
    private fun savePlaybackState() {
        // Cancel any previous pending save operation
        savePlaybackStateJob?.cancel()
        
        if (!getPreferenceBoolean(PREF_REMEMBER_POSITION, true)) {
            return
        }
        
        val identifier = getMediaIdentifier()
        
        // Launch new save job and track it
        savePlaybackStateJob = lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val position = player?.currentPosition ?: 0
                val duration = player?.duration ?: 0
                
                Log.d(TAG, "Saving playback state - identifier: $identifier, position: $position")
                
                // Save to preferences or database
                saveToPreferences(identifier, position, duration)
                
            }.onFailure { e ->
                Log.e(TAG, "Error saving playback state", e)
            }
        }
    }

    /**
     * Load playback state from preferences
     */
    private suspend fun loadPlaybackState() {
        if (!getPreferenceBoolean(PREF_REMEMBER_POSITION, true)) {
            return
        }
        
        val identifier = getMediaIdentifier()
        
        runCatching {
            val savedPosition = loadFromPreferences(identifier)
            
            if (savedPosition > 0) {
                withContext(Dispatchers.Main) {
                    player?.seekTo(savedPosition)
                }
                Log.d(TAG, "Loaded playback position: $savedPosition")
            }
            
        }.onFailure { e ->
            Log.e(TAG, "Error loading playback state", e)
        }
    }

    /**
     * Get unique identifier for current media
     */
    private fun getMediaIdentifier(): String {
        return if (channelId > 0) {
            "channel_$channelId"
        } else {
            streamUrl?.hashCode()?.toString() ?: "unknown"
        }
    }

    // ==================== Background Playback ====================

    /**
     * Start background playback service
     */
    private fun startBackgroundPlayback() {
        Log.d(TAG, "Starting background playback")
        
        val intent = Intent(this, MediaPlaybackService::class.java).apply {
            putExtra("channel_name", channelName)
            putExtra("stream_url", streamUrl)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * End background playback
     */
    private fun endBackgroundPlayback() {
        Log.d(TAG, "Ending background playback")
        
        if (serviceBound) {
            runCatching {
                unbindService(serviceConnection)
                serviceBound = false
            }
        }
        
        stopService(Intent(this, MediaPlaybackService::class.java))
        mediaPlaybackService = null
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
        
        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                Log.d(TAG, "Audio focus granted")
                true
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                Log.d(TAG, "Audio focus delayed")
                false
            }
            else -> {
                Log.d(TAG, "Audio focus denied")
                restoreAudioFocus = {}
                false
            }
        }
    }

    /**
     * Abandon audio focus
     */
    private fun abandonAudioFocus() {
        if (restoreAudioFocus != {}) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
            restoreAudioFocus = {}
        }
    }

    /**
     * Cleanup audio resources
     */
    private fun cleanupAudio() {
        abandonAudioFocus()
    }

    /**
     * Cleanup receivers
     */
    private fun cleanupReceivers() {
        if (noisyReceiverRegistered) {
            runCatching {
                unregisterReceiver(noisyReceiver)
                noisyReceiverRegistered = false
            }
        }
    }

    // ==================== UI Setup ====================

    /**
     * Setup system UI for immersive playback
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun setupSystemUI() {
        // Set cutout mode
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        
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
    @RequiresApi(Build.VERSION_CODES.P)
    private fun restoreSystemUI() {
        // Clear flags first
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Set cutout mode
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pipHelper.updatePictureInPictureParams()
        }
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
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

    // ==================== PiP Helper Setup ====================

    /**
     * Initialize PiP helper
     */
    private fun setupPipHelper() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pipHelper = PipHelper(this, playerView)
        }
    }

    // ==================== Controls Management ====================

    /**
     * Setup player controls
     */
    private fun setupPlayerControls() {
        // Setup control buttons
        binding.playPauseButton?.setOnClickListener {
            togglePlayPause()
        }
        
        binding.seekForwardButton?.setOnClickListener {
            seekForward()
        }
        
        binding.seekBackwardButton?.setOnClickListener {
            seekBackward()
        }
        
        binding.nextButton?.setOnClickListener {
            playNext()
        }
        
        binding.previousButton?.setOnClickListener {
            playPrevious()
        }
        
        // Setup gesture overlay
        binding.gestureOverlay?.setOnClickListener {
            toggleControls()
        }
    }

    /**
     * Show controls
     */
    private fun showControls() {
        binding.playerControlsLayout?.visibility = View.VISIBLE
        controlsShown = true
        scheduleHideControls()
    }

    /**
     * Hide controls
     */
    private fun hideControls() {
        binding.playerControlsLayout?.visibility = View.GONE
        controlsShown = false
        cancelHideControls()
    }

    /**
     * Toggle controls visibility
     */
    private fun toggleControls() {
        if (controlsShown) {
            hideControls()
        } else {
            showControls()
        }
    }

    /**
     * Schedule auto-hide of controls
     */
    private fun scheduleHideControls() {
        cancelHideControls()
        
        hideControlsJob = lifecycleScope.launch {
            delay(CONTROLS_TIMEOUT)
            if (player?.isPlaying == true) {
                hideControls()
            }
        }
    }

    /**
     * Cancel scheduled hide
     */
    private fun cancelHideControls() {
        hideControlsJob?.cancel()
        hideControlsJob = null
    }

    // ==================== Gesture Handling ====================

    /**
     * Setup gesture detectors for brightness and volume
     */
    private fun setupGestureDetectors() {
        // TODO: Implement gesture detectors for:
        // - Swipe up/down on left side: brightness
        // - Swipe up/down on right side: volume
        // - Double tap left: seek backward
        // - Double tap right: seek forward
        // - Pinch: zoom (if supported)
    }

    // ==================== Brightness and Volume ====================

    /**
     * Set brightness level
     */
    private fun setBrightness(brightness: Float) {
        val lp = window.attributes
        lp.screenBrightness = brightness.coerceIn(0f, 1f)
        window.attributes = lp
        currentBrightness = brightness
    }

    /**
     * Increase brightness
     */
    private fun increaseBrightness() {
        val newBrightness = (currentBrightness + BRIGHTNESS_STEP).coerceAtMost(1f)
        setBrightness(newBrightness)
    }

    /**
     * Decrease brightness
     */
    private fun decreaseBrightness() {
        val newBrightness = (currentBrightness - BRIGHTNESS_STEP).coerceAtLeast(0f)
        setBrightness(newBrightness)
    }

    /**
     * Update volume
     */
    private fun updateVolume() {
        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    /**
     * Increase volume
     */
    private fun increaseVolume() {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_RAISE,
            AudioManager.FLAG_SHOW_UI
        )
        updateVolume()
    }

    /**
     * Decrease volume
     */
    private fun decreaseVolume() {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        updateVolume()
    }

    // ==================== Back Press Handling ====================

    /**
     * Setup back press handler
     */
    @RequiresApi(Build.VERSION_CODES.P)
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
    @RequiresApi(Build.VERSION_CODES.P)
    private fun handleBackPress() {
        // Hide controls first if shown
        if (controlsShown) {
            hideControls()
            return
        }
        
        // Try to enter PiP mode if auto PiP is enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val autoPip = getPreferenceBoolean(PREF_AUTO_PIP, true)
            if (autoPip && isReady && player?.isPlaying == true) {
                pipHelper.enterPipMode()
                return
            }
        }
        
        // Finish activity
        isUserFinishing = true
        finish()
    }

    // ==================== Key Event Handling ====================

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause()
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event?.isLongPress == true) {
                    playNext()
                } else {
                    seekForward()
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event?.isLongPress == true) {
                    playPrevious()
                } else {
                    seekBackward()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                increaseVolume()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                decreaseVolume()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ==================== Intent Handling ====================

    /**
     * Extract data from intent
     */
    private fun extractIntentData(intent: Intent) {
        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME)
        channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1)
        playlistIndex = intent.getIntExtra(EXTRA_PLAYLIST_INDEX, 0)
        
        // Extract playlist
        playlist = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(EXTRA_PLAYLIST, Uri::class.java) ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(EXTRA_PLAYLIST) ?: emptyList()
        }
        
        Log.d(TAG, "Stream URL: $streamUrl")
        Log.d(TAG, "Channel: $channelName (ID: $channelId)")
        Log.d(TAG, "Playlist size: ${playlist.size}, index: $playlistIndex")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        
        Log.d(TAG, "onNewIntent")
        
        // Update intent
        setIntent(intent)
        
        // Extract new data
        extractIntentData(intent)
        
        // Prepare new media
        player?.let {
            preparePlaylist()
            it.prepare()
        }
    }

    /**
     * Update channel info display
     */
    private fun updateChannelInfo(mediaItem: MediaItem) {
        // Update UI with channel info
        binding.channelNameText?.text = channelName ?: "Live TV"
    }

    // ==================== Helper Methods ====================

    /**
     * Show error message to user
     */
    private fun showError(message: String) {
        Log.e(TAG, message)
        
        lifecycleScope.launch(Dispatchers.Main) {
            // TODO: Show Toast, Snackbar, or Dialog
            android.widget.Toast.makeText(
                this@PlayerActivity,
                message,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    // ==================== Preferences Helpers ====================

    private fun getPreferenceBoolean(key: String, defaultValue: Boolean): Boolean {
        val prefs = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(key, defaultValue)
    }

    private fun getPreferenceString(key: String, defaultValue: String): String {
        val prefs = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    private fun saveToPreferences(identifier: String, position: Long, duration: Long) {
        val prefs = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putLong("position_$identifier", position)
            putLong("duration_$identifier", duration)
            apply()
        }
    }

    private fun loadFromPreferences(identifier: String): Long {
        val prefs = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        return prefs.getLong("position_$identifier", 0)
    }
}

// ==================== PiP Helper Class ====================

/**
 * Helper class for Picture-in-Picture functionality
 */
@RequiresApi(Build.VERSION_CODES.O)
class PipHelper(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView
) {
    private val TAG = "PipHelper"
    
    fun onPictureInPictureModeChanged(isInPipMode: Boolean) {
        Log.d(TAG, "PiP mode changed: $isInPipMode")
        // Additional PiP-specific logic can go here
    }

    fun updatePictureInPictureParams() {
        if (activity.isFinishing || activity.isDestroyed) return

        runCatching {
            val params = buildPipParams()
            activity.setPictureInPictureParams(params)
        }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()

        getVideoAspectRatio()?.let { aspectRatio ->
            builder.setAspectRatio(aspectRatio)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
        }

        return builder.build()
    }

    private fun getVideoAspectRatio(): Rational? {
        val player = playerView.player ?: return null
        val videoFormat = player.videoFormat ?: return null
        
        val width = videoFormat.width
        val height = videoFormat.height

        if (width == 0 || height == 0) return null

        return Rational(width, height).takeIf { it.toFloat() in 0.5f..2.39f }
    }

    fun enterPipMode() {
        runCatching {
            activity.enterPictureInPictureMode(buildPipParams())
        }.onFailure {
            Log.e(TAG, "Failed to enter PiP mode", it)
        }
    }

    fun onStop() {
        // Cleanup if needed
    }
}

// ==================== MediaPlaybackService Placeholder ====================

/**
 * Background playback service for media continuation
 */
class MediaPlaybackService : android.app.Service() {
    
    inner class MediaPlaybackBinder : android.os.Binder() {
        fun getService() = this@MediaPlaybackService
    }
    
    private val binder = MediaPlaybackBinder()
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    fun updatePlaybackState(isPlaying: Boolean) {
        Log.d("MediaPlaybackService", "Playback state: $isPlaying")
        // Update notification, media session, etc.
    }
}
