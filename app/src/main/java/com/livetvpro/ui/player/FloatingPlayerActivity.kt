package com.livetvpro.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.util.Rational
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.databinding.ActivityPlayerBinding
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import com.livetvpro.ui.adapters.LiveEventAdapter
import com.livetvpro.ui.adapters.LinkChipAdapter
import com.livetvpro.data.models.LiveEventLink
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * FloatingPlayerActivity - Fullscreen video player launched from floating player
 * 
 * This is identical to PlayerActivity but opened from the floating player's fullscreen button.
 * When user clicks fullscreen in the floating overlay, it opens this activity.
 * 
 * KEY FEATURES:
 * - Modern WindowInsetsController for system UI management
 * - Complete layout recalculation on orientation changes
 * - Proper PiP state management and restoration
 * - Window flags properly restored when exiting PiP
 * - No activity recreation (handled via configChanges in manifest)
 */
@UnstableApi
@AndroidEntryPoint
class FloatingPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var playerListener: Player.Listener? = null
    
    @javax.inject.Inject
    lateinit var preferencesManager: com.livetvpro.data.local.PreferencesManager
    
    private lateinit var relatedChannelsAdapter: RelatedChannelAdapter
    private var relatedChannels = listOf<Channel>()
    private lateinit var relatedEventsAdapter: LiveEventAdapter
    private lateinit var linkChipAdapter: LinkChipAdapter

    // ===== CRITICAL FIX: WindowInsetsController for modern system UI management =====
    private lateinit var windowInsetsController: WindowInsetsControllerCompat

    // Custom control buttons
    private var btnBack: ImageButton? = null
    private var btnPip: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var btnLock: ImageButton? = null
    private var btnMute: ImageButton? = null
    private var btnRewind: ImageButton? = null
    private var btnPlayPause: ImageButton? = null
    private var btnForward: ImageButton? = null
    private var btnFullscreen: ImageButton? = null
    private var btnAspectRatio: ImageButton? = null
    private var tvChannelName: TextView? = null

    // State variables
    private var isInPipMode = false
    private var isLocked = false
    private var isMuted = false
    private val skipMs = 10_000L
    private var userRequestedPip = false
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideUnlockButtonRunnable = Runnable {
        binding.unlockButton.visibility = View.GONE
    }
    
    private var pipReceiver: BroadcastReceiver? = null
    private var wasLockedBeforePip = false

    // Content data
    private var contentType: ContentType = ContentType.CHANNEL
    private var channelData: Channel? = null
    private var eventData: LiveEvent? = null
    private var allEventLinks = listOf<LiveEventLink>()
    private var currentLinkIndex = 0
    private var contentId: String = ""
    private var contentName: String = ""
    private var streamUrl: String = ""
    
    // FIXED: Store playback position for seamless transitions
    private var savedPlaybackPosition: Long = -1L

    enum class ContentType {
        CHANNEL, EVENT, NETWORK_STREAM
    }

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"
        private const val EXTRA_EVENT = "extra_event"
        private const val EXTRA_SELECTED_LINK_INDEX = "extra_selected_link_index"
        private const val ACTION_MEDIA_CONTROL = "com.livetvpro.MEDIA_CONTROL"
        private const val EXTRA_CONTROL_TYPE = "control_type"
        private const val CONTROL_TYPE_PLAY = 1
        private const val CONTROL_TYPE_PAUSE = 2
        private const val CONTROL_TYPE_REWIND = 3
        private const val CONTROL_TYPE_FORWARD = 4

        fun startWithChannel(context: Context, channel: Channel, linkIndex: Int = -1) {
            val intent = Intent(context, FloatingPlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel as Parcelable)
                putExtra(EXTRA_SELECTED_LINK_INDEX, linkIndex)
                // Disable enter animation to prevent black screen
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
            // Override transition to instant (no animation)
            if (context is android.app.Activity) {
                context.overridePendingTransition(0, 0)
            }
        }

        fun startWithEvent(context: Context, event: LiveEvent, linkIndex: Int = -1) {
            val intent = Intent(context, FloatingPlayerActivity::class.java).apply {
                putExtra(EXTRA_EVENT, event as Parcelable)
                putExtra(EXTRA_SELECTED_LINK_INDEX, linkIndex)
                // Disable enter animation to prevent black screen
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
            // Override transition to instant (no animation)
            if (context is android.app.Activity) {
                context.overridePendingTransition(0, 0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // CRITICAL FIX: Delay showing the activity until the UI is ready
        // This prevents the black screen issue
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            postponeEnterTransition()
        }
        
        // Initialize binding - this persists across orientation changes
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // ===== CRITICAL FIX: Initialize WindowInsetsController =====
        windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Setup window flags and UI based on initial orientation
        val currentOrientation = resources.configuration.orientation
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        
        setupWindowFlags(isLandscape)
        setupSystemUI(isLandscape)
        setupWindowInsets()
        
        // CRITICAL: Force 16:9 layout immediately in portrait mode
        // This prevents the spinner from appearing in center of full screen
        if (!isLandscape) {
            exitFullscreen()
        }
        
        parseIntent()

        if (contentType == ContentType.CHANNEL && contentId.isNotEmpty()) {
            viewModel.refreshChannelData(contentId)
        }

        // Setup UI components
        bindControllerViews()
        setupRelatedChannels()
        setupLinksUI()
        configurePlayerInteractions()
        setupLockOverlay()
        
        // Apply orientation
        applyOrientationSettings(isLandscape)
        
        // Enable controller
        binding.playerView.useController = true
        binding.playerView.controllerAutoShow = true  // Show controls on start
        
        // Show initial loading state for related channels
        if (!isLandscape) {
            binding.relatedLoadingProgress.visibility = View.VISIBLE
            binding.relatedChannelsRecycler.visibility = View.GONE
        }
        
        // Show loading spinner
        binding.progressBar.visibility = View.VISIBLE
        
        // CRITICAL: Start transition after UI is laid out
        binding.root.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    binding.root.viewTreeObserver.removeOnPreDrawListener(this)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        startPostponedEnterTransition()
                    }
                    return true
                }
            }
        )
        
        // Start player and load content
        setupPlayer()
        loadRelatedContent()
        
        // Observe refreshed channel data
        viewModel.refreshedChannel.observe(this) { freshChannel ->
            if (freshChannel != null && freshChannel.links != null && freshChannel.links.isNotEmpty()) {
                if (allEventLinks.isEmpty() || allEventLinks.size < freshChannel.links.size) {
                    allEventLinks = freshChannel.links.map { 
                        LiveEventLink(
                            quality = it.quality,
                            url = it.url,
                            cookie = it.cookie,
                            referer = it.referer,
                            origin = it.origin,
                            userAgent = it.userAgent,
                            drmScheme = it.drmScheme,
                            drmLicenseUrl = it.drmLicenseUrl
                        ) 
                    }
                    
                    val matchIndex = allEventLinks.indexOfFirst { it.url == streamUrl }
                    if (matchIndex != -1) {
                        currentLinkIndex = matchIndex
                    }
                    
                    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    updateLinksForOrientation(isLandscape)
                }
            }
            
            // Load related content with fresh channel data that includes categoryId
            if (freshChannel != null && contentType == ContentType.CHANNEL) {
                channelData = freshChannel
                if (freshChannel.categoryId.isNotEmpty()) {
                    viewModel.loadRelatedChannels(freshChannel.categoryId, freshChannel.id)
                }
            }
        }
        
        viewModel.relatedItems.observe(this) { channels ->
            relatedChannels = channels
            relatedChannelsAdapter.submitList(channels)
            binding.relatedChannelsSection.visibility = if (channels.isEmpty()) {
                View.GONE
            } else {
                View.VISIBLE
            }
            binding.relatedLoadingProgress.visibility = View.GONE
            binding.relatedChannelsRecycler.visibility = View.VISIBLE
        }
        
        viewModel.relatedLiveEvents.observe(this) { liveEvents ->
            if (contentType == ContentType.EVENT && ::relatedEventsAdapter.isInitialized) {
                relatedEventsAdapter.updateData(liveEvents)
                binding.relatedChannelsSection.visibility = if (liveEvents.isEmpty()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
                binding.relatedLoadingProgress.visibility = View.GONE
                binding.relatedChannelsRecycler.visibility = View.VISIBLE
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerPipReceiver()
        }
    }
    
    /**
     * ===== CRITICAL FIX: Setup window flags for immersive mode =====
     * Called on create, orientation change, and when returning from PiP
     */
    private fun setupWindowFlags(isLandscape: Boolean) {
        if (isLandscape) {
            // Landscape: Full immersive mode with display cutout support
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = 
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            
            // Set layout flags for immersive mode
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        } else {
            // Portrait: Allow system windows
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    }

    /**
     * ===== CRITICAL FIX: Setup system UI visibility using modern API =====
     */
    private fun setupSystemUI(isLandscape: Boolean) {
        if (isLandscape) {
            // Hide all system bars in landscape
            windowInsetsController.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = 
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Show system bars in portrait
            windowInsetsController.apply {
                show(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
        }
    }
    
    private fun setupWindowInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            binding.root.setOnApplyWindowInsetsListener { view, insets ->
                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                
                if (isLandscape) {
                    binding.playerContainer.setPadding(0, 0, 0, 0)
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
                        val displayCutout = insets.displayCutout
                        
                        val topPadding = maxOf(systemBars.top, displayCutout?.safeInsetTop ?: 0)
                        val leftPadding = maxOf(systemBars.left, displayCutout?.safeInsetLeft ?: 0)
                        val rightPadding = maxOf(systemBars.right, displayCutout?.safeInsetRight ?: 0)
                        
                        binding.playerContainer.setPadding(leftPadding, topPadding, rightPadding, 0)
                    } else {
                        @Suppress("DEPRECATION")
                        binding.playerContainer.setPadding(
                            insets.systemWindowInsetLeft,
                            insets.systemWindowInsetTop,
                            insets.systemWindowInsetRight,
                            0
                        )
                    }
                }
                
                insets
            }
        }
    }

    /**
     * ===== CRITICAL FIX: Handle configuration changes =====
     * This is called instead of recreating the activity (thanks to configChanges in manifest)
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    
    // If in PiP mode, just hide controller and return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
        binding.playerView.hideController()
        return
    }
    
    val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
    
    // FIXED: Remember if controls were visible before orientation change
    val wasControllerVisible = binding.playerView.isControllerFullyVisible
    val isBuffering = player?.playbackState == Player.STATE_BUFFERING
    
    // Apply all orientation-related changes in the correct sequence
    setupWindowFlags(isLandscape)
    setupSystemUI(isLandscape)
    applyOrientationSettings(isLandscape)
    setSubtitleTextSize()
    
    // FIXED: Restore controller visibility if it was visible and we're not buffering
    // This prevents the controls from disappearing during orientation change
    if (wasControllerVisible && !isBuffering) {
        binding.playerView.postDelayed({
            if (player?.playbackState != Player.STATE_BUFFERING) {
                binding.playerView.showController()
            }
        }, 100)
    } else if (isBuffering) {
        // Keep controls hidden during buffering
        binding.playerView.hideController()
    }

    // Force layout refresh
    binding.root.post {
        binding.root.requestLayout()
        binding.playerContainer.requestLayout()
        binding.playerView.requestLayout()
    }
}


    /**
     * Apply all orientation-specific settings
     */
    private fun applyOrientationSettings(isLandscape: Boolean) {
        adjustLayoutForOrientation(isLandscape)
        updateLinksForOrientation(isLandscape)
        
        btnFullscreen?.setImageResource(
            if (isLandscape) R.drawable.ic_fullscreen_exit 
            else R.drawable.ic_fullscreen
        )
    }

    /**
     * ===== CRITICAL FIX: Completely recalculate layout for orientation =====
     * This is the key method that fixes the layout issues
     */
    private fun adjustLayoutForOrientation(isLandscape: Boolean) {
        if (isLandscape) {
            // Use enterFullscreen helper for consistency
            enterFullscreen()
            
            val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
            // Additional landscape-specific constraints
            params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.topMargin = 0
            params.bottomMargin = 0
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            
            binding.playerContainer.setPadding(0, 0, 0, 0)
            binding.playerContainer.layoutParams = params
            
            // Player view settings
            binding.playerView.controllerAutoShow = true  // Show controls on start
            binding.playerView.controllerShowTimeoutMs = 3000
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            
        } else {
            // Use exitFullscreen helper for consistency
            exitFullscreen()
            
            // Player view settings
            binding.playerView.controllerAutoShow = true  // Show controls on start
            binding.playerView.controllerShowTimeoutMs = 5000
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            
            // Setup constraints for links section
            val linksParams = binding.linksSection.layoutParams as ConstraintLayout.LayoutParams
            linksParams.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            linksParams.height = ConstraintLayout.LayoutParams.WRAP_CONTENT
            linksParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            linksParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            linksParams.topToBottom = binding.playerContainer.id
            linksParams.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            binding.linksSection.layoutParams = linksParams
            
            // Setup constraints for related channels section
            val relatedParams = binding.relatedChannelsSection.layoutParams as ConstraintLayout.LayoutParams
            relatedParams.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            relatedParams.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            relatedParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            relatedParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            relatedParams.topToBottom = binding.linksSection.id
            relatedParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            binding.relatedChannelsSection.layoutParams = relatedParams
            
            // Sections are visible by default in XML
            // They will be hidden by ViewModel observers if no data is available
        }
        
        // CRITICAL: Request layout update
        binding.root.requestLayout()
    }

    override fun onResume() {
        super.onResume()
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyOrientationSettings(isLandscape)
        
        if (player == null) {
            setupPlayer()
            binding.playerView.onResume()
        }
    }

    /**
     * ===== CRITICAL FIX: Handle PiP mode changes =====
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        isInPipMode = isInPictureInPictureMode
        
        if (isInPipMode) {
            // Entering PiP mode
            enterPipUIMode()
        } else {
            // Exiting PiP mode
            exitPipUIMode(newConfig)
        }
    }

    /**
     * Configure UI for entering PiP mode
     */
    private fun enterPipUIMode() {
        binding.playerView.useController = false 
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE
        binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        binding.playerView.hideController()
        
        // Show system UI in PiP
        WindowCompat.setDecorFitsSystemWindows(window, true)
        windowInsetsController.apply {
            show(WindowInsetsCompat.Type.systemBars())
            show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    /**
     * ===== CRITICAL FIX: Restore UI when exiting PiP mode =====
     * This ensures all window flags and system UI are properly restored
     */
    private fun exitPipUIMode(newConfig: Configuration) {
        userRequestedPip = false
        
        if (isFinishing) {
            return
        }
        
        setSubtitleTextSize()
        
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        // CRITICAL: Restore window flags and system UI
        setupWindowFlags(isLandscape)
        setupSystemUI(isLandscape)
        
        // Restore orientation settings
        applyOrientationSettings(isLandscape)
        
        // Show portrait sections if not in landscape
        if (!isLandscape) {
            if (allEventLinks.size > 1) {
                binding.linksSection.visibility = View.VISIBLE
            }
            if (relatedChannels.isNotEmpty()) {
                binding.relatedChannelsSection.visibility = View.VISIBLE
            }
        }
        
        // Restore lock state
        if (wasLockedBeforePip) {
            isLocked = true
            binding.playerView.useController = false
            binding.lockOverlay.visibility = View.VISIBLE
            showUnlockButton()
            wasLockedBeforePip = false
        } else {
            isLocked = false
            binding.playerView.useController = true
            
            binding.playerView.postDelayed({
                if (!isInPipMode && !isLocked) {
                    binding.playerView.showController()
                }
            }, 150)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // FIXED: Disable auto PIP for FloatingPlayerActivity
        // User should explicitly click PIP button to return to floating window
        // Auto PIP is disabled here
        android.util.Log.d("FloatingPlayerActivity", "onUserLeaveHint - Auto PIP disabled for this activity")
    }

    // ===== Rest of your existing methods (unchanged) =====
    
    private fun parseIntent() {
        // Check if this is a network stream request
        val isNetworkStream = intent.getBooleanExtra("IS_NETWORK_STREAM", false)
        
        if (isNetworkStream) {
            // Handle network stream
            contentType = ContentType.NETWORK_STREAM
            contentName = intent.getStringExtra("CHANNEL_NAME") ?: "Network Stream"
            contentId = "network_stream_${System.currentTimeMillis()}"
            
            // Get network stream parameters
            val streamUrlRaw = intent.getStringExtra("STREAM_URL") ?: ""
            val cookie = intent.getStringExtra("COOKIE") ?: ""
            val referer = intent.getStringExtra("REFERER") ?: ""
            val origin = intent.getStringExtra("ORIGIN") ?: ""
            val drmLicense = intent.getStringExtra("DRM_LICENSE") ?: ""
            val userAgent = intent.getStringExtra("USER_AGENT") ?: "Default"
            val drmScheme = intent.getStringExtra("DRM_SCHEME") ?: "clearkey"
            
            // Create a single link with network stream data
            allEventLinks = listOf(
                LiveEventLink(
                    quality = "Network Stream",
                    url = streamUrlRaw,
                    cookie = cookie,
                    referer = referer,
                    origin = origin,
                    userAgent = userAgent,
                    drmScheme = drmScheme,
                    drmLicenseUrl = drmLicense
                )
            )
            
            currentLinkIndex = 0
            streamUrl = buildStreamUrl(allEventLinks[0])
            
            // Extract playback position if coming from FloatingPlayerService
            val savedPosition = intent.getLongExtra("playback_position", -1L)
            if (savedPosition > 0) {
                this.savedPlaybackPosition = savedPosition
                android.util.Log.d("FloatingPlayerActivity", "Received saved position: $savedPosition")
            }
            return
        }
        
        // Original channel/event parsing logic
        channelData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CHANNEL)
        }

        eventData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_EVENT, LiveEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_EVENT)
        }

        val passedLinkIndex = intent.getIntExtra(EXTRA_SELECTED_LINK_INDEX, -1)

        // CRITICAL FIX: Check eventData FIRST, because when an event is played through
        // FloatingPlayerService, BOTH channelData and eventData are present
        // (channel is the converted version for stream data, event is the original for context)
        if (eventData != null) {
            contentType = ContentType.EVENT
            val event = eventData!!
            contentId = event.id
            contentName = event.title.ifEmpty { "${event.team1Name} vs ${event.team2Name}" }
            
            allEventLinks = event.links
            
            if (allEventLinks.isNotEmpty()) {
                currentLinkIndex = if (passedLinkIndex in allEventLinks.indices) passedLinkIndex else 0
                streamUrl = buildStreamUrl(allEventLinks[currentLinkIndex])
            } else {
                currentLinkIndex = 0
                streamUrl = ""
            }
            
        } else if (channelData != null) {
            contentType = ContentType.CHANNEL
            val channel = channelData!!
            contentId = channel.id
            contentName = channel.name

            if (channel.links != null && channel.links.isNotEmpty()) {
                allEventLinks = channel.links.map { 
                    LiveEventLink(
                        quality = it.quality,
                        url = it.url,
                        cookie = it.cookie,
                        referer = it.referer,
                        origin = it.origin,
                        userAgent = it.userAgent,
                        drmScheme = it.drmScheme,
                        drmLicenseUrl = it.drmLicenseUrl
                    ) 
                }
                
                if (passedLinkIndex in allEventLinks.indices) {
                    currentLinkIndex = passedLinkIndex
                } else {
                    val matchIndex = allEventLinks.indexOfFirst { it.url == channel.streamUrl }
                    currentLinkIndex = if (matchIndex != -1) matchIndex else 0
                }
                
                streamUrl = buildStreamUrl(allEventLinks[currentLinkIndex])
            } else {
                streamUrl = channel.streamUrl
                allEventLinks = emptyList()
            }

        } else {
            finish()
            return
        }
        
        // FIXED: Extract playback position if coming from FloatingPlayerService
        val savedPosition = intent.getLongExtra("playback_position", -1L)
        if (savedPosition > 0) {
            this.savedPlaybackPosition = savedPosition
            android.util.Log.d("FloatingPlayerActivity", "Received saved position: $savedPosition")
        }
    }

    private fun setupRelatedChannels() {
        if (contentType == ContentType.NETWORK_STREAM) {
            // For network streams, hide the related channels section
            binding.relatedChannelsSection.visibility = View.GONE
            return
        }
        
        if (contentType == ContentType.EVENT) {
            relatedEventsAdapter = LiveEventAdapter(
                context = this,
                events = emptyList(),
                preferencesManager = preferencesManager,
                onEventClick = { event, linkIndex ->
                    // Switch to the selected event in the same activity
                    switchToEventFromLiveEvent(event)
                }
            )
            
            binding.relatedChannelsRecycler.layoutManager = LinearLayoutManager(this)
            binding.relatedChannelsRecycler.adapter = relatedEventsAdapter
        } else {
            relatedChannelsAdapter = RelatedChannelAdapter { relatedItem ->
                switchToChannel(relatedItem)
            }
            
            binding.relatedChannelsRecycler.layoutManager = GridLayoutManager(this, 3)
            binding.relatedChannelsRecycler.adapter = relatedChannelsAdapter
        }
    }
    
    private fun setupLinksUI() {
        linkChipAdapter = LinkChipAdapter { link, position ->
            switchToLink(link, position)
        }

        val portraitLinksRecycler = binding.linksRecyclerView
        portraitLinksRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        portraitLinksRecycler.adapter = linkChipAdapter

        val landscapeLinksRecycler = binding.playerView.findViewById<RecyclerView>(R.id.exo_links_recycler)
        landscapeLinksRecycler?.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        val landscapeLinkAdapter = LinkChipAdapter { link, position ->
            switchToLink(link, position)
        }
        landscapeLinksRecycler?.adapter = landscapeLinkAdapter

        if (allEventLinks.size > 1) {
            val currentOrientation = resources.configuration.orientation
            val isCurrentlyLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE

            if (isCurrentlyLandscape) {
                binding.linksSection.visibility = View.GONE
                landscapeLinksRecycler?.visibility = View.VISIBLE
                landscapeLinkAdapter.submitList(allEventLinks)
                landscapeLinkAdapter.setSelectedPosition(currentLinkIndex)
            } else {
                binding.linksSection.visibility = View.VISIBLE
                landscapeLinksRecycler?.visibility = View.GONE
                linkChipAdapter.submitList(allEventLinks)
                linkChipAdapter.setSelectedPosition(currentLinkIndex)
            }
        } else {
            binding.linksSection.visibility = View.GONE
            landscapeLinksRecycler?.visibility = View.GONE
        }
    }

    private fun updateLinksForOrientation(isLandscape: Boolean) {
        val landscapeLinksRecycler = binding.playerView.findViewById<RecyclerView>(R.id.exo_links_recycler)
        
        if (allEventLinks.size > 1) {
            if (isLandscape) {
                binding.linksSection.visibility = View.GONE
                landscapeLinksRecycler?.visibility = View.VISIBLE
                
                val landscapeAdapter = landscapeLinksRecycler?.adapter as? LinkChipAdapter
                if (landscapeAdapter != null) {
                    landscapeAdapter.submitList(allEventLinks)
                    landscapeAdapter.setSelectedPosition(currentLinkIndex)
                }
            } else {
                binding.linksSection.visibility = View.VISIBLE
                landscapeLinksRecycler?.visibility = View.GONE
                
                linkChipAdapter.submitList(allEventLinks)
                linkChipAdapter.setSelectedPosition(currentLinkIndex)
            }
        } else {
            binding.linksSection.visibility = View.GONE
            landscapeLinksRecycler?.visibility = View.GONE
        }
    }

    private fun loadRelatedContent() {
        when (contentType) {
            ContentType.CHANNEL -> {
                channelData?.let { channel ->
                    viewModel.loadRelatedChannels(channel.categoryId, channel.id)
                }
            }
            ContentType.EVENT -> {
                eventData?.let { event ->
                    viewModel.loadRelatedEvents(event.id)
                }
            }
            ContentType.NETWORK_STREAM -> {
                // No related content for network streams
            }
        }
    }

    private fun switchToChannel(newChannel: Channel) {
        releasePlayer()
        channelData = newChannel
        eventData = null
        contentType = ContentType.CHANNEL
        contentId = newChannel.id
        contentName = newChannel.name
        
        if (newChannel.links != null && newChannel.links.isNotEmpty()) {
            allEventLinks = newChannel.links.map { 
                LiveEventLink(
                    quality = it.quality,
                    url = it.url,
                    cookie = it.cookie,
                    referer = it.referer,
                    origin = it.origin,
                    userAgent = it.userAgent,
                    drmScheme = it.drmScheme,
                    drmLicenseUrl = it.drmLicenseUrl
                ) 
            }
            currentLinkIndex = 0
            streamUrl = allEventLinks.firstOrNull()?.let { buildStreamUrl(it) } ?: newChannel.streamUrl
        } else {
            allEventLinks = emptyList()
            streamUrl = newChannel.streamUrl
        }
        
        tvChannelName?.text = contentName
        
        setupPlayer()
        setupLinksUI()
        
        binding.relatedLoadingProgress.visibility = View.VISIBLE
        binding.relatedChannelsRecycler.visibility = View.GONE
        viewModel.loadRelatedChannels(newChannel.categoryId, newChannel.id)
    }

    private fun switchToEvent(relatedChannel: Channel) {
        switchToChannel(relatedChannel)
    }
    
    private fun switchToEventFromLiveEvent(newEvent: LiveEvent) {
        try {
            if (::relatedEventsAdapter.isInitialized) {
                relatedEventsAdapter.stopCountdown()
            }
            
            releasePlayer()
            
            eventData = newEvent
            channelData = null
            contentType = ContentType.EVENT
            contentId = newEvent.id
            contentName = newEvent.title.ifEmpty { "${newEvent.team1Name} vs ${newEvent.team2Name}" }
            
            allEventLinks = newEvent.links
            
            if (allEventLinks.isNotEmpty()) {
                currentLinkIndex = 0
                streamUrl = allEventLinks.firstOrNull()?.let { buildStreamUrl(it) } ?: ""
            } else {
                currentLinkIndex = 0
                streamUrl = ""
            }
            
            tvChannelName?.text = contentName
            
            setupPlayer()
            setupLinksUI()
            
            binding.relatedLoadingProgress.visibility = View.VISIBLE
            binding.relatedChannelsRecycler.visibility = View.GONE
            
            viewModel.loadRelatedEvents(newEvent.id)
            
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivity", "Error switching to event", e)
        }
    }

    private fun switchToLink(link: LiveEventLink, position: Int) {
        currentLinkIndex = position
        streamUrl = buildStreamUrl(link)
        
        linkChipAdapter.setSelectedPosition(position)
        
        val landscapeLinksRecycler = binding.playerView.findViewById<RecyclerView>(R.id.exo_links_recycler)
        val landscapeAdapter = landscapeLinksRecycler?.adapter as? LinkChipAdapter
        landscapeAdapter?.setSelectedPosition(position)
        
        releasePlayer()
        setupPlayer()
    }

    override fun onPause() {
        super.onPause()
        val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            isInPictureInPictureMode
        } else {
            false
        }
        
        if (!isPip) {
            binding.playerView.onPause()
            player?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Safety net: ensure windows are restored even if finish() wasn't the exit path
        FloatingPlayerService.showAll(this)
        mainHandler.removeCallbacksAndMessages(null)
        unregisterPipReceiver()
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.let {
            try {
                playerListener?.let { listener -> it.removeListener(listener) }
                it.stop()
                it.release()
            } catch (t: Throwable) {
            }
        }
        player = null
        playerListener = null
    }

