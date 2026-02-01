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
import android.provider.Settings
import android.util.Rational
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
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
import com.livetvpro.utils.PlayerOrientation
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.UUID

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
    private var videoLoading = true
    
    // Orientation preferences
    private var currentOrientation: PlayerOrientation = PlayerOrientation.VIDEO

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

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Set orientation ASAP, before super/inflating to avoid glitches with activity launch animation
        setOrientation(currentOrientation)
        
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        val currentOrientationConfig = resources.configuration.orientation
        val isLandscape = currentOrientationConfig == Configuration.ORIENTATION_LANDSCAPE
        
        setWindowFlags(isLandscape)
        setupWindowInsets()
        
        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        if (isLandscape) {
            params.dimensionRatio = null
            params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        } else {
            params.dimensionRatio = "H,16:9"
            params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        }
        binding.playerContainer.layoutParams = params

        parseIntent()

        if (contentType == ContentType.CHANNEL && contentId.isNotEmpty()) {
            viewModel.refreshChannelData(contentId)
        }

        binding.progressBar.visibility = View.VISIBLE
        
        setupPlayer()
        
        binding.playerView.post {
            bindControllerViews()
            setupRotationButton()
            // Set initial resize mode based on orientation
            if (isLandscape) {
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            } else {
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }

        configurePlayerInteractions()
        setupLockOverlay()
        setupRelatedChannels()
        setupLinksUI()
        loadRelatedContent()
        
        viewModel.refreshedChannel.observe(this) { freshChannel ->
            if (freshChannel != null && freshChannel.links != null && freshChannel.links.isNotEmpty()) {
                if (allEventLinks.isEmpty() || allEventLinks.size < freshChannel.links.size) {
                    allEventLinks = freshChannel.links.map { 
                        LiveEventLink(label = it.quality, url = it.url) 
                    }
                    
                    val matchIndex = allEventLinks.indexOfFirst { it.url == streamUrl }
                    if (matchIndex != -1) {
                        currentLinkIndex = matchIndex
                    }
                    
                    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    updateLinksForOrientation(isLandscape)
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerPipReceiver()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        // Update subtitle text size when not in PiP
        if (!isInPip()) {
            setSubtitleTextSize(newConfig.orientation)
        }
        updateSubtitleViewMargin()
        
        // Update rotation button icon
        updateButtonRotation()
        
        // Handle orientation-specific UI changes
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        setWindowFlags(isLandscape)
        
        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        if (isLandscape) {
            params.dimensionRatio = null
            params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            
            binding.relatedChannelsSection.visibility = View.GONE
            binding.linksSection.visibility = View.GONE
            
            if (!isInPipMode) {
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        } else {
            params.dimensionRatio = "H,16:9"
            params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            
            if (allEventLinks.size > 1) {
                binding.linksSection.visibility = View.VISIBLE
            }
            if (relatedChannels.isNotEmpty()) {
                binding.relatedChannelsSection.visibility = View.VISIBLE
            }
            
            if (!isInPipMode) {
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }
        binding.playerContainer.layoutParams = params
        
        updateLinksForOrientation(isLandscape)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun isInPip(): Boolean {
        if (!isPiPSupported()) return false
        return isInPictureInPictureMode
    }

    private fun isPiPSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        
        val packageManager = packageManager
        
        // Check for Fire TV and disable PiP
        if (packageManager.hasSystemFeature("amazon.hardware.fire_tv")) {
            return false
        }
        
        return packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun setOrientation(orientation: PlayerOrientation) {
        val currentPlayer = player
        
        when (orientation) {
            PlayerOrientation.VIDEO -> {
                // If player exists and has video format, set orientation based on video
                if (currentPlayer != null && !videoLoading) {
                    val format = currentPlayer.videoFormat
                    if (format != null) {
                        if (isPortrait(format)) {
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        } else {
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    } else {
                        // Default to landscape while loading
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                } else {
                    // Default to landscape while loading
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
            PlayerOrientation.LANDSCAPE -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            PlayerOrientation.PORTRAIT -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            PlayerOrientation.LOCKED_LANDSCAPE -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            PlayerOrientation.LOCKED_PORTRAIT -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            PlayerOrientation.SYSTEM -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    private fun getNextOrientation(): PlayerOrientation {
        return when (currentOrientation) {
            PlayerOrientation.VIDEO -> PlayerOrientation.LANDSCAPE
            PlayerOrientation.LANDSCAPE -> PlayerOrientation.PORTRAIT
            PlayerOrientation.PORTRAIT -> PlayerOrientation.LOCKED_LANDSCAPE
            PlayerOrientation.LOCKED_LANDSCAPE -> PlayerOrientation.LOCKED_PORTRAIT
            PlayerOrientation.LOCKED_PORTRAIT -> PlayerOrientation.SYSTEM
            PlayerOrientation.SYSTEM -> PlayerOrientation.VIDEO
        }
    }

    private fun isPortrait(format: Format): Boolean {
        val rotation = format.rotationDegrees
        val isRotated = rotation == 90 || rotation == 270
        
        return if (isRotated) {
            format.width > format.height
        } else {
            format.height > format.width
        }
    }

    private fun isRotated(format: Format): Boolean {
        val rotation = format.rotationDegrees
        return rotation == 90 || rotation == 270
    }

    private fun getRational(format: Format): Rational {
        val rotation = format.rotationDegrees
        val isRotated = rotation == 90 || rotation == 270
        
        return if (isRotated) {
            Rational(format.height, format.width)
        } else {
            Rational(format.width, format.height)
        }
    }

    private fun setupRotationButton() {
        // Rotation button setup - add btn_rotation to your layout to enable this feature
        // For now, orientation features work but manual rotation button is not visible
        updateButtonRotation()
    }

    private fun updateButtonRotation() {
        // Rotation button icon update - will work when btn_rotation is added to layout
        // For now, orientation changes work automatically based on video aspect ratio
    }

    private fun setSubtitleTextSize(orientation: Int = resources.configuration.orientation) {
        val subtitleView = binding.playerView.subtitleView ?: return
        
        val textSize = if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            16f
        } else {
            20f
        }
        
        subtitleView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSize)
    }

    private fun updateSubtitleViewMargin(format: Format? = null) {
        val subtitleView = binding.playerView.subtitleView ?: return
        
        val marginBottom = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            resources.getDimensionPixelSize(R.dimen.subtitle_bottom_margin_portrait)
        } else {
            resources.getDimensionPixelSize(R.dimen.subtitle_bottom_margin_landscape)
        }
        
        val params = subtitleView.layoutParams as? android.view.ViewGroup.MarginLayoutParams
        params?.bottomMargin = marginBottom
        subtitleView.layoutParams = params
    }

    private fun setupWindowInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            binding.root.setOnApplyWindowInsetsListener { view, insets ->
                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                
                if (isLandscape) {
                    binding.playerContainer.setPadding(0, 0, 0, 0)
                } else {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
                        binding.playerContainer.setPadding(
                            systemBars.left,
                            systemBars.top,
                            systemBars.right,
                            0
                        )
                    } else {
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

    private fun parseIntent() {
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

        if (channelData != null) {
            contentType = ContentType.CHANNEL
            val channel = channelData!!
            contentId = channel.id
            contentName = channel.name

            if (channel.links != null && channel.links.isNotEmpty()) {
                allEventLinks = channel.links.map { 
                    LiveEventLink(label = it.quality, url = it.url) 
                }
                
                if (passedLinkIndex in allEventLinks.indices) {
                    currentLinkIndex = passedLinkIndex
                } else {
                    val matchIndex = allEventLinks.indexOfFirst { it.url == channel.streamUrl }
                    currentLinkIndex = if (matchIndex != -1) matchIndex else 0
                }
                
                streamUrl = allEventLinks[currentLinkIndex].url
            } else {
                streamUrl = channel.streamUrl
                allEventLinks = emptyList()
            }

        } else if (eventData != null) {
            contentType = ContentType.EVENT
            val event = eventData!!
            contentId = event.id
            contentName = event.title.ifEmpty { "${event.team1Name} vs ${event.team2Name}" }
            
            allEventLinks = event.links
            
            if (allEventLinks.isNotEmpty()) {
                currentLinkIndex = if (passedLinkIndex in allEventLinks.indices) passedLinkIndex else 0
                streamUrl = allEventLinks[currentLinkIndex].url
            } else {
                currentLinkIndex = 0
                streamUrl = ""
            }
        } else {
            finish()
            return
        }
    }

    private fun setWindowFlags(isLandscape: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(!isLandscape)
            if (isLandscape) {
                window.insetsController?.hide(WindowInsets.Type.systemBars())
            } else {
                window.insetsController?.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            if (isLandscape) {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
            } else {
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    private fun setupPlayer() {
        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSizeSd()
                    .build()
            )
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("LiveTVPro/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30000)
            .setReadTimeoutMs(30000)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector!!)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer
                
                val mediaItem = MediaItem.Builder()
                    .setUri(streamUrl)
                    .build()
                
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                
                playerListener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                videoLoading = false
                                binding.progressBar.visibility = View.GONE
                                binding.errorView.visibility = View.GONE
                                
                                // Handle video-based orientation when video is ready
                                if (currentOrientation == PlayerOrientation.VIDEO) {
                                    val format = exoPlayer.videoFormat
                                    if (format != null) {
                                        if (isPortrait(format)) {
                                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                        } else {
                                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                        }
                                        updateButtonRotation()
                                    }
                                }
                                
                                // Update subtitle margins based on video format
                                exoPlayer.videoFormat?.let { format ->
                                    updateSubtitleViewMargin(format)
                                }
                                
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode) {
                                    updatePipParams()
                                }
                            }
                            Player.STATE_BUFFERING -> {
                                if (!videoLoading) {
                                    binding.progressBar.visibility = View.VISIBLE
                                }
                            }
                            Player.STATE_ENDED -> {
                                binding.progressBar.visibility = View.GONE
                            }
                            Player.STATE_IDLE -> {
                                binding.progressBar.visibility = View.GONE
                            }
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        binding.progressBar.visibility = View.GONE
                        binding.errorView.visibility = View.VISIBLE
                        binding.errorText.text = "Playback error. Please try again."
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // Play/pause button state will be updated via PiP params
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode) {
                            updatePipParams()
                        }
                    }
                }
                
                exoPlayer.addListener(playerListener!!)
            }
    }

    private fun bindControllerViews() {
        // Controller views will be bound when you add them to your layout
        // For now, orientation features work automatically based on video aspect ratio
    }

    

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            binding.playerView.useController = false
            binding.lockOverlay.visibility = View.VISIBLE
            showUnlockButton()
        } else {
            binding.playerView.useController = true
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            binding.playerView.showController()
        }
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
        }
    }

    private fun toggleFullscreen() {
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (isPortrait) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    private fun cycleResizeMode() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        
        binding.playerView.resizeMode = currentResizeMode
        
        val modeText = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
            else -> "Fit"
        }
        
        Toast.makeText(this, "Aspect Ratio: $modeText", Toast.LENGTH_SHORT).show()
    }

    private fun setupLockOverlay() {
        binding.lockOverlay.setOnClickListener {
            showUnlockButton()
        }
        
        binding.unlockButton.setOnClickListener {
            isLocked = false
            binding.playerView.useController = true
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            binding.playerView.showController()
        }
    }

    private fun showUnlockButton() {
        binding.unlockButton.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        mainHandler.postDelayed(hideUnlockButtonRunnable, 3000)
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { relatedItem ->
            when (contentType) {
                ContentType.CHANNEL -> switchToChannel(relatedItem)
                ContentType.EVENT -> switchToEvent(relatedItem)
            }
        }
        val recyclerView = binding.relatedChannelsRecycler
        
        recyclerView.layoutManager = if (contentType == ContentType.EVENT) {
            LinearLayoutManager(this)  
        } else {
            GridLayoutManager(this, 3)  
        }
        recyclerView.adapter = relatedChannelsAdapter
    }
    
    private fun setupLinksUI() {
        linkChipAdapter = LinkChipAdapter { link, position ->
            switchToLink(link, position)
        }

        val portraitLinksRecycler = binding.linksRecyclerView
        portraitLinksRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        portraitLinksRecycler.adapter = linkChipAdapter

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        updateLinksForOrientation(isLandscape)
    }

    private fun updateLinksForOrientation(isLandscape: Boolean) {
        if (allEventLinks.size > 1) {
            linkChipAdapter.submitList(allEventLinks)
            linkChipAdapter.setSelectedPosition(currentLinkIndex)
            
            if (isLandscape) {
                binding.linksSection.visibility = View.GONE
                // Landscape links recycler in player controller not yet implemented
            } else {
                binding.linksSection.visibility = View.VISIBLE
            }
        } else {
            binding.linksSection.visibility = View.GONE
        }
    }

    private fun switchToLink(link: LiveEventLink, position: Int) {
        if (position != currentLinkIndex) {
            currentLinkIndex = position
            streamUrl = link.url
            
            binding.progressBar.visibility = View.VISIBLE
            player?.release()
            player = null
            setupPlayer()
        }
    }

    private fun switchToChannel(channel: Channel) {
        releasePlayer()
        PlayerActivity.startWithChannel(this, channel)
        finish()
    }

    private fun switchToEvent(channel: Channel) {
        // Convert Channel to LiveEvent if needed for your use case
        releasePlayer()
        PlayerActivity.startWithChannel(this, channel)
        finish()
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
        }

        // Observe the correct LiveData from your ViewModel
        viewModel.relatedItems.observe(this) { items ->
            if (items.isNotEmpty()) {
                // Filter out the current playing item to avoid duplicates
                relatedChannels = items.filter { it.id != contentId }
                relatedChannelsAdapter.submitList(relatedChannels)

                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                if (!isLandscape && relatedChannels.isNotEmpty()) {
                    binding.relatedChannelsSection.visibility = View.VISIBLE
                }
            } else {
                binding.relatedChannelsSection.visibility = View.GONE
            }
        }
    }


    private fun releasePlayer() {
        player?.let {
            playerListener?.let { listener ->
                it.removeListener(listener)
            }
            it.release()
        }
        player = null
        trackSelector = null
        playerListener = null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        if (!isPiPSupported()) return
        
        try {
            val currentPlayer = player ?: return
            val format = currentPlayer.videoFormat
            
            val paramsBuilder = PictureInPictureParams.Builder()
            
            // Set aspect ratio with rotation compensation
            if (format != null) {
                val rational = getRational(format)
                
                // Clamp aspect ratio to supported range
                val minRatio = Rational(1, 2) // 1:2 (portrait)
                val maxRatio = Rational(2, 1) // 2:1 (landscape)
                
                val clampedRational = when {
                    rational.toFloat() < minRatio.toFloat() -> minRatio
                    rational.toFloat() > maxRatio.toFloat() -> maxRatio
                    else -> rational
                }
                
                paramsBuilder.setAspectRatio(clampedRational)
            } else {
                // Default to 16:9
                paramsBuilder.setAspectRatio(Rational(16, 9))
            }
            
            // Add PiP actions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                paramsBuilder.setActions(buildPipActions())
            }
            
            // Expanded PiP support for ultra-wide/tall videos (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && format != null) {
                paramsBuilder.setExpandedAspectRatio(getRational(format))
            }
            
            // Scale subtitles for PiP
            val subtitleView = binding.playerView.subtitleView
            subtitleView?.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            
            enterPictureInPictureMode(paramsBuilder.build())
        } catch (e: IllegalStateException) {
            // PiP not supported or error occurred
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipParams() {
        if (!isInPipMode || !isPiPSupported()) return
        
        try {
            val currentPlayer = player ?: return
            val format = currentPlayer.videoFormat
            
            val paramsBuilder = PictureInPictureParams.Builder()
            
            // Set aspect ratio
            if (format != null) {
                val rational = getRational(format)
                
                // Clamp aspect ratio
                val minRatio = Rational(1, 2)
                val maxRatio = Rational(2, 1)
                
                val clampedRational = when {
                    rational.toFloat() < minRatio.toFloat() -> minRatio
                    rational.toFloat() > maxRatio.toFloat() -> maxRatio
                    else -> rational
                }
                
                paramsBuilder.setAspectRatio(clampedRational)
            }
            
            // Update actions
            paramsBuilder.setActions(buildPipActions())
            
            setPictureInPictureParams(paramsBuilder.build())
        } catch (e: IllegalStateException) {
            // Ignore errors
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipActions(): ArrayList<RemoteAction> {
        val actions = ArrayList<RemoteAction>()
        
        // Rewind button
        val rewindIntent = PendingIntent.getBroadcast(
            this,
            CONTROL_TYPE_REWIND,
            Intent(ACTION_MEDIA_CONTROL)
                .setPackage(packageName)
                .putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_REWIND),
            PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_skip_back),
            "Rewind",
            "Rewind 10s",
            rewindIntent
        ))
        
        // Play/Pause button (dynamic based on playback state)
        if (player?.isPlaying == true) {
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
                "Pause",
                "Pause",
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
                "Play",
                "Play",
                playIntent
            ))
        }
        
        // Forward button
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

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        isInPipMode = isInPictureInPictureMode
        
        if (isInPipMode) {
            // Entering PiP mode
            binding.playerView.useController = false 
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            binding.playerView.hideController()
            
            // Scale subtitles for PiP
            val subtitleView = binding.playerView.subtitleView
            subtitleView?.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        } else {
            // Exiting PiP mode
            userRequestedPip = false
            
            if (isFinishing) {
                return
            }
            
            // Restore subtitle text size
            setSubtitleTextSize()
            
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            
            setWindowFlags(isLandscape)
            
            // Show sections in portrait only
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
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPipMode && player?.isPlaying == true && isPiPSupported()) {
            userRequestedPip = true
            wasLockedBeforePip = isLocked
            enterPipMode()
        }
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            updatePipParams()
                        }
                    }
                    CONTROL_TYPE_PAUSE -> {
                        if (hasError || hasEnded) {
                            retryPlayback()
                        } else {
                            currentPlayer.pause()
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            updatePipParams()
                        }
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
