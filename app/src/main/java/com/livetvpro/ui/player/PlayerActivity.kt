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
import com.livetvpro.ui.adapters.LinkChipAdapter
import com.livetvpro.data.models.LiveEventLink
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * PlayerActivity - Main video player activity
 * 
 * UPDATED FEATURES:
 * - Proper orientation handling without recreation (via configChanges)
 * - Correct layout adjustments on orientation change
 * - PiP mode with proper state restoration
 * - Window insets controller for immersive mode
 * - Proper binding persistence across configuration changes
 */
@UnstableApi
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var playerListener: Player.Listener? = null
    
    private lateinit var relatedChannelsAdapter: RelatedChannelAdapter
    private var relatedChannels = listOf<Channel>()
    private lateinit var linkChipAdapter: LinkChipAdapter

    // Window insets controller for proper immersive mode
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

    enum class ContentType {
        CHANNEL, EVENT
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
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel as Parcelable)
                putExtra(EXTRA_SELECTED_LINK_INDEX, linkIndex)
            }
            context.startActivity(intent)
        }

        fun startWithEvent(context: Context, event: LiveEvent, linkIndex: Int = -1) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_EVENT, event as Parcelable)
                putExtra(EXTRA_SELECTED_LINK_INDEX, linkIndex)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize binding - this persists across orientation changes
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize window insets controller
        windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Setup window flags and UI based on initial orientation
        val currentOrientation = resources.configuration.orientation
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        
        setupWindowFlags(isLandscape)
        setupSystemUI(isLandscape)
        
        parseIntent()

        if (contentType == ContentType.CHANNEL && contentId.isNotEmpty()) {
            viewModel.refreshChannelData(contentId)
        }

        binding.progressBar.visibility = View.VISIBLE
        
        setupPlayer()
        
        // Bind controller views after a short delay to ensure player view is laid out
        binding.playerView.postDelayed({
            bindControllerViews()
            applyOrientationSettings(isLandscape)
        }, 100)

        configurePlayerInteractions()
        setupLockOverlay()
        setupRelatedChannels()
        setupLinksUI()
        loadRelatedContent()
        
        // Observe refreshed channel data
        viewModel.refreshedChannel.observe(this) { freshChannel ->
            if (freshChannel != null && freshChannel.links != null && freshChannel.links.isNotEmpty()) {
                if (allEventLinks.isEmpty() || allEventLinks.size < freshChannel.links.size) {
                    allEventLinks = freshChannel.links.map { 
                        LiveEventLink(
                            id = UUID.randomUUID().toString(),
                            name = it.name ?: "Link ${it.url?.takeLast(8)}",
                            url = it.url ?: "",
                            drmScheme = it.drmScheme,
                            drmLicenseUrl = it.drmLicenseUrl
                        )
                    }
                    if (allEventLinks.size > 1) {
                        binding.linksSection.visibility = View.VISIBLE
                        linkChipAdapter.updateLinks(allEventLinks)
                    }
                }
            }
        }
    }

    /**
     * Handle configuration changes (orientation, screen size, etc.)
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        if (isInPipMode) {
            return
        }
        
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        // Update window flags and system UI
        setupWindowFlags(isLandscape)
        setupSystemUI(isLandscape)
        
        // Apply orientation-specific settings
        applyOrientationSettings(isLandscape)
    }

    /**
     * Setup window flags based on orientation
     */
    private fun setupWindowFlags(isLandscape: Boolean) {
        if (isLandscape) {
            // Landscape: fullscreen immersive
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        } else {
            // Portrait: normal window
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    /**
     * Setup system UI (status bar, navigation bar) based on orientation
     */
    private fun setupSystemUI(isLandscape: Boolean) {
        if (isLandscape) {
            // Landscape: hide system bars for immersive experience
            WindowCompat.setDecorFitsSystemWindows(window, false)
            windowInsetsController.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                hide(WindowInsetsCompat.Type.navigationBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Portrait: show system bars
            WindowCompat.setDecorFitsSystemWindows(window, true)
            windowInsetsController.apply {
                show(WindowInsetsCompat.Type.systemBars())
                show(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }

    /**
     * Apply orientation-specific layout settings
     */
    private fun applyOrientationSettings(isLandscape: Boolean) {
        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        
        if (isLandscape) {
            // LANDSCAPE: Full screen with no aspect ratio
            params.dimensionRatio = null
            params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.topMargin = 0
            params.bottomMargin = 0
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            
            binding.playerContainer.setPadding(0, 0, 0, 0)
            
            // Player view settings
            binding.playerView.controllerAutoShow = false
            binding.playerView.controllerShowTimeoutMs = 3000
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            
            // Hide portrait-only sections
            binding.relatedChannelsSection.visibility = View.GONE
            binding.linksSection.visibility = View.GONE
            
        } else {
            // PORTRAIT: 16:9 at top with sections below
            params.dimensionRatio = "16:9"
            params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.height = ConstraintLayout.LayoutParams.WRAP_CONTENT
            params.topMargin = 0
            params.bottomMargin = 0
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            
            // Player view settings
            binding.playerView.controllerAutoShow = false
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
            
            // Show sections if content exists
            binding.root.post {
                if (allEventLinks.size > 1) {
                    binding.linksSection.visibility = View.VISIBLE
                } else {
                    binding.linksSection.visibility = View.GONE
                }
                
                if (relatedChannels.isNotEmpty()) {
                    binding.relatedChannelsSection.visibility = View.VISIBLE
                } else {
                    binding.relatedChannelsSection.visibility = View.GONE
                }
            }
        }
        
        // Apply the updated params
        binding.playerContainer.layoutParams = params
        
        // Request layout update
        binding.root.requestLayout()
    }

    /**
     * CRITICAL: Handle PiP mode changes
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
        // Register PiP receiver for controls
        registerPipReceiver()
        
        // Hide all UI
        binding.playerView.useController = false 
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE
        binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        binding.playerView.hideController()
        
        // Show system UI
        WindowCompat.setDecorFitsSystemWindows(window, true)
        windowInsetsController.apply {
            show(WindowInsetsCompat.Type.systemBars())
            show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    /**
     * Restore UI when exiting PiP mode
     */
    private fun exitPipUIMode(newConfig: Configuration) {
        userRequestedPip = false
        unregisterPipReceiver()
        
        if (isFinishing) {
            return
        }
        
        setSubtitleTextSize()
        
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        // Restore window flags and system UI
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
        if (!isInPipMode && player?.isPlaying == true) {
            userRequestedPip = true
            wasLockedBeforePip = isLocked
            enterPipMode()
        }
    }

    private fun parseIntent() {
        val selectedLinkIndex = intent.getIntExtra(EXTRA_SELECTED_LINK_INDEX, -1)
        
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                intent.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)?.let { channel ->
                    contentType = ContentType.CHANNEL
                    channelData = channel
                    contentId = channel.id
                    contentName = channel.name
                    if (!channel.links.isNullOrEmpty()) {
                        allEventLinks = channel.links.map { link ->
                            LiveEventLink(
                                id = UUID.randomUUID().toString(),
                                name = link.name ?: "Link ${link.url?.takeLast(8)}",
                                url = link.url ?: "",
                                drmScheme = link.drmScheme,
                                drmLicenseUrl = link.drmLicenseUrl
                            )
                        }
                        currentLinkIndex = if (selectedLinkIndex in allEventLinks.indices) {
                            selectedLinkIndex
                        } else {
                            0
                        }
                        streamUrl = allEventLinks[currentLinkIndex].url
                    }
                }
                
                intent.getParcelableExtra(EXTRA_EVENT, LiveEvent::class.java)?.let { event ->
                    contentType = ContentType.EVENT
                    eventData = event
                    contentId = event.id
                    contentName = event.title
                    if (!event.links.isNullOrEmpty()) {
                        allEventLinks = event.links
                        currentLinkIndex = if (selectedLinkIndex in allEventLinks.indices) {
                            selectedLinkIndex
                        } else {
                            0
                        }
                        streamUrl = allEventLinks[currentLinkIndex].url
                    }
                }
            }
            else -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Channel>(EXTRA_CHANNEL)?.let { channel ->
                    contentType = ContentType.CHANNEL
                    channelData = channel
                    contentId = channel.id
                    contentName = channel.name
                    if (!channel.links.isNullOrEmpty()) {
                        allEventLinks = channel.links.map { link ->
                            LiveEventLink(
                                id = UUID.randomUUID().toString(),
                                name = link.name ?: "Link ${link.url?.takeLast(8)}",
                                url = link.url ?: "",
                                drmScheme = link.drmScheme,
                                drmLicenseUrl = link.drmLicenseUrl
                            )
                        }
                        currentLinkIndex = if (selectedLinkIndex in allEventLinks.indices) {
                            selectedLinkIndex
                        } else {
                            0
                        }
                        streamUrl = allEventLinks[currentLinkIndex].url
                    }
                }
                
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<LiveEvent>(EXTRA_EVENT)?.let { event ->
                    contentType = ContentType.EVENT
                    eventData = event
                    contentId = event.id
                    contentName = event.title
                    if (!event.links.isNullOrEmpty()) {
                        allEventLinks = event.links
                        currentLinkIndex = if (selectedLinkIndex in allEventLinks.indices) {
                            selectedLinkIndex
                        } else {
                            0
                        }
                        streamUrl = allEventLinks[currentLinkIndex].url
                    }
                }
            }
        }
    }

    private fun setupPlayer() {
        if (streamUrl.isEmpty()) {
            showError("No stream URL available")
            return
        }

        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSizeSd()
                    .build()
            )
        }

        val currentLink = allEventLinks[currentLinkIndex]
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("LiveTVPro/1.0")
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = if (!currentLink.drmScheme.isNullOrEmpty() && !currentLink.drmLicenseUrl.isNullOrEmpty()) {
            val drmCallback = HttpMediaDrmCallback(currentLink.drmLicenseUrl, httpDataSourceFactory)
            val drmSessionManager = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(
                    when (currentLink.drmScheme.lowercase()) {
                        "widevine" -> C.WIDEVINE_UUID
                        "playready" -> C.PLAYREADY_UUID
                        "clearkey" -> C.CLEARKEY_UUID
                        else -> C.WIDEVINE_UUID
                    },
                    FrameworkMediaDrm.DEFAULT_PROVIDER
                )
                .build(drmCallback)
            
            DefaultMediaSourceFactory(this)
                .setDataSourceFactory(httpDataSourceFactory)
                .setDrmSessionManagerProvider { drmSessionManager }
        } else {
            DefaultMediaSourceFactory(this)
                .setDataSourceFactory(httpDataSourceFactory)
        }

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector!!)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        binding.playerView.player = player
        
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .build()

        playerListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        binding.progressBar.visibility = View.GONE
                        binding.errorView.visibility = View.GONE
                    }
                    Player.STATE_BUFFERING -> {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    Player.STATE_ENDED -> {
                        binding.progressBar.visibility = View.GONE
                    }
                }
                updatePlayPauseButton()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode) {
                    updatePipParams()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                binding.progressBar.visibility = View.GONE
                showError("Playback error: ${error.message}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseButton()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode) {
                    updatePipParams()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                setSubtitleTextSize()
            }
        }

        player?.addListener(playerListener!!)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.playWhenReady = true

        setSubtitleTextSize()
    }

    private fun bindControllerViews() {
        val controllerView = binding.playerView.findViewById<View>(R.id.exo_controller)
        
        btnBack = controllerView?.findViewById(R.id.btn_back)
        btnPip = controllerView?.findViewById(R.id.btn_pip)
        btnSettings = controllerView?.findViewById(R.id.btn_settings)
        btnLock = controllerView?.findViewById(R.id.btn_lock)
        btnMute = controllerView?.findViewById(R.id.btn_mute)
        btnRewind = controllerView?.findViewById(R.id.exo_rew)
        btnPlayPause = controllerView?.findViewById(R.id.exo_play_pause)
        btnForward = controllerView?.findViewById(R.id.exo_ffwd)
        btnFullscreen = controllerView?.findViewById(R.id.btn_fullscreen)
        btnAspectRatio = controllerView?.findViewById(R.id.btn_aspect_ratio)
        tvChannelName = controllerView?.findViewById(R.id.tv_channel_name)

        tvChannelName?.text = contentName

        btnBack?.setOnClickListener {
            finish()
        }

        btnPip?.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPipMode()
            }
        }

        btnLock?.setOnClickListener {
            toggleLock()
        }

        btnMute?.setOnClickListener {
            toggleMute()
        }

        btnFullscreen?.setOnClickListener {
            toggleFullscreen()
        }

        btnAspectRatio?.setOnClickListener {
            cycleAspectRatio()
        }

        updatePlayPauseButton()
        updateMuteButton()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            btnPip?.visibility = View.GONE
        }
    }

    private fun configurePlayerInteractions() {
        binding.playerView.setOnClickListener {
            if (!isLocked) {
                if (binding.playerView.isControllerFullyVisible) {
                    binding.playerView.hideController()
                } else {
                    binding.playerView.showController()
                }
            }
        }
    }

    private fun setupLockOverlay() {
        binding.lockOverlay.setOnClickListener {
            showUnlockButton()
        }

        binding.unlockButton.setOnClickListener {
            toggleLock()
        }
    }

    private fun toggleLock() {
        isLocked = !isLocked
        
        if (isLocked) {
            binding.playerView.useController = false
            binding.playerView.hideController()
            binding.lockOverlay.visibility = View.VISIBLE
            showUnlockButton()
        } else {
            binding.playerView.useController = true
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            binding.playerView.showController()
        }
    }

    private fun showUnlockButton() {
        mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        binding.unlockButton.visibility = View.VISIBLE
        mainHandler.postDelayed(hideUnlockButtonRunnable, 3000)
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            updateMuteButton()
        }
    }

    private fun updateMuteButton() {
        btnMute?.setImageResource(
            if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_on
        )
    }

    private fun updatePlayPauseButton() {
        val isPlaying = player?.isPlaying == true
        btnPlayPause?.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun toggleFullscreen() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun cycleAspectRatio() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        binding.playerView.resizeMode = currentResizeMode
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { channel ->
            switchToChannel(channel)
        }

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.relatedChannelsRecyclerView.apply {
            layoutManager = if (isLandscape) {
                LinearLayoutManager(this@PlayerActivity, LinearLayoutManager.HORIZONTAL, false)
            } else {
                GridLayoutManager(this@PlayerActivity, 3)
            }
            adapter = relatedChannelsAdapter
        }
    }

    private fun setupLinksUI() {
        linkChipAdapter = LinkChipAdapter(
            links = allEventLinks,
            selectedIndex = currentLinkIndex
        ) { clickedLink, index ->
            switchToLink(index)
        }

        binding.linksRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = linkChipAdapter
        }

        if (allEventLinks.size > 1) {
            binding.linksSection.visibility = View.VISIBLE
        } else {
            binding.linksSection.visibility = View.GONE
        }
    }

    private fun loadRelatedContent() {
        lifecycleScope.launch {
            when (contentType) {
                ContentType.CHANNEL -> {
                    viewModel.fetchRelatedChannels(contentId)
                    viewModel.relatedChannels.observe(this@PlayerActivity) { channels ->
                        relatedChannels = channels
                        relatedChannelsAdapter.submitList(channels)
                        
                        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        if (!isLandscape && channels.isNotEmpty()) {
                            binding.relatedChannelsSection.visibility = View.VISIBLE
                        }
                    }
                }
                ContentType.EVENT -> {
                    // For events, we don't show related channels
                    binding.relatedChannelsSection.visibility = View.GONE
                }
            }
        }
    }

    private fun switchToChannel(channel: Channel) {
        if (!channel.links.isNullOrEmpty()) {
            releasePlayer()
            
            contentType = ContentType.CHANNEL
            channelData = channel
            contentId = channel.id
            contentName = channel.name
            allEventLinks = channel.links.map { link ->
                LiveEventLink(
                    id = UUID.randomUUID().toString(),
                    name = link.name ?: "Link ${link.url?.takeLast(8)}",
                    url = link.url ?: "",
                    drmScheme = link.drmScheme,
                    drmLicenseUrl = link.drmLicenseUrl
                )
            }
            currentLinkIndex = 0
            streamUrl = allEventLinks[currentLinkIndex].url
            
            tvChannelName?.text = contentName
            
            linkChipAdapter.updateLinks(allEventLinks)
            linkChipAdapter.updateSelection(currentLinkIndex)
            
            if (allEventLinks.size > 1) {
                binding.linksSection.visibility = View.VISIBLE
            } else {
                binding.linksSection.visibility = View.GONE
            }
            
            binding.progressBar.visibility = View.VISIBLE
            setupPlayer()
            
            viewModel.refreshChannelData(contentId)
            viewModel.fetchRelatedChannels(contentId)
        }
    }

    private fun switchToLink(linkIndex: Int) {
        if (linkIndex == currentLinkIndex || linkIndex !in allEventLinks.indices) {
            return
        }

        val wasPlaying = player?.isPlaying == true
        val currentPosition = player?.currentPosition ?: 0L

        releasePlayer()

        currentLinkIndex = linkIndex
        streamUrl = allEventLinks[currentLinkIndex].url
        linkChipAdapter.updateSelection(currentLinkIndex)

        binding.progressBar.visibility = View.VISIBLE
        setupPlayer()

        if (wasPlaying) {
            player?.seekTo(currentPosition)
            player?.play()
        }
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.errorView.visibility = View.VISIBLE
        binding.errorMessage.text = message
        binding.retryButton.setOnClickListener {
            binding.errorView.visibility = View.GONE
            binding.progressBar.visibility = View.VISIBLE
            player?.release()
            player = null
            setupPlayer()
        }
    }

    private fun setSubtitleTextSize() {
        val subtitleView = binding.playerView.subtitleView ?: return
        val videoHeight = player?.videoSize?.height ?: 0
        
        val textSizePx = when {
            videoHeight >= 1080 -> 32f
            videoHeight >= 720 -> 24f
            videoHeight >= 480 -> 20f
            else -> 16f
        }
        
        subtitleView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx)
        subtitleView.setStyle(
            androidx.media3.ui.CaptionStyleCompat(
                android.graphics.Color.WHITE,
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                android.graphics.Color.BLACK,
                null
            )
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            val params = buildPipParams()
            try {
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        
        val videoSize = player?.videoSize
        if (videoSize != null && videoSize.width > 0 && videoSize.height > 0) {
            val rational = Rational(videoSize.width, videoSize.height)
            builder.setAspectRatio(rational)
        }
        
        val actions = buildPipActions()
        builder.setActions(actions)
        
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipParams() {
        if (isInPipMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                setPictureInPictureParams(buildPipParams())
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
        player?.release()
        player = null
        setupPlayer()
    }

    private fun releasePlayer() {
        playerListener?.let { player?.removeListener(it) }
        player?.release()
        player = null
        trackSelector = null
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPipMode) {
            player?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        unregisterPipReceiver()
        mainHandler.removeCallbacks(hideUnlockButtonRunnable)
    }

    override fun finish() {
        try {
            releasePlayer()
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