// ====================================================================================
// UNIVERSAL STREAM URL PARSER - Auto-detects and supports ANY format
// ====================================================================================
// Automatically handles:
// ✅ ANY separator: |, ?|, ::, $$, ##, @@, ;; and more
// ✅ ANY encoding: URL-encoded, base64, JSON
// ✅ ANY parameter format: key=value, key:value
// ✅ ANY header name: case-insensitive with variations
// ✅ Embedded metadata in query params
// ✅ Multiple formats combined
// ====================================================================================

private data class StreamInfo(
    val url: String,
    val headers: Map<String, String>,
    val drmScheme: String?,
    val drmKeyId: String?,
    val drmKey: String?,
    val drmLicenseUrl: String? = null
)

private fun parseStreamUrl(streamUrl: String): StreamInfo {
    if (streamUrl.isBlank()) {
        return StreamInfo("", mapOf(), null, null, null, null)
    }
    
    var workingUrl = streamUrl.trim()
    val headers = mutableMapOf<String, String>()
    var drmScheme: String? = null
    var drmKeyId: String? = null
    var drmKey: String? = null
    var drmLicenseUrl: String? = null
    var cleanUrl = workingUrl
    
    // ========================================================================
    // PHASE 1: Extract base64-encoded metadata from query parameters
    // ========================================================================
    
    val base64Patterns = listOf(
        """[?&](token|auth|key|data|meta|params|h|headers|b64)=([A-Za-z0-9+/=_-]{30,})""",
        """[?&]([a-z]{1,3})=([A-Za-z0-9+/=_-]{50,})"""
    )
    
    for (patternStr in base64Patterns) {
        val pattern = Regex(patternStr)
        val match = pattern.find(cleanUrl)
        if (match != null) {
            try {
                val encoded = match.groupValues[2]
                val decoded = try {
                    String(android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
                } catch (e: Exception) {
                    try {
                        String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
                    } catch (e2: Exception) {
                        continue
                    }
                }
                
                // Try parsing as JSON
                if ((decoded.startsWith("{") && decoded.endsWith("}")) || 
                    (decoded.startsWith("[") && decoded.endsWith("]"))) {
                    try {
                        val json = org.json.JSONObject(decoded)
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val value = json.optString(key, "")
                            if (value.isNotEmpty()) {
                                processAndApplyKeyValue(key, value, headers, 
                                    { s -> drmScheme = s }, 
                                    { k -> drmKeyId = k }, 
                                    { k -> drmKey = k },
                                    { l -> drmLicenseUrl = l })
                            }
                        }
                        cleanUrl = cleanUrl.replace(match.value, "")
                        continue
                    } catch (e: Exception) {}
                }
                
                // Try parsing as key=value pairs
                if (decoded.contains("=")) {
                    val pairs = extractKeyValuePairs(decoded)
                    for ((k, v) in pairs) {
                        processAndApplyKeyValue(k, v, headers,
                            { s -> drmScheme = s },
                            { k -> drmKeyId = k },
                            { k -> drmKey = k },
                            { l -> drmLicenseUrl = l })
                    }
                    cleanUrl = cleanUrl.replace(match.value, "")
                }
            } catch (e: Exception) {}
        }
    }
    
    // ========================================================================
    // PHASE 2: Extract URL-encoded JSON from query parameters
    // ========================================================================
    
    val jsonPattern = Regex("""[?&](headers?|hdr|h|meta|params)=(%7B[^&]+|\{[^&]+)""")
    val jsonMatch = jsonPattern.find(cleanUrl)
    if (jsonMatch != null) {
        try {
            val encodedJson = jsonMatch.groupValues[2]
            val decodedJson = if (encodedJson.startsWith("%")) {
                java.net.URLDecoder.decode(encodedJson, "UTF-8")
            } else {
                encodedJson
            }
            val json = org.json.JSONObject(decodedJson)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key, "")
                if (value.isNotEmpty()) {
                    processAndApplyKeyValue(key, value, headers,
                        { s -> drmScheme = s },
                        { k -> drmKeyId = k },
                        { k -> drmKey = k },
                        { l -> drmLicenseUrl = l })
                }
            }
            cleanUrl = cleanUrl.replace(jsonMatch.value, "")
        } catch (e: Exception) {}
    }
    
    // ========================================================================
    // PHASE 3: Extract numbered parameters (c1, c2, etc.)
    // ========================================================================
    
    val multiPattern = Regex("""[?&](c|cookie|h|header)\d+=([^&]+)""")
    var multiMatch = multiPattern.find(cleanUrl)
    val cookieParts = mutableListOf<String>()
    while (multiMatch != null) {
        try {
            val value = java.net.URLDecoder.decode(multiMatch.groupValues[2], "UTF-8")
            cookieParts.add(value)
            cleanUrl = cleanUrl.replace(multiMatch.value, "")
        } catch (e: Exception) {}
        multiMatch = multiPattern.find(cleanUrl)
    }
    if (cookieParts.isNotEmpty()) {
        headers["Cookie"] = cookieParts.joinToString("; ")
    }
    
    // ========================================================================
    // PHASE 4: Detect main separator and extract metadata
    // ========================================================================
    
    val separators = listOf(
        "|||", "||", "|",     // Pipe variants
        "?||", "?|",          // Your format
        ":::", "::",          // Colons
        "$$", "##", "@@", "%%", // Symbols
        ";;", ";;"            // Semicolons
    )
    
    var foundSeparator: String? = null
    var separatorIndex = -1
    
    for (sep in separators) {
        val idx = cleanUrl.indexOf(sep)
        if (idx > 0) {
            foundSeparator = sep
            separatorIndex = idx
            break
        }
    }
    
    if (foundSeparator != null && separatorIndex > 0) {
        cleanUrl = workingUrl.substring(0, separatorIndex).trim()
        val paramsString = workingUrl.substring(separatorIndex + foundSeparator.length).trim()
        
        // Remove trailing ? from base URL
        if (cleanUrl.endsWith("?")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)
        }
        
        // Parse parameters
        val pairs = extractKeyValuePairs(paramsString)
        for ((k, v) in pairs) {
            processAndApplyKeyValue(k, v, headers,
                { s -> drmScheme = s },
                { k -> drmKeyId = k },
                { k -> drmKey = k },
                { l -> drmLicenseUrl = l })
        }
    }
    
    // ========================================================================
    // PHASE 5: Extract from hash fragments
    // ========================================================================
    
    val hashIndex = cleanUrl.indexOf('#')
    if (hashIndex > 0) {
        val hashPart = cleanUrl.substring(hashIndex + 1)
        if (hashPart.contains("=") || hashPart.contains(":")) {
            val pairs = extractKeyValuePairs(hashPart)
            for ((k, v) in pairs) {
                processAndApplyKeyValue(k, v, headers,
                    { s -> drmScheme = s },
                    { k -> drmKeyId = k },
                    { k -> drmKey = k },
                    { l -> drmLicenseUrl = l })
            }
            cleanUrl = cleanUrl.substring(0, hashIndex)
        }
    }
    
    // ========================================================================
    // PHASE 6: Extract headers from query parameters
    // ========================================================================
    
    val queryPattern = Regex("""[?&]([^=&]+)=([^&]+)""")
    val queryMatches = queryPattern.findAll(cleanUrl).toList()
    
    for (match in queryMatches) {
        val key = match.groupValues[1].trim()
        val value = match.groupValues[2].trim()
        
        val normalizedKey = normalizeKey(key)
        if (isKnownHeaderKey(normalizedKey)) {
            processAndApplyKeyValue(key, value, headers,
                { s -> drmScheme = s },
                { k -> drmKeyId = k },
                { k -> drmKey = k },
                { l -> drmLicenseUrl = l })
            cleanUrl = cleanUrl.replace(match.value, "")
        }
    }
    
    // Clean up URL
    cleanUrl = cleanUrl.replace(Regex("""[?&]+$"""), "")
    
    // Normalize DRM scheme
    if (drmScheme != null) {
        drmScheme = normalizeDrmScheme(drmScheme!!)
    }
    
    return StreamInfo(cleanUrl, headers, drmScheme, drmKeyId, drmKey, drmLicenseUrl)
}

