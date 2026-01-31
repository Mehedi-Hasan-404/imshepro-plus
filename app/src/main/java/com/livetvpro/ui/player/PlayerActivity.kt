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
    private var startedInLandscape = false

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
        
        // Track if we started in landscape mode
        startedInLandscape = isLandscape
        
        // If started in landscape, lock to landscape only
        if (startedInLandscape) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        
        setWindowFlags(isLandscape)
        setupWindowInsets()
        
        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        if (isLandscape) {
            params.dimensionRatio = null
            params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        } else {
            params.dimensionRatio = "H,16:9"  // Use H,16:9 format for consistency
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
        
        binding.playerView.postDelayed({
            bindControllerViews()
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
                    linkChipAdapter.submitList(allEventLinks)
                    if (allEventLinks.size > 1) {
                        binding.linksSection.visibility = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) View.VISIBLE else View.GONE
                    }
                }
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

        val selectedLinkIndex = intent.getIntExtra(EXTRA_SELECTED_LINK_INDEX, -1)

        when {
            channelData != null -> {
                contentType = ContentType.CHANNEL
                val channel = channelData!!
                contentId = channel.id
                contentName = channel.name
                
                allEventLinks = channel.links?.map { 
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
            eventData != null -> {
                contentType = ContentType.EVENT
                val event = eventData!!
                contentId = event.id
                contentName = event.name
                
                allEventLinks = event.links?.map { 
                    LiveEventLink(label = it.label, url = it.url) 
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
        }
    }

    private fun setupPlayer() {
        trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setPreferredTextLanguage("en")
                .build()
        }
        
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0")
            .setAllowCrossProtocolRedirects(true)
        
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)
        
        if (streamUrl.contains("drm", ignoreCase = true) || 
            streamUrl.contains("widevine", ignoreCase = true) ||
            streamUrl.contains(".mpd", ignoreCase = true)) {
            
            val drmCallback = HttpMediaDrmCallback(
                streamUrl,
                httpDataSourceFactory
            )
            
            try {
                val drmSessionManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(
                        C.WIDEVINE_UUID,
                        FrameworkMediaDrm.DEFAULT_PROVIDER
                    )
                    .build(drmCallback)
                
                mediaSourceFactory.setDrmSessionManagerProvider { drmSessionManager }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector!!)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer
                
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
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode) {
                                    updatePipParams()
                                }
                            }
                            Player.STATE_ENDED -> {
                                binding.progressBar.visibility = View.GONE
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode) {
                                    updatePipParams()
                                }
                            }
                            Player.STATE_IDLE -> {
                                binding.progressBar.visibility = View.GONE
                            }
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        binding.progressBar.visibility = View.GONE
                        binding.errorView.visibility = View.VISIBLE
                        binding.errorText.text = getString(R.string.playback_error)
                        binding.retryButton.setOnClickListener {
                            retryPlayback()
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode) {
                            updatePipParams()
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode) {
                            updatePipParams()
                        }
                        updatePlayPauseButton()
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            setSubtitleTextSize()
                        }
                    }
                }
                
                exoPlayer.addListener(playerListener!!)
                
                val mediaItem = MediaItem.fromUri(streamUrl)
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
    }

    private fun bindControllerViews() {
        val playerView = binding.playerView
        
        btnBack = playerView.findViewById(R.id.btnBack)
        btnPip = playerView.findViewById(R.id.btnPip)
        btnSettings = playerView.findViewById(R.id.btnSettings)
        btnLock = playerView.findViewById(R.id.btnLock)
        btnMute = playerView.findViewById(R.id.btnMute)
        btnRewind = playerView.findViewById(R.id.btnRewind)
        btnPlayPause = playerView.findViewById(R.id.btnPlayPause)
        btnForward = playerView.findViewById(R.id.btnForward)
        btnFullscreen = playerView.findViewById(R.id.btnFullscreen)
        btnAspectRatio = playerView.findViewById(R.id.btnAspectRatio)
        tvChannelName = playerView.findViewById(R.id.tvChannelName)
        
        tvChannelName?.text = contentName
        
        btnBack?.setOnClickListener { 
            if (!isLocked) {
                finish()
            }
        }
        
        btnPip?.setOnClickListener {
            if (!isLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                userRequestedPip = true
                wasLockedBeforePip = isLocked
                enterPipMode()
            }
        }
        
        btnSettings?.setOnClickListener {
            if (!isLocked) {
                showTrackSelectionDialog()
            }
        }
        
        btnLock?.setOnClickListener {
            isLocked = true
            binding.playerView.useController = false
            binding.playerView.hideController()
            binding.lockOverlay.visibility = View.VISIBLE
            showUnlockButton()
        }
        
        btnMute?.setOnClickListener {
            if (!isLocked) {
                toggleMute()
            }
        }
        
        btnRewind?.setOnClickListener {
            if (!isLocked) {
                player?.let { p ->
                    val newPosition = p.currentPosition - skipMs
                    p.seekTo(if (newPosition < 0) 0 else newPosition)
                }
            }
        }
        
        btnPlayPause?.setOnClickListener {
            if (!isLocked) {
                player?.let { p ->
                    if (binding.errorView.visibility == View.VISIBLE || 
                        p.playbackState == Player.STATE_ENDED) {
                        retryPlayback()
                    } else {
                        if (p.isPlaying) p.pause() else p.play()
                    }
                }
            }
        }
        
        btnForward?.setOnClickListener {
            if (!isLocked) {
                player?.let { p ->
                    val newPosition = p.currentPosition + skipMs
                    if (p.isCurrentWindowLive && p.duration != C.TIME_UNSET && newPosition >= p.duration) {
                        p.seekTo(p.duration)
                    } else {
                        p.seekTo(newPosition)
                    }
                }
            }
        }
        
        btnFullscreen?.setOnClickListener {
            if (!isLocked) {
                toggleOrientation()
            }
        }
        
        btnAspectRatio?.setOnClickListener {
            if (!isLocked) {
                cycleResizeMode()
            }
        }
        
        updatePlayPauseButton()
        updateMuteButton()
        updateFullscreenButton()
    }

    private fun applyOrientationSettings(isLandscape: Boolean) {
        btnPip?.visibility = if (isLandscape && 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && 
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            View.VISIBLE
        } else {
            View.GONE
        }
        
        btnLock?.visibility = if (isLandscape) View.VISIBLE else View.GONE
        btnFullscreen?.visibility = if (!startedInLandscape) View.VISIBLE else View.GONE
        
        if (isLandscape) {
            binding.linksSection.visibility = View.GONE
            binding.relatedChannelsSection.visibility = View.GONE
        } else {
            if (allEventLinks.size > 1) {
                binding.linksSection.visibility = View.VISIBLE
            }
            if (relatedChannels.isNotEmpty()) {
                binding.relatedChannelsSection.visibility = View.VISIBLE
            }
        }
    }

    private fun configurePlayerInteractions() {
        binding.playerView.setOnClickListener {
            if (isLocked) {
                showUnlockButton()
            }
        }
        
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) {
                    updatePlayPauseButton()
                    updateMuteButton()
                    updateFullscreenButton()
                }
            }
        )
    }

    private fun setupLockOverlay() {
        binding.unlockButton.setOnClickListener {
            isLocked = false
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            binding.playerView.useController = true
            binding.playerView.showController()
            mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        }
    }

    private fun showUnlockButton() {
        binding.unlockButton.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        mainHandler.postDelayed(hideUnlockButtonRunnable, 3000)
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { channel ->
            if (!isLocked) {
                releasePlayer()
                channelData = channel
                contentType = ContentType.CHANNEL
                contentId = channel.id
                contentName = channel.name
                
                allEventLinks = channel.links?.map { 
                    LiveEventLink(label = it.quality, url = it.url) 
                } ?: emptyList()
                
                currentLinkIndex = 0
                streamUrl = if (allEventLinks.isNotEmpty()) {
                    allEventLinks[0].url
                } else {
                    ""
                }
                
                linkChipAdapter.submitList(allEventLinks)
                tvChannelName?.text = contentName
                
                binding.progressBar.visibility = View.VISIBLE
                setupPlayer()
                
                loadRelatedContent()
            }
        }
        
        binding.relatedChannelsRecyclerView.apply {
            layoutManager = GridLayoutManager(this@PlayerActivity, 2)
            adapter = relatedChannelsAdapter
        }
    }

    private fun setupLinksUI() {
        linkChipAdapter = LinkChipAdapter(
            onLinkClick = { link, position ->
                if (!isLocked && position != currentLinkIndex) {
                    currentLinkIndex = position
                    streamUrl = link.url
                    
                    player?.let { p ->
                        val currentPosition = p.currentPosition
                        val wasPlaying = p.isPlaying
                        
                        releasePlayer()
                        setupPlayer()
                        
                        player?.let { newPlayer ->
                            if (currentPosition > 0) {
                                newPlayer.seekTo(currentPosition)
                            }
                            if (wasPlaying) {
                                newPlayer.play()
                            }
                        }
                    }
                }
            },
            getCurrentIndex = { currentLinkIndex }
        )
        
        binding.linksRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = linkChipAdapter
        }
        
        linkChipAdapter.submitList(allEventLinks)
        
        if (allEventLinks.size > 1) {
            binding.linksSection.visibility = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                View.VISIBLE
            } else {
                View.GONE
            }
        } else {
            binding.linksSection.visibility = View.GONE
        }
    }

    private fun loadRelatedContent() {
        if (contentType == ContentType.CHANNEL && contentId.isNotEmpty()) {
            lifecycleScope.launch {
                try {
                    viewModel.loadRelatedChannels(contentId)
                    viewModel.relatedChannels.observe(this@PlayerActivity) { channels ->
                        relatedChannels = channels
                        relatedChannelsAdapter.submitList(channels)
                        if (channels.isNotEmpty() && resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                            binding.relatedChannelsSection.visibility = View.VISIBLE
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun toggleMute() {
        player?.let { p ->
            isMuted = !isMuted
            p.volume = if (isMuted) 0f else 1f
            updateMuteButton()
        }
    }

    private fun updateMuteButton() {
        btnMute?.setImageResource(
            if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
    }

    private fun updatePlayPauseButton() {
        player?.let { p ->
            val hasError = binding.errorView.visibility == View.VISIBLE
            val hasEnded = p.playbackState == Player.STATE_ENDED
            
            btnPlayPause?.setImageResource(
                if (hasError || hasEnded || !p.isPlaying) {
                    R.drawable.ic_play
                } else {
                    R.drawable.ic_pause
                }
            )
        }
    }

    private fun updateFullscreenButton() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        btnFullscreen?.setImageResource(
            if (isLandscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
    }

    private fun toggleOrientation() {
        if (startedInLandscape) {
            return
        }
        
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun cycleResizeMode() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        binding.playerView.resizeMode = currentResizeMode
    }

    private fun showTrackSelectionDialog() {
        trackSelector?.let { selector ->
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            dialog.setTitle(R.string.track_selection)
            
            val items = arrayOf(
                getString(R.string.quality_auto),
                getString(R.string.subtitles)
            )
            
            dialog.setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        selector.parameters = selector.buildUponParameters()
                            .clearVideoSizeConstraints()
                            .setMaxVideoSizeSd()
                            .build()
                    }
                    1 -> {
                        // Handle subtitle selection
                    }
                }
            }
            
            dialog.show()
        }
    }

    private fun setSubtitleTextSize() {
        val subtitleView = binding.playerView.subtitleView
        subtitleView?.apply {
            setFixedTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION, SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
        }
    }

    private fun setWindowFlags(isLandscape: Boolean) {
        if (isLandscape) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.hide(WindowInsets.Type.systemBars())
                window.insetsController?.systemBarsBehavior = 
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(WindowInsets.Type.systemBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    private fun setupWindowInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
    }

    private fun resetPlayerContainerLayout(isLandscape: Boolean) {
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
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        if (isInPipMode) {
            return
        }
        
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        setWindowFlags(isLandscape)
        resetPlayerContainerLayout(isLandscape)
        
        binding.playerView.post {
            bindControllerViews()
            applyOrientationSettings(isLandscape)
            setSubtitleTextSize()
        }
        
        if (isLandscape) {
            binding.linksSection.visibility = View.GONE
            binding.relatedChannelsSection.visibility = View.GONE
        } else {
            if (allEventLinks.size > 1) {
                binding.linksSection.visibility = View.VISIBLE
            }
            if (relatedChannels.isNotEmpty()) {
                binding.relatedChannelsSection.visibility = View.VISIBLE
            }
        }
    }

    private fun releasePlayer() {
        player?.let { p ->
            playerListener?.let { p.removeListener(it) }
            p.release()
        }
        player = null
        playerListener = null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setActions(createPipActions())
                .build()
            
            try {
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipParams() {
        if (isInPipMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setActions(createPipActions())
                .build()
            
            try {
                setPictureInPictureParams(params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
            Icon.createWithResource(this, R.drawable.ic_skip_back),
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
            // Entering PiP mode
            binding.playerView.useController = false 
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            binding.playerView.hideController()
            
            // Register PiP broadcast receiver
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
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) updatePipParams()
                        }
                        CONTROL_TYPE_PAUSE -> {
                            if (hasError || hasEnded) {
                                retryPlayback()
                            } else {
                                currentPlayer.pause()
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) updatePipParams()
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
        } else {
            // Exiting PiP mode
            userRequestedPip = false
            
            // Unregister PiP receiver
            pipReceiver?.let {
                try {
                    unregisterReceiver(it)
                } catch (e: Exception) {
                    // Ignore
                }
                pipReceiver = null
            }
            
            if (isFinishing) {
                return
            }
            
            setSubtitleTextSize()
            
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            resetPlayerContainerLayout(isLandscape)
            
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
            // Clean up PiP receiver
            pipReceiver?.let {
                try {
                    unregisterReceiver(it)
                } catch (e: Exception) {
                    // Ignore
                }
                pipReceiver = null
            }
            isInPipMode = false
            userRequestedPip = false
            wasLockedBeforePip = false
            super.finish()
        } catch (e: Exception) {
            super.finish()
        }
    }
}
