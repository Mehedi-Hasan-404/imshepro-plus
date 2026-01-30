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
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Apply immersive mode early
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Ensure initial orientation settings are applied immediately
        val currentOrientation = resources.configuration.orientation
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        setWindowFlags(isLandscape)
        
        // Setup window insets listener to respect cutouts in portrait mode
        setupWindowInsets()

        parseIntent()

        if (contentType == ContentType.CHANNEL && contentId.isNotEmpty()) {
            viewModel.refreshChannelData(contentId)
        }

        binding.progressBar.visibility = View.VISIBLE
        
        setupPlayer()
        
        binding.playerView.postDelayed({
            bindControllerViews()
            
            val currentOrientation = resources.configuration.orientation
            val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
            applyOrientationSettings(isLandscape)
        }, 100)

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
    
    private fun setupWindowInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            binding.root.setOnApplyWindowInsetsListener { view, insets ->
                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                
                if (isLandscape) {
                    view.setPadding(0, 0, 0, 0)
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val windowInsets = insets.getInsets(WindowInsets.Type.systemBars())
                        view.setPadding(0, windowInsets.top, 0, 0)
                    } else {
                        @Suppress("DEPRECATION")
                        view.setPadding(0, insets.systemWindowInsetTop, 0, 0)
                    }
                }
                insets
            }
        }
    }

    private fun parseIntent() {
        val receivedChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CHANNEL)
        }

        val receivedEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_EVENT, LiveEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_EVENT)
        }

        val selectedLinkIndex = intent.getIntExtra(EXTRA_SELECTED_LINK_INDEX, -1)

        when {
            receivedChannel != null -> {
                contentType = ContentType.CHANNEL
                channelData = receivedChannel
                contentId = receivedChannel.id
                contentName = receivedChannel.name

                allEventLinks = receivedChannel.links?.map { 
                    LiveEventLink(label = it.quality, url = it.url) 
                } ?: emptyList()

                currentLinkIndex = if (selectedLinkIndex >= 0 && selectedLinkIndex < allEventLinks.size) {
                    selectedLinkIndex
                } else {
                    0
                }

                streamUrl = if (allEventLinks.isNotEmpty()) {
                    allEventLinks[currentLinkIndex].url
                } else {
                    ""
                }
            }
            receivedEvent != null -> {
                contentType = ContentType.EVENT
                eventData = receivedEvent
                contentId = receivedEvent.id
                contentName = receivedEvent.title

                allEventLinks = receivedEvent.links

                currentLinkIndex = if (selectedLinkIndex >= 0 && selectedLinkIndex < allEventLinks.size) {
                    selectedLinkIndex
                } else {
                    0
                }

                streamUrl = if (allEventLinks.isNotEmpty()) {
                    allEventLinks[currentLinkIndex].url
                } else {
                    ""
                }
            }
            else -> {
                finish()
                return
            }
        }
    }

    private fun setupPlayer() {
        if (streamUrl.isEmpty()) {
            binding.progressBar.visibility = View.GONE
            binding.errorView.visibility = View.VISIBLE
            binding.errorText.text = "No stream URL available"
            return
        }

        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setMaxVideoSizeSd())
        }

        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .build()

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector!!)
            .build()
            .apply {
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }

        binding.playerView.player = player

        playerListener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
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
                    Player.STATE_IDLE -> {
                        // Do nothing
                    }
                }
                
                if (isInPipMode) {
                    updatePipParams()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                binding.progressBar.visibility = View.GONE
                binding.errorView.visibility = View.VISIBLE
                binding.errorText.text = "Playback error: ${error.message}"
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isInPipMode) {
                    updatePipParams()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                if (!isLandscape && videoSize.width > 0 && videoSize.height > 0) {
                    val aspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                    val layoutParams = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
                    layoutParams.dimensionRatio = "H,$aspectRatio:1"
                    binding.playerContainer.layoutParams = layoutParams
                }
            }
        }

        player?.addListener(playerListener!!)
        
        setSubtitleTextSize()
    }

    private fun setSubtitleTextSize() {
        val subtitleView = binding.playerView.subtitleView
        if (subtitleView != null) {
            val textSizePx = resources.getDimensionPixelSize(R.dimen.subtitle_text_size)
            subtitleView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx.toFloat())
            subtitleView.setApplyEmbeddedStyles(true)
            subtitleView.setStyle(
                androidx.media3.ui.CaptionStyleCompat(
                    android.graphics.Color.WHITE,
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                    androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    android.graphics.Color.BLACK,
                    null
                )
            )
        }
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
                if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                    userRequestedPip = true
                    wasLockedBeforePip = isLocked
                    enterPipMode()
                }
            }
        }

        btnSettings?.setOnClickListener {
            showTrackSelectionDialog()
        }

        btnLock?.setOnClickListener {
            toggleLock()
        }

        btnMute?.setOnClickListener {
            toggleMute()
        }

        btnFullscreen?.setOnClickListener {
            toggleOrientation()
        }

        btnAspectRatio?.setOnClickListener {
            cycleResizeMode()
        }

        val currentOrientation = resources.configuration.orientation
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        
        btnPip?.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && 
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            View.VISIBLE
        } else {
            View.GONE
        }

        btnFullscreen?.setImageResource(
            if (isLandscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
    }

    private fun applyOrientationSettings(isLandscape: Boolean) {
        if (isLandscape) {
            binding.relatedChannelsSection.visibility = View.GONE
            binding.linksSection.visibility = View.GONE
            
            val layoutParams = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
            layoutParams.dimensionRatio = null
            layoutParams.height = ConstraintLayout.LayoutParams.MATCH_PARENT
            layoutParams.width = ConstraintLayout.LayoutParams.MATCH_PARENT
            binding.playerContainer.layoutParams = layoutParams
        } else {
            if (allEventLinks.size > 1) {
                binding.linksSection.visibility = View.VISIBLE
            }
            if (relatedChannels.isNotEmpty()) {
                binding.relatedChannelsSection.visibility = View.VISIBLE
            }
            
            val player = player
            if (player != null && player.videoSize.width > 0 && player.videoSize.height > 0) {
                val aspectRatio = player.videoSize.width.toFloat() / player.videoSize.height.toFloat()
                val layoutParams = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
                layoutParams.dimensionRatio = "H,$aspectRatio:1"
                layoutParams.height = ConstraintLayout.LayoutParams.WRAP_CONTENT
                layoutParams.width = ConstraintLayout.LayoutParams.MATCH_PARENT
                binding.playerContainer.layoutParams = layoutParams
            } else {
                val layoutParams = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
                layoutParams.dimensionRatio = "16:9"
                layoutParams.height = ConstraintLayout.LayoutParams.WRAP_CONTENT
                layoutParams.width = ConstraintLayout.LayoutParams.MATCH_PARENT
                binding.playerContainer.layoutParams = layoutParams
            }
        }

        btnFullscreen?.setImageResource(
            if (isLandscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
        
        updateLinksForOrientation(isLandscape)
    }

    private fun updateLinksForOrientation(isLandscape: Boolean) {
        if (isLandscape || allEventLinks.size <= 1) {
            binding.linksSection.visibility = View.GONE
        } else {
            binding.linksSection.visibility = View.VISIBLE
            linkChipAdapter.updateLinks(allEventLinks, currentLinkIndex)
        }
    }

    private fun setWindowFlags(isLandscape: Boolean) {
        if (isLandscape) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior = 
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.show(WindowInsets.Type.systemBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        setWindowFlags(isLandscape)
        applyOrientationSettings(isLandscape)
        
        binding.playerView.postDelayed({
            btnFullscreen?.setImageResource(
                if (isLandscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
            )
        }, 100)
    }

    private fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun configurePlayerInteractions() {
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE && isLocked) {
                    binding.playerView.hideController()
                }
            }
        )
    }

    private fun setupLockOverlay() {
        binding.lockOverlay.setOnClickListener {
            if (isLocked) {
                showUnlockButton()
            }
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
            mainHandler.removeCallbacks(hideUnlockButtonRunnable)
            
            binding.playerView.postDelayed({
                binding.playerView.showController()
            }, 100)
        }

        btnLock?.setImageResource(
            if (isLocked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open
        )
    }

    private fun showUnlockButton() {
        binding.unlockButton.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        mainHandler.postDelayed(hideUnlockButtonRunnable, 3000)
    }

    private fun toggleMute() {
        isMuted = !isMuted
        player?.volume = if (isMuted) 0f else 1f
        btnMute?.setImageResource(
            if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
    }

    private fun cycleResizeMode() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        binding.playerView.resizeMode = currentResizeMode

        val resizeText = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
            else -> "Fit"
        }
        
        android.widget.Toast.makeText(this, "Resize: $resizeText", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showTrackSelectionDialog() {
        val trackSelector = trackSelector ?: return
        
        val mappedTrackInfo = trackSelector.currentMappedTrackInfo ?: return
        
        val rendererCount = mappedTrackInfo.rendererCount
        val trackTypes = mutableListOf<String>()
        
        for (i in 0 until rendererCount) {
            val trackGroups = mappedTrackInfo.getTrackGroups(i)
            if (trackGroups.length > 0) {
                when (mappedTrackInfo.getRendererType(i)) {
                    C.TRACK_TYPE_VIDEO -> trackTypes.add("Video Quality")
                    C.TRACK_TYPE_AUDIO -> trackTypes.add("Audio")
                    C.TRACK_TYPE_TEXT -> trackTypes.add("Subtitles")
                }
            }
        }
        
        if (trackTypes.isEmpty()) {
            android.widget.Toast.makeText(this, "No tracks available", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Track Selection")
            .setItems(trackTypes.toTypedArray()) { _, which ->
                // Handle track selection
            }
            .show()
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter(
            onChannelClick = { channel ->
                if (channel.id != contentId) {
                    releasePlayer()
                    
                    contentType = ContentType.CHANNEL
                    channelData = channel
                    contentId = channel.id
                    contentName = channel.name
                    
                    allEventLinks = channel.links?.map { 
                        LiveEventLink(label = it.quality, url = it.url) 
                    } ?: emptyList()
                    
                    currentLinkIndex = 0
                    streamUrl = if (allEventLinks.isNotEmpty()) {
                        allEventLinks[currentLinkIndex].url
                    } else {
                        ""
                    }
                    
                    binding.progressBar.visibility = View.VISIBLE
                    setupPlayer()
                    
                    binding.playerView.postDelayed({
                        bindControllerViews()
                        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        updateLinksForOrientation(isLandscape)
                    }, 100)
                    
                    viewModel.refreshChannelData(channel.id)
                    loadRelatedContent()
                }
            }
        )
        
        binding.relatedChannelsRecycler.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = relatedChannelsAdapter
        }
    }

    private fun setupLinksUI() {
        linkChipAdapter = LinkChipAdapter(
            links = allEventLinks,
            currentIndex = currentLinkIndex,
            onLinkClick = { clickedIndex ->
                if (clickedIndex != currentLinkIndex && clickedIndex in allEventLinks.indices) {
                    val previousIndex = currentLinkIndex
                    currentLinkIndex = clickedIndex
                    streamUrl = allEventLinks[currentLinkIndex].url
                    
                    linkChipAdapter.updateLinks(allEventLinks, currentLinkIndex)
                    
                    val currentPosition = player?.currentPosition ?: 0L
                    
                    releasePlayer()
                    
                    binding.progressBar.visibility = View.VISIBLE
                    setupPlayer()
                    
                    player?.seekTo(currentPosition)
                }
            }
        )
        
        binding.linksRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = linkChipAdapter
        }
    }

    private fun loadRelatedContent() {
        when (contentType) {
            ContentType.CHANNEL -> {
                val channelId = contentId
                if (channelId.isNotEmpty()) {
                    binding.relatedLoadingProgress.visibility = View.VISIBLE
                    
                    lifecycleScope.launch {
                        viewModel.loadRelatedChannels(channelId)
                    }
                    
                    viewModel.relatedChannels.observe(this) { channels ->
                        binding.relatedLoadingProgress.visibility = View.GONE
                        
                        if (channels != null && channels.isNotEmpty()) {
                            relatedChannels = channels
                            relatedChannelsAdapter.submitList(channels)
                            
                            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                            if (!isLandscape) {
                                binding.relatedChannelsSection.visibility = View.VISIBLE
                            }
                        } else {
                            binding.relatedChannelsSection.visibility = View.GONE
                        }
                    }
                }
            }
            ContentType.EVENT -> {
                binding.relatedChannelsSection.visibility = View.GONE
            }
        }
    }

    private fun releasePlayer() {
        player?.removeListener(playerListener!!)
        player?.release()
        player = null
        trackSelector = null
    }

    override fun onStop() {
        super.onStop()
        if (isInPipMode) {
            // Keep playing in PIP
        } else {
            player?.pause()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        releasePlayer()
        unregisterPipReceiver()
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        val params = createPipParams()
        try {
            enterPictureInPictureMode(params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipParams() {
        if (!isInPipMode) return
        
        val params = createPipParams()
        setPictureInPictureParams(params)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createPipParams(): PictureInPictureParams {
        val aspectRatio = player?.videoSize?.let { videoSize ->
            if (videoSize.width > 0 && videoSize.height > 0) {
                Rational(videoSize.width, videoSize.height)
            } else {
                Rational(16, 9)
            }
        } ?: Rational(16, 9)

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(false)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setActions(createPipActions())
        }

        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createPipActions(): List<RemoteAction> {
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

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        isInPipMode = isInPictureInPictureMode
        
        if (isInPipMode) {
            // Keep RecyclerViews visible in background - DON'T hide them
            // binding.relatedChannelsSection.visibility = View.GONE  // REMOVED
            // binding.linksSection.visibility = View.GONE            // REMOVED
            
            binding.playerView.useController = false 
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            binding.playerView.hideController()
        } else {
            userRequestedPip = false
            
            if (isFinishing) {
                return
            }
            
            setSubtitleTextSize()
            
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            applyOrientationSettings(isLandscape)
            
            if (!isLandscape) {
                if (allEventLinks.size > 1) {
                    binding.linksSection.visibility = View.VISIBLE
                }
                if (relatedChannels.isNotEmpty()) {
                    binding.relatedChannelsSection.visibility = View.VISIBLE
                }
            }
            
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
        if (!isInPipMode && player?.isPlaying == true) {
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