// ========================================================================
// Helper Functions
// ========================================================================

private fun extractKeyValuePairs(text: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    
    // Try different delimiters
    val possibleDelimiters = listOf(
        Regex("""[&|;]"""),   // Common delimiters
        Regex("""[\r\n]+"""), // Line breaks
        Regex("""\s{2,}""")   // Multiple spaces
    )
    
    for (delimiter in possibleDelimiters) {
        val parts = text.split(delimiter)
        var foundPairs = false
        
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            
            // Try = or : as assignment operator
            var eqIndex = trimmed.indexOf('=')
            if (eqIndex == -1) {
                eqIndex = trimmed.indexOf(':')
            }
            
            if (eqIndex > 0) {
                val key = trimmed.substring(0, eqIndex).trim()
                val value = trimmed.substring(eqIndex + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    result[key] = value
                    foundPairs = true
                }
            }
        }
        
        if (foundPairs) break
    }
    
    return result
}

private fun processAndApplyKeyValue(
    key: String,
    value: String,
    headers: MutableMap<String, String>,
    drmSchemeSetter: (String) -> Unit,
    drmKeyIdSetter: (String) -> Unit,
    drmKeySetter: (String) -> Unit,
    drmLicenseUrlSetter: (String) -> Unit
) {
    // Try to URL decode the value
    var decodedValue = try {
        java.net.URLDecoder.decode(value, "UTF-8")
    } catch (e: Exception) {
        value
    }
    
    val normalizedKey = normalizeKey(key)
    
    when {
        // DRM Scheme
        normalizedKey.contains("drm") || normalizedKey == "scheme" -> {
            drmSchemeSetter(decodedValue)
        }
        
        // DRM License
        normalizedKey.contains("license") || normalizedKey == "lic" -> {
            if (decodedValue.startsWith("http://", ignoreCase = true) ||
                decodedValue.startsWith("https://", ignoreCase = true)) {
                drmLicenseUrlSetter(decodedValue)
            } else if (decodedValue.contains(":")) {
                val colonIndex = decodedValue.indexOf(':')
                drmKeyIdSetter(decodedValue.substring(0, colonIndex).trim())
                drmKeySetter(decodedValue.substring(colonIndex + 1).trim())
            }
        }
        
        // DRM Key ID
        normalizedKey.contains("keyid") || normalizedKey == "kid" -> {
            drmKeyIdSetter(decodedValue)
        }
        
        // DRM Key
        normalizedKey == "key" || normalizedKey == "k" -> {
            drmKeySetter(decodedValue)
        }
        
        // User-Agent
        normalizedKey.contains("agent") || normalizedKey == "ua" -> {
            headers["User-Agent"] = decodedValue
        }
        
        // Referer
        normalizedKey.contains("refer") || normalizedKey == "ref" -> {
            headers["Referer"] = decodedValue
        }
        
        // Origin
        normalizedKey.contains("origin") || normalizedKey == "org" -> {
            headers["Origin"] = decodedValue
        }
        
        // Cookie
        normalizedKey.contains("cookie") -> {
            headers["Cookie"] = decodedValue
        }
        
        // Authorization
        normalizedKey.contains("auth") || normalizedKey.contains("bearer") || normalizedKey.contains("token") -> {
            headers["Authorization"] = if (decodedValue.startsWith("Bearer ", ignoreCase = true)) {
                decodedValue
            } else {
                "Bearer $decodedValue"
            }
        }
        
        // Content-Type
        normalizedKey.contains("content") && normalizedKey.contains("type") -> {
            headers["Content-Type"] = decodedValue
        }
        
        // API Key
        normalizedKey.contains("apikey") -> {
            headers["X-API-Key"] = decodedValue
        }
        
        // Other common headers
        normalizedKey == "accept" || normalizedKey == "acc" -> {
            headers["Accept"] = decodedValue
        }
        normalizedKey == "range" -> {
            headers["Range"] = decodedValue
        }
        normalizedKey == "host" -> {
            headers["Host"] = decodedValue
        }
        normalizedKey.contains("forward") || normalizedKey == "xff" -> {
            headers["X-Forwarded-For"] = decodedValue
        }
        normalizedKey.contains("cache") && normalizedKey.contains("control") -> {
            headers["Cache-Control"] = decodedValue
        }
        normalizedKey == "pragma" -> {
            headers["Pragma"] = decodedValue
        }
        normalizedKey == "connection" || normalizedKey == "conn" -> {
            headers["Connection"] = decodedValue
        }
        
        // Any x-* or sec-* header
        key.startsWith("x-", ignoreCase = true) || key.startsWith("sec-", ignoreCase = true) -> {
            headers[key] = decodedValue
        }
    }
}

