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
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        val currentOrientation = resources.configuration.orientation
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        
        setWindowFlags(isLandscape)
        setupWindowInsets()
        
        // Configure initial layout
        updatePlayerContainerLayout(isLandscape)

        parseIntent()

        if (contentType == ContentType.CHANNEL && contentId.isNotEmpty()) {
            viewModel.refreshChannelData(contentId)
        }

        binding.progressBar.visibility = View.VISIBLE
        
        setupPlayer()
        
        binding.playerView.post {
            bindControllerViews()
            // Set initial resize mode based on orientation
            if (isLandscape) {
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
            } else {
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
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
                    
                    if (currentLinkIndex >= allEventLinks.size) {
                        currentLinkIndex = 0
                    }
                    
                    linkChipAdapter.submitList(allEventLinks)
                    linkChipAdapter.setSelectedPosition(currentLinkIndex)
                    
                    if (allEventLinks.size > 1 && !isInFullscreen()) {
                        binding.linksSection.visibility = View.VISIBLE
                    }
                }
            }
        }
        
        updateLinkSectionVisibility()
    }
    
    /**
     * Updates the player container layout parameters based on orientation
     */
    private fun updatePlayerContainerLayout(isLandscape: Boolean) {
        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        
        if (isLandscape) {
            // Landscape: Fill entire screen
            params.dimensionRatio = null
            params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        } else {
            // Portrait: 16:9 aspect ratio at the top
            params.dimensionRatio = "H,16:9"
            params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.height = 0
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        }
        
        binding.playerContainer.layoutParams = params
        binding.playerContainer.requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        if (isInPipMode) {
            return
        }
        
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        // Update layout constraints
        updatePlayerContainerLayout(isLandscape)
        
        // Apply orientation settings
        applyOrientationSettings(isLandscape)
    }

    private fun applyOrientationSettings(isLandscape: Boolean) {
        setWindowFlags(isLandscape)
        
        if (isLandscape) {
            // Hide sections in landscape
            binding.linksSection.visibility = View.GONE
            binding.relatedChannelsSection.visibility = View.GONE
            
            // Update resize mode for landscape
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
            btnAspectRatio?.visibility = View.GONE
        } else {
            // Show sections in portrait if applicable
            updateLinkSectionVisibility()
            
            if (relatedChannels.isNotEmpty()) {
                binding.relatedChannelsSection.visibility = View.VISIBLE
            }
            
            // Update resize mode for portrait
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
            btnAspectRatio?.visibility = View.VISIBLE
        }
    }

    private fun parseIntent() {
        val selectedLinkIndex = intent.getIntExtra(EXTRA_SELECTED_LINK_INDEX, -1)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            channelData = intent.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
            eventData = intent.getParcelableExtra(EXTRA_EVENT, LiveEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            channelData = intent.getParcelableExtra(EXTRA_CHANNEL)
            @Suppress("DEPRECATION")
            eventData = intent.getParcelableExtra(EXTRA_EVENT)
        }
        
        when {
            channelData != null -> {
                contentType = ContentType.CHANNEL
                contentId = channelData?.id ?: ""
                contentName = channelData?.name ?: ""
                
                val links = channelData?.links ?: emptyList()
                if (links.isNotEmpty()) {
                    allEventLinks = links.map { LiveEventLink(label = it.quality, url = it.url) }
                    currentLinkIndex = if (selectedLinkIndex in allEventLinks.indices) {
                        selectedLinkIndex
                    } else {
                        0
                    }
                    streamUrl = allEventLinks[currentLinkIndex].url
                } else {
                    streamUrl = ""
                }
            }
            eventData != null -> {
                contentType = ContentType.EVENT
                contentId = eventData?.id ?: ""
                contentName = eventData?.title ?: ""
                
                val links = eventData?.links ?: emptyList()
                if (links.isNotEmpty()) {
                    allEventLinks = links
                    currentLinkIndex = if (selectedLinkIndex in allEventLinks.indices) {
                        selectedLinkIndex
                    } else {
                        0
                    }
                    streamUrl = allEventLinks[currentLinkIndex].url
                } else {
                    streamUrl = ""
                }
            }
        }
    }

    private fun setupPlayer() {
        if (streamUrl.isEmpty()) {
            showError("Stream URL is empty")
            return
        }

        trackSelector = DefaultTrackSelector(this)
        
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0")
            .setAllowCrossProtocolRedirects(true)

        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .build()

        val drmLicenseUrl = when(contentType) {
            ContentType.CHANNEL -> channelData?.drmLicenseUrl
            ContentType.EVENT -> eventData?.drmLicenseUrl
        }

        val mediaSourceFactory = if (!drmLicenseUrl.isNullOrEmpty()) {
            val drmCallback = HttpMediaDrmCallback(drmLicenseUrl, dataSourceFactory)
            val drmSessionManager = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID) { FrameworkMediaDrm.newInstance(C.WIDEVINE_UUID) }
                .build(drmCallback)
            
            DefaultMediaSourceFactory(this)
                .setDataSourceFactory(dataSourceFactory)
                .setDrmSessionManagerProvider { drmSessionManager }
        } else {
            DefaultMediaSourceFactory(this)
                .setDataSourceFactory(dataSourceFactory)
        }

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector!!)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        binding.playerView.player = player

        playerListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.errorView.visibility = View.GONE
                    }
                    Player.STATE_READY -> {
                        binding.progressBar.visibility = View.GONE
                        binding.errorView.visibility = View.GONE
                        if (isInPipMode) {
                            updatePipParams()
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
                showError("Playback error: ${error.message}")
                if (isInPipMode) {
                    updatePipParams()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isInPipMode) {
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
        player?.play()

        if (isMuted) {
            player?.volume = 0f
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerPipReceiver()
        }
    }

    private fun setSubtitleTextSize() {
        binding.playerView.post {
            val subtitleView = binding.playerView.subtitleView
            if (subtitleView != null) {
                val videoHeight = player?.videoSize?.height ?: 0
                
                val textSize = when {
                    videoHeight > 1080 -> 24f
                    videoHeight > 720 -> 20f
                    videoHeight > 480 -> 18f
                    else -> 16f
                }
                
                subtitleView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSize)
                subtitleView.setStyle(
                    androidx.media3.ui.CaptionStyleCompat(
                        android.graphics.Color.WHITE,
                        android.graphics.Color.BLACK,
                        android.graphics.Color.TRANSPARENT,
                        androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                        android.graphics.Color.BLACK,
                        null
                    )
                )
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

    private fun showError(message: String) {
        binding.errorView.visibility = View.VISIBLE
        binding.errorView.findViewById<TextView>(R.id.errorMessage)?.text = message
        binding.errorView.findViewById<View>(R.id.retryButton)?.setOnClickListener {
            retryPlayback()
        }
    }

    private fun bindControllerViews() {
        binding.playerView.findViewById<View>(R.id.exo_controller)?.let { controller ->
            btnBack = controller.findViewById(R.id.btn_back)
            btnPip = controller.findViewById(R.id.btn_pip)
            btnSettings = controller.findViewById(R.id.btn_settings)
            btnLock = controller.findViewById(R.id.btn_lock)
            btnMute = controller.findViewById(R.id.btn_mute)
            btnRewind = controller.findViewById(R.id.exo_rew)
            btnPlayPause = controller.findViewById(R.id.exo_play_pause)
            btnForward = controller.findViewById(R.id.exo_ffwd)
            btnFullscreen = controller.findViewById(R.id.btn_fullscreen)
            btnAspectRatio = controller.findViewById(R.id.btn_aspect_ratio)
            tvChannelName = controller.findViewById(R.id.tv_channel_name)
        }
    }

    private fun configurePlayerInteractions() {
        tvChannelName?.text = contentName

        btnBack?.setOnClickListener {
            finish()
        }

        btnPip?.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                userRequestedPip = true
                wasLockedBeforePip = isLocked
                enterPipMode()
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

        btnRewind?.setOnClickListener {
            val newPosition = (player?.currentPosition ?: 0) - skipMs
            player?.seekTo(if (newPosition < 0) 0 else newPosition)
        }

        btnForward?.setOnClickListener {
            val currentPlayer = player ?: return@setOnClickListener
            val newPosition = currentPlayer.currentPosition + skipMs
            
            if (currentPlayer.isCurrentWindowLive && currentPlayer.duration != C.TIME_UNSET && newPosition >= currentPlayer.duration) {
                currentPlayer.seekTo(currentPlayer.duration)
            } else {
                currentPlayer.seekTo(newPosition)
            }
        }

        btnFullscreen?.setOnClickListener {
            toggleFullscreen()
        }

        btnAspectRatio?.setOnClickListener {
            cycleResizeMode()
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            btnPip?.visibility = View.GONE
        }
    }

    private fun setupLockOverlay() {
        binding.unlockButton.setOnClickListener {
            toggleLock()
        }
        
        binding.lockOverlay.setOnClickListener {
            showUnlockButton()
        }
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { channel ->
            if (channel.id != contentId) {
                releasePlayer()
                channelData = channel
                contentType = ContentType.CHANNEL
                contentId = channel.id
                contentName = channel.name
                
                val links = channel.links ?: emptyList()
                if (links.isNotEmpty()) {
                    allEventLinks = links.map { LiveEventLink(label = it.quality, url = it.url) }
                    currentLinkIndex = 0
                    streamUrl = allEventLinks[currentLinkIndex].url
                    
                    linkChipAdapter.submitList(allEventLinks)
                    linkChipAdapter.setSelectedPosition(currentLinkIndex)
                } else {
                    streamUrl = ""
                    allEventLinks = emptyList()
                }
                
                updateLinkSectionVisibility()
                setupPlayer()
                tvChannelName?.text = contentName
            }
        }

        val layoutManager = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            GridLayoutManager(this, 2)
        } else {
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        }
        
        binding.relatedChannelsRecyclerView.layoutManager = layoutManager
        binding.relatedChannelsRecyclerView.adapter = relatedChannelsAdapter
    }

    private fun setupLinksUI() {
        linkChipAdapter = LinkChipAdapter(
            onLinkSelected = { link, position ->
                if (position != currentLinkIndex && position in allEventLinks.indices) {
                    currentLinkIndex = position
                    streamUrl = link.url
                    
                    releasePlayer()
                    setupPlayer()
                    
                    linkChipAdapter.setSelectedPosition(currentLinkIndex)
                }
            }
        )
        
        binding.linksRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.linksRecyclerView.adapter = linkChipAdapter
        
        if (allEventLinks.isNotEmpty()) {
            linkChipAdapter.submitList(allEventLinks)
            linkChipAdapter.setSelectedPosition(currentLinkIndex)
        }
    }

    private fun loadRelatedContent() {
        if (contentType == ContentType.CHANNEL) {
            lifecycleScope.launch {
                viewModel.relatedChannels.collect { channels ->
                    relatedChannels = channels.filter { it.id != contentId }
                    relatedChannelsAdapter.submitList(relatedChannels)
                    
                    if (relatedChannels.isNotEmpty() && !isInFullscreen()) {
                        binding.relatedChannelsSection.visibility = View.VISIBLE
                    } else {
                        binding.relatedChannelsSection.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun updateLinkSectionVisibility() {
        if (allEventLinks.size > 1 && !isInFullscreen()) {
            binding.linksSection.visibility = View.VISIBLE
        } else {
            binding.linksSection.visibility = View.GONE
        }
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
            mainHandler.removeCallbacks(hideUnlockButtonRunnable)
            
            binding.playerView.postDelayed({
                if (!isLocked) {
                    binding.playerView.showController()
                }
            }, 100)
        }
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

    private fun toggleFullscreen() {
        val isCurrentlyLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        requestedOrientation = if (isCurrentlyLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun isInFullscreen(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun cycleResizeMode() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> {
                btnAspectRatio?.setImageResource(R.drawable.ic_aspect_ratio_fill)
                AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> {
                btnAspectRatio?.setImageResource(R.drawable.ic_aspect_ratio_zoom)
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            else -> {
                btnAspectRatio?.setImageResource(R.drawable.ic_aspect_ratio_fit)
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }
        
        binding.playerView.resizeMode = currentResizeMode
    }

    private fun showTrackSelectionDialog() {
        val trackSelector = this.trackSelector ?: return
        
        val mappedTrackInfo = trackSelector.currentMappedTrackInfo ?: return
        
        val dialogFragment = androidx.media3.ui.TrackSelectionDialogBuilder(
            this,
            getString(R.string.select_tracks),
            player!!,
            C.TRACK_TYPE_VIDEO
        ).build()
        
        dialogFragment.show(supportFragmentManager, "track_selection")
    }

    private fun setWindowFlags(isLandscape: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (isLandscape) {
                window.insetsController?.hide(WindowInsets.Type.systemBars())
                window.insetsController?.systemBarsBehavior = 
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                window.insetsController?.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            if (isLandscape) {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
            } else {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
            }
        }
    }

    private fun setupWindowInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        val params = getPipParams()
        try {
            enterPictureInPictureMode(params)
        } catch (e: IllegalStateException) {
            // PIP not available
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getPipParams(): PictureInPictureParams {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val actions = createPipActions()
            builder.setActions(actions)
        }

        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipParams() {
        if (isInPipMode) {
            try {
                setPictureInPictureParams(getPipParams())
            } catch (e: IllegalStateException) {
                // Ignore
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun createPipActions(): ArrayList<RemoteAction> {
        val actions = ArrayList<RemoteAction>()
        
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
            
            // Update layout
            updatePlayerContainerLayout(isLandscape)
            
            // Apply orientation settings
            setWindowFlags(isLandscape)
            
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