private fun normalizeKey(key: String): String {
    return key.lowercase()
        .replace("-", "")
        .replace("_", "")
        .replace(" ", "")
}

private fun isKnownHeaderKey(normalizedKey: String): Boolean {
    val knownKeywords = listOf(
        "agent", "ua",
        "refer", "ref",
        "origin", "org",
        "cookie",
        "auth", "bearer", "token",
        "content", "type",
        "accept", "acc",
        "range",
        "host",
        "apikey",
        "forward", "xff",
        "cache", "control",
        "pragma",
        "connection", "conn"
    )
    
    return knownKeywords.any { normalizedKey.contains(it) } ||
           normalizedKey.startsWith("x") ||
           normalizedKey.startsWith("sec")
}

private fun normalizeDrmScheme(scheme: String): String {
    val lower = scheme.lowercase().trim()
    return when {
        lower.contains("clearkey") || lower == "org.w3.clearkey" || lower == "cenc" -> "clearkey"
        lower.contains("widevine") || lower == "com.widevine.alpha" -> "widevine"
        lower.contains("playready") || lower == "com.microsoft.playready" -> "playready"
        lower.contains("fairplay") || lower == "com.apple.fps" || lower == "fps" -> "fairplay"
        else -> lower
    }
}

    private fun buildStreamUrl(link: LiveEventLink): String {
        var url = link.url
        val params = mutableListOf<String>()
        
        link.referer?.let { if (it.isNotEmpty()) params.add("referer=$it") }
        link.cookie?.let { if (it.isNotEmpty()) params.add("cookie=$it") }
        link.origin?.let { if (it.isNotEmpty()) params.add("origin=$it") }
        link.userAgent?.let { if (it.isNotEmpty()) params.add("user-agent=$it") }
        link.drmScheme?.let { if (it.isNotEmpty()) params.add("drmScheme=$it") }
        link.drmLicenseUrl?.let { if (it.isNotEmpty()) params.add("drmLicense=$it") }
        
        if (params.isNotEmpty()) {
            url += "|" + params.joinToString("|")
        }
        
        return url
    }

        private fun setupPlayer() {
        // FIXED: Check if we should use a transferred player (no loading!)
        val useTransferredPlayer = intent.getBooleanExtra("use_transferred_player", false)
        
        if (useTransferredPlayer) {
            val (transferredPlayer, transferredUrl, transferredName) = PlayerHolder.retrievePlayer()
            
            if (transferredPlayer != null) {
                android.util.Log.d("FloatingPlayerActivity", "Using transferred player - NO LOADING!")
                
                // FIXED: Hide loading indicator since we're not loading!
                binding.progressBar.visibility = View.GONE
                binding.errorView.visibility = View.GONE
                
                // Use the existing player
                player = transferredPlayer
                binding.playerView.player = player
                
                // Clear holder
                PlayerHolder.clearReferences()
                
                // Setup UI but don't recreate player
                bindControllerViews()
                configurePlayerInteractions()
                setupLockOverlay()
                
                // Player is already playing - just continue!
                return  // Exit early - no need to create new player
            }
        }
        
        if (player != null) return
        binding.errorView.visibility = View.GONE
        binding.errorText.text = ""
        binding.progressBar.visibility = View.VISIBLE
        
        // FIX 1: Hide controls immediately on setup start
        binding.playerView.hideController()
        
        trackSelector = DefaultTrackSelector(this)

        try {
            val streamInfo = parseStreamUrl(streamUrl)
            
            if (streamInfo.url.isBlank()) {
                showError("Invalid stream URL")
                return
            }

            val headers = streamInfo.headers.toMutableMap()
            if (!headers.containsKey("User-Agent")) {
                headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            }

            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(headers["User-Agent"] ?: "LiveTVPro/1.0")
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true)

            val mediaSourceFactory = if (streamInfo.drmScheme != null) {
                val drmSessionManager = when (streamInfo.drmScheme) {
                    "clearkey" -> {
                        if (streamInfo.drmKeyId != null && streamInfo.drmKey != null) {
                            createClearKeyDrmManager(streamInfo.drmKeyId, streamInfo.drmKey)
                        } else null
                    }
                    "widevine" -> {
                        if (streamInfo.drmLicenseUrl != null) {
                            createWidevineDrmManager(streamInfo.drmLicenseUrl, headers)
                        } else null
                    }
                    "playready" -> {
                        if (streamInfo.drmLicenseUrl != null) {
                            createPlayReadyDrmManager(streamInfo.drmLicenseUrl, headers)
                        } else null
                    }
                    else -> null
                }

                if (drmSessionManager != null) {
                    DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(dataSourceFactory)
                        .setDrmSessionManagerProvider { drmSessionManager }
                } else {
                    DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(dataSourceFactory)
                }
            } else {
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(dataSourceFactory)
            }

            player = ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector!!)
                .setMediaSourceFactory(mediaSourceFactory)
                .setSeekBackIncrementMs(skipMs)
                .setSeekForwardIncrementMs(skipMs)
                .build().also { exo ->
                    binding.playerView.player = exo
                    
                    // FIX 2: Ensure controls remain hidden after attaching player
                    binding.playerView.hideController()
                    
                    val mediaItem = MediaItem.fromUri(streamInfo.url)
                    exo.setMediaItem(mediaItem)
                    exo.prepare()
                    
                    // FIXED: Seek to saved position if available (for seamless transition from floating player)
                    if (savedPlaybackPosition > 0) {
                        android.util.Log.d("FloatingPlayerActivity", "Seeking to saved position: $savedPlaybackPosition")
                        exo.seekTo(savedPlaybackPosition)
                        savedPlaybackPosition = -1L // Reset after using
                    }
                    
                    exo.playWhenReady = true

                    playerListener = object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    // Stream is ready - hide loading
                                    updatePlayPauseIcon(exo.playWhenReady)
                                    binding.progressBar.visibility = View.GONE
                                    binding.errorView.visibility = View.GONE
                                    
                                    // FIX 3: REMOVED binding.playerView.showController()
                                    // Controls will NOT auto-show. User must tap to see them.
                                    
                                    updatePipParams()
                                }
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar.visibility = View.VISIBLE
                                    binding.errorView.visibility = View.GONE
                                    
                                    // FIX 4: Explicitly hide controls during buffering
                                    binding.playerView.hideController()
                                }
                                Player.STATE_ENDED -> {
                                    binding.progressBar.visibility = View.GONE
                                    // Optional: You might want to show controls here so user can restart
                                    binding.playerView.showController()
                                }
                                Player.STATE_IDLE -> {}
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            updatePlayPauseIcon(isPlaying)
                            if (isInPipMode) {
                                updatePipParams()
                            }
                        }

                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            super.onVideoSizeChanged(videoSize)
                            updatePipParams()
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            super.onPlayerError(error)
                            binding.progressBar.visibility = View.GONE

                            val errorMessage = when {
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT ->
                                    "Connection Failed"
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                                    when {
                                        error.message?.contains("403") == true -> "Access Denied"
                                        error.message?.contains("404") == true -> "Stream Not Found"
                                        else -> "Playback Error"
                                    }
                                }
                                error.message?.contains("drm", ignoreCase = true) == true ||
                                error.message?.contains("widevine", ignoreCase = true) == true ||
                                error.message?.contains("clearkey", ignoreCase = true) == true ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                                    "Stream Error"
                                error.message?.contains("geo", ignoreCase = true) == true ||
                                error.message?.contains("region", ignoreCase = true) == true ->
                                    "Not Available"
                                else -> "Playback Error"
                            }

                            showError(errorMessage)
                        }
                    }

                    exo.addListener(playerListener!!)
                }
        } catch (e: Exception) {
            showError("Failed to initialize player")
        }
    }

    
    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
        
        // Controls are already visible, just show the error message
        
        binding.errorText.apply {
            text = message
            typeface = try {
                resources.getFont(R.font.bergen_sans)
            } catch (e: Exception) {
                android.graphics.Typeface.DEFAULT
            }
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setPadding(48, 20, 48, 20)
            setBackgroundResource(R.drawable.error_message_background)
            elevation = 0f
        }
        
        val layoutParams = binding.errorView.layoutParams
        if (layoutParams is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
            layoutParams.verticalBias = 0.35f
            binding.errorView.layoutParams = layoutParams
        }
        
        binding.errorView.visibility = View.VISIBLE
    }

    private fun createClearKeyDrmManager(keyIdHex: String, keyHex: String): DefaultDrmSessionManager? {
        return try {
            val clearKeyUuid = UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")
            val keyIdBytes = hexToBytes(keyIdHex)
            val keyBytes = hexToBytes(keyHex)

            val keyIdBase64 = android.util.Base64.encodeToString(
                keyIdBytes,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
            val keyBase64 = android.util.Base64.encodeToString(
                keyBytes,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )

            val jwkResponse = """
            {
                "keys": [
                    {
                        "kty": "oct",
                        "k": "$keyBase64",
                        "kid": "$keyIdBase64"
                    }
                ]
            }
            """.trimIndent()

            val drmCallback = LocalMediaDrmCallback(jwkResponse.toByteArray())

            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(clearKeyUuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false)
                .build(drmCallback)
        } catch (e: Exception) {
            null
        }
    }

    private fun createWidevineDrmManager(licenseUrl: String, requestHeaders: Map<String, String>): DefaultDrmSessionManager? {
        return try {
            val widevineUuid = C.WIDEVINE_UUID

            val licenseDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(requestHeaders["User-Agent"] ?: "LiveTVPro/1.0")
                .setDefaultRequestProperties(requestHeaders)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)

            val drmCallback = HttpMediaDrmCallback(
                licenseUrl,
                licenseDataSourceFactory
            )

            requestHeaders.forEach { (key, value) ->
                drmCallback.setKeyRequestProperty(key, value)
            }

            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(widevineUuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false)
                .build(drmCallback)
        } catch (e: Exception) {
            null
        }
    }

    private fun createPlayReadyDrmManager(licenseUrl: String, requestHeaders: Map<String, String>): DefaultDrmSessionManager? {
        return try {
            val playReadyUuid = C.PLAYREADY_UUID

            val licenseDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(requestHeaders["User-Agent"] ?: "LiveTVPro/1.0")
                .setDefaultRequestProperties(requestHeaders)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)

            val drmCallback = HttpMediaDrmCallback(
                licenseUrl,
                licenseDataSourceFactory
            )

            requestHeaders.forEach { (key, value) ->
                drmCallback.setKeyRequestProperty(key, value)
            }

            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(playReadyUuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false)
                .build(drmCallback)
        } catch (e: Exception) {
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return try {
            val cleanHex = hex.replace(" ", "").replace("-", "").lowercase()
            if (cleanHex.length % 2 != 0) return ByteArray(0)
            cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        btnPlayPause?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun bindControllerViews() {
        binding.playerView.post {
            with(binding.playerView) {
                btnBack = findViewById(R.id.exo_back)
                btnPip = findViewById(R.id.exo_pip)
                btnSettings = findViewById(R.id.exo_settings)
                btnLock = findViewById(R.id.exo_lock)
                btnMute = findViewById(R.id.exo_mute)
                btnRewind = findViewById(R.id.exo_rewind)
                btnPlayPause = findViewById(R.id.exo_play_pause)
                btnForward = findViewById(R.id.exo_forward)
                btnFullscreen = findViewById(R.id.exo_fullscreen)
                btnAspectRatio = findViewById(R.id.exo_aspect_ratio)
                tvChannelName = findViewById(R.id.exo_channel_name)
            }
            
            btnBack?.setImageResource(R.drawable.ic_arrow_back)
            btnPip?.setImageResource(R.drawable.ic_pip)
            btnSettings?.setImageResource(R.drawable.ic_settings)
            btnLock?.setImageResource(if (isLocked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open)
            updateMuteIcon()
            btnRewind?.setImageResource(R.drawable.ic_skip_backward)
            updatePlayPauseIcon(player?.isPlaying == true)
            btnForward?.setImageResource(R.drawable.ic_skip_forward)
            btnAspectRatio?.setImageResource(R.drawable.ic_aspect_ratio)
            
            val currentOrientation = resources.configuration.orientation
            if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
            } else {
                btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
            }
            
            listOf(btnBack, btnPip, btnSettings, btnLock, btnMute, btnRewind, 
                   btnPlayPause, btnForward, btnFullscreen, btnAspectRatio).forEach {
                it?.apply { 
                    isClickable = true
                    isFocusable = true
                    isEnabled = true
                    visibility = View.VISIBLE
                }
            }
            
            btnAspectRatio?.visibility = View.VISIBLE
            btnPip?.visibility = View.VISIBLE
            btnFullscreen?.visibility = View.VISIBLE
            
            // Always show title TextView to maintain layout structure
            tvChannelName?.visibility = View.VISIBLE
            if (contentType == ContentType.NETWORK_STREAM) {
                // For network streams, show empty title (or use contentName if you want to show the stream name)
                tvChannelName?.text = ""
            } else {
                tvChannelName?.text = contentName
            }
            
            try {
                val bergenSansFont = resources.getFont(R.font.bergen_sans)
                tvChannelName?.typeface = bergenSansFont
                
                binding.playerView.findViewById<TextView>(R.id.exo_position)?.typeface = bergenSansFont
                binding.playerView.findViewById<TextView>(R.id.exo_duration)?.typeface = bergenSansFont
            } catch (e: Exception) {
            }
            
            setupControlListeners()
        }
    }

    private fun setupControlListeners() {
        btnBack?.setOnClickListener { if (!isLocked) finish() }
        
        btnPip?.setOnClickListener {
            if (!isLocked) {
                val currentChannel = channelData
                val currentEvent = eventData
                val currentPlayer = player
                val currentStreamUrl = streamUrl
                val currentName = contentName

                // Accept either channel OR event — previously only channel was checked
                // so the entire block was skipped for event content
                if (currentPlayer != null && (currentChannel != null || currentEvent != null)) {
                    PlayerHolder.transferPlayer(currentPlayer, currentStreamUrl, currentName)

                    val sourceInstanceId = intent.getStringExtra("source_instance_id")

                    val intent = Intent(this, FloatingPlayerService::class.java).apply {
                        // Pass whichever content type we have
                        if (currentChannel != null) {
                            putExtra(FloatingPlayerService.EXTRA_CHANNEL, currentChannel)
                        }
                        if (currentEvent != null) {
                            putExtra(FloatingPlayerService.EXTRA_EVENT, currentEvent)
                        }
                        putExtra(FloatingPlayerService.EXTRA_RESTORE_POSITION, true)
                        putExtra("use_transferred_player", true)
                        if (sourceInstanceId != null) {
                            putExtra(FloatingPlayerService.EXTRA_INSTANCE_ID, sourceInstanceId)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }

                    player = null
                    finish()
                }
            }
        }
        
        btnSettings?.setOnClickListener { if (!isLocked) showPlayerSettingsDialog() }
        btnAspectRatio?.setOnClickListener { if (!isLocked) toggleAspectRatio() }
        btnLock?.setOnClickListener { toggleLock() }
        
        btnRewind?.setOnClickListener {
            if (!isLocked) player?.let { p ->
                val newPosition = p.currentPosition - skipMs
                p.seekTo(if (newPosition < 0) 0 else newPosition)
            }
        }
        
        btnPlayPause?.apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                handlePlayPauseClick()
            }
        }
        
        btnForward?.setOnClickListener {
            if (!isLocked) player?.let { p ->
                val newPosition = p.currentPosition + skipMs
                if (p.isCurrentWindowLive && p.duration != C.TIME_UNSET && newPosition >= p.duration) {
                    p.seekTo(p.duration)
                } else {
                    p.seekTo(newPosition)
                }
            }
        }
        
        btnFullscreen?.apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { 
                if (!isLocked) {
                    toggleFullscreen()
                }
            }
        }
        
        btnMute?.setOnClickListener { if (!isLocked) toggleMute() }
    }

    private fun handlePlayPauseClick() {
        val hasError = binding.errorView.visibility == View.VISIBLE
        val hasEnded = player?.playbackState == Player.STATE_ENDED
        
        if (hasError || hasEnded) {
            retryPlayback()
        } else {
            if (!isLocked) {
                player?.let { p ->
                    if (p.isPlaying) p.pause() else p.play()
                }
            }
        }
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            updateMuteIcon()
        }
    }

    private fun updateMuteIcon() {
        btnMute?.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
    }

    private fun toggleAspectRatio() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        binding.playerView.resizeMode = currentResizeMode
    }

    private fun showPlayerSettingsDialog() {
        val exoPlayer = player ?: return
        try {
            val dialog = com.livetvpro.ui.player.settings.PlayerSettingsDialog(this, exoPlayer)
            dialog.show()
        } catch (e: Exception) {
        }
    }

    private fun configurePlayerInteractions() {
        binding.playerView.apply {
            setControllerHideDuringAds(false)
            controllerShowTimeoutMs = 5000
            controllerHideOnTouch = true
        }
    }

    private fun setupLockOverlay() {
        binding.unlockButton.background = resources.getDrawable(R.drawable.ripple_square_white, null)
        binding.unlockButton.setOnClickListener { toggleLock() }
        binding.lockOverlay.setOnClickListener {
            if (binding.unlockButton.visibility == View.VISIBLE) hideUnlockButton() else showUnlockButton()
        }
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE
    }

    private fun showUnlockButton() {
        binding.unlockButton.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        mainHandler.postDelayed(hideUnlockButtonRunnable, 3000)
    }

    private fun hideUnlockButton() {
        mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        binding.unlockButton.visibility = View.GONE
    }

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            binding.playerView.useController = false
            binding.playerView.hideController()
            binding.lockOverlay.apply {
                visibility = View.VISIBLE
                isClickable = true
                isFocusable = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            showUnlockButton()
            btnLock?.setImageResource(R.drawable.ic_lock_closed)
        } else {
            binding.playerView.useController = true
            binding.lockOverlay.visibility = View.GONE
            hideUnlockButton()
            btnLock?.setImageResource(R.drawable.ic_lock_open)
            binding.playerView.showController()
        }
    }

    private fun toggleFullscreen() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
    
    /**
     * Force exit fullscreen mode - ensures 16:9 player container immediately
     */
    private fun exitFullscreen() {
        // 1. Show System UI (Status bar, navigation)
        windowInsetsController.apply {
            show(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
        
        // 2. FORCE the Player Container to 16:9 Ratio
        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        params.width = ConstraintLayout.LayoutParams.MATCH_PARENT
        params.height = 0 // 0dp means "Match Constraint"
        params.dimensionRatio = "H,16:9" // Force the 16:9 aspect ratio
        
        // Remove bottom constraint to allow it to sit at the top only
        params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        
        binding.playerContainer.layoutParams = params

        // 3. Update related UI visibility if needed
        binding.relatedChannelsSection.visibility = View.VISIBLE
        binding.linksSection.visibility = View.VISIBLE
        // Ensure the container itself is visible
        binding.playerContainer.visibility = View.VISIBLE
    }
    
    /**
     * Force enter fullscreen mode - makes player fill entire screen
     */
    private fun enterFullscreen() {
        // Hide System UI
        windowInsetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Force Player Container to Fill Screen
        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        params.width = ConstraintLayout.LayoutParams.MATCH_PARENT
        params.height = ConstraintLayout.LayoutParams.MATCH_PARENT // Fill height
        params.dimensionRatio = null // Remove ratio constraint
        
        binding.playerContainer.layoutParams = params
        
        // Optional: Hide other views if you want true fullscreen
        binding.relatedChannelsSection.visibility = View.GONE
        binding.linksSection.visibility = View.GONE
    }

    private fun setSubtitleTextSize() {
        val subtitleView = binding.playerView.subtitleView ?: return
        subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
    }

    private fun setSubtitleTextSizePiP() {
        val subtitleView = binding.playerView.subtitleView ?: return
        subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 2)
    }
    
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return

        player?.let {
            if (!it.isPlaying) {
                it.play()
            }
        }

        binding.playerView.useController = false
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE

        setSubtitleTextSizePiP()

        updatePipParams(enter = true)
    }

    private fun updatePipParams(enter: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        
        try {
            val format = player?.videoFormat
            val width = format?.width ?: 16
            val height = format?.height ?: 9
            
            var ratio = if (width > 0 && height > 0) {
                Rational(width, height)
            } else {
                Rational(16, 9)
            }
            
            val rationalLimitWide = Rational(239, 100)
            val rationalLimitTall = Rational(100, 239)
            
            if (ratio.toFloat() > rationalLimitWide.toFloat()) {
                ratio = rationalLimitWide
            } else if (ratio.toFloat() < rationalLimitTall.toFloat()) {
                ratio = rationalLimitTall
            }
            
            val builder = PictureInPictureParams.Builder()
            builder.setAspectRatio(ratio)
            
            val actions = buildPipActions()
            builder.setActions(actions)
            
            val pipSourceRect = android.graphics.Rect()
            binding.playerView.getGlobalVisibleRect(pipSourceRect)
            if (!pipSourceRect.isEmpty) {
                builder.setSourceRectHint(pipSourceRect)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(false)
                builder.setSeamlessResizeEnabled(true)
            }
            
            if (enter) {
                enterPictureInPictureMode(builder.build())
            } else {
                setPictureInPictureParams(builder.build())
            }
        } catch (e: Exception) {
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipActions(): List<RemoteAction> {
        val actions = mutableListOf<RemoteAction>()
        
        val rewindIntent = PendingIntent.getBroadcast(
            this,
            CONTROL_TYPE_REWIND,
            Intent(ACTION_MEDIA_CONTROL)
                .setPackage(packageName)
                .putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_REWIND),
            PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_skip_backward),
            "Rewind",
            "Rewind 10s",
            rewindIntent
        ))
        
        val isPlaying = player?.isPlaying == true
        if (isPlaying) {
            val pauseIntent = PendingIntent.getBroadcast(
                this,
                CONTROL_TYPE_PAUSE,
                Intent(ACTION_MEDIA_CONTROL)
                    .setPackage(packageName)
                    .putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PAUSE),
                PendingIntent.FLAG_IMMUTABLE
            )
            actions.add(RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_pause),
                getString(R.string.pause),
                getString(R.string.pause),
                pauseIntent
            ))
        } else {
            val playIntent = PendingIntent.getBroadcast(
                this,
                CONTROL_TYPE_PLAY,
                Intent(ACTION_MEDIA_CONTROL)
                    .setPackage(packageName)
                    .putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PLAY),
                PendingIntent.FLAG_IMMUTABLE
            )
            actions.add(RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_play),
                getString(R.string.play),
                getString(R.string.play),
                playIntent
            ))
        }
        
        val forwardIntent = PendingIntent.getBroadcast(
            this,
            CONTROL_TYPE_FORWARD,
            Intent(ACTION_MEDIA_CONTROL)
                .setPackage(packageName)
                .putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_FORWARD),
            PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_skip_forward),
            "Forward",
            "Forward 10s",
            forwardIntent
        ))
        
        return actions
    }

    private fun registerPipReceiver() {
        if (pipReceiver != null) return
        
        pipReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_MEDIA_CONTROL) return
                
                val currentPlayer = player ?: return
                val hasError = binding.errorView.visibility == View.VISIBLE
                val hasEnded = currentPlayer.playbackState == Player.STATE_ENDED
                
                when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                    CONTROL_TYPE_PLAY -> {
                        if (hasError || hasEnded) {
                            retryPlayback()
                        } else {
                            currentPlayer.play()
                        }
                        updatePipParams()
                    }
                    CONTROL_TYPE_PAUSE -> {
                        if (hasError || hasEnded) {
                            retryPlayback()
                        } else {
                            currentPlayer.pause()
                        }
                        updatePipParams()
                    }
                    CONTROL_TYPE_REWIND -> {
                        if (!hasError && !hasEnded) {
                            val newPosition = currentPlayer.currentPosition - skipMs
                            currentPlayer.seekTo(if (newPosition < 0) 0 else newPosition)
                        }
                    }
                    CONTROL_TYPE_FORWARD -> {
                        if (!hasError && !hasEnded) {
                            val newPosition = currentPlayer.currentPosition + skipMs
                            if (currentPlayer.isCurrentWindowLive && currentPlayer.duration != C.TIME_UNSET && newPosition >= currentPlayer.duration) {
                                currentPlayer.seekTo(currentPlayer.duration)
                            } else {
                                currentPlayer.seekTo(newPosition)
                            }
                        }
                    }
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL))
        }
    }

    private fun unregisterPipReceiver() {
        try {
            pipReceiver?.let {
                unregisterReceiver(it)
                pipReceiver = null
            }
        } catch (e: Exception) {
        }
    }

        private fun retryPlayback() {
        binding.errorView.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        
        // FIX 5: Hide controls explicitly when retrying
        binding.playerView.hideController()
        
        player?.release()
        player = null
        setupPlayer()
    }


    override fun finish() {
        try {
            // Restore any floating windows that were hidden when this activity launched.
            // They are TYPE_APPLICATION_OVERLAY and were hidden so they wouldn't cover this UI.
            FloatingPlayerService.showAll(this)

            // FIXED: Only release player if it wasn't transferred
            if (player != null) {
                releasePlayer()
            }
            unregisterPipReceiver()
            isInPipMode = false
            userRequestedPip = false
            wasLockedBeforePip = false
            super.finish()
        } catch (e: Exception) {
            super.finish()
        }
    }
}
