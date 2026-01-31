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
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.databinding.ActivityPlayerBinding
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import com.livetvpro.ui.adapters.LinkChipAdapter
import com.livetvpro.data.models.LiveEventLink
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

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
        private const val TAG = "LiveTVPro_PlayerActivity"
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
        
        enableEdgeToEdge()
        
        val currentOrientation = resources.configuration.orientation
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        
        setWindowFlags(isLandscape)
        setupWindowInsets()
        
        configurePlayerDimensions(isLandscape)

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

        setupBackPressHandler()
    }

    private fun enableEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    private fun setupWindowInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun configurePlayerDimensions(isLandscape: Boolean) {
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

    private fun setWindowFlags(isLandscape: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                if (isLandscape) {
                    controller.hide(WindowInsets.Type.systemBars())
                } else {
                    controller.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            if (isLandscape) {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            } else {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
            }
        }
    }

    private fun parseIntent() {
        val selectedLinkIndex = intent.getIntExtra(EXTRA_SELECTED_LINK_INDEX, -1)
        
        when {
            intent.hasExtra(EXTRA_CHANNEL) -> {
                contentType = ContentType.CHANNEL
                channelData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_CHANNEL)
                }
                
                channelData?.let { channel ->
                    contentId = channel.id
                    contentName = channel.name
                    
                    if (channel.links?.isNotEmpty() == true) {
                        allEventLinks = channel.links.map { 
                            LiveEventLink(label = it.quality, url = it.url) 
                        }
                        currentLinkIndex = if (selectedLinkIndex >= 0 && selectedLinkIndex < allEventLinks.size) {
                            selectedLinkIndex
                        } else {
                            0
                        }
                        streamUrl = allEventLinks[currentLinkIndex].url
                    }
                }
            }
            intent.hasExtra(EXTRA_EVENT) -> {
                contentType = ContentType.EVENT
                eventData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_EVENT, LiveEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_EVENT)
                }
                
                eventData?.let { event ->
                    contentId = event.id
                    contentName = event.title
                    
                    if (event.links?.isNotEmpty() == true) {
                        allEventLinks = event.links
                        currentLinkIndex = if (selectedLinkIndex >= 0 && selectedLinkIndex < allEventLinks.size) {
                            selectedLinkIndex
                        } else {
                            0
                        }
                        streamUrl = allEventLinks[currentLinkIndex].url
                    }
                }
            }
        }
        
        Log.d(TAG, "Parsed content: type=$contentType, name=$contentName, url=$streamUrl")
    }

    private fun setupPlayer() {
        if (streamUrl.isEmpty()) {
            showError(getString(R.string.network_error))
            return
        }

        try {
            trackSelector = DefaultTrackSelector(this).apply {
                setParameters(
                    buildUponParameters()
                        .setMaxVideoSizeSd()
                        .setPreferredAudioLanguage("en")
                )
            }

            player = ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector!!)
                .build()
                .also { exoPlayer ->
                    binding.playerView.player = exoPlayer
                    
                    playerListener = object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    binding.progressBar.visibility = View.GONE
                                    binding.errorView.visibility = View.GONE
                                    updatePipParams()
                                }
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar.visibility = View.VISIBLE
                                }
                                Player.STATE_ENDED -> {
                                    updatePipParams()
                                }
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            showError(getString(R.string.network_error))
                            updatePipParams()
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            updatePipParams()
                            btnPlayPause?.setImageResource(
                                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                            )
                        }

                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            updatePipParams()
                        }
                    }
                    
                    exoPlayer.addListener(playerListener!!)
                    
                    val mediaItem = MediaItem.fromUri(streamUrl)
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    exoPlayer.play()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up player", e)
            showError(getString(R.string.network_error))
        }
    }

    private fun bindControllerViews() {
        try {
            btnBack = binding.playerView.findViewById(R.id.exo_back)
            btnPip = binding.playerView.findViewById(R.id.exo_pip)
            btnSettings = binding.playerView.findViewById(R.id.exo_settings)
            btnLock = binding.playerView.findViewById(R.id.exo_lock)
            btnMute = binding.playerView.findViewById(R.id.exo_mute)
            btnRewind = binding.playerView.findViewById(R.id.exo_rew)
            btnPlayPause = binding.playerView.findViewById(R.id.exo_play_pause)
            btnForward = binding.playerView.findViewById(R.id.exo_ffwd)
            btnFullscreen = binding.playerView.findViewById(R.id.exo_fullscreen)
            btnAspectRatio = binding.playerView.findViewById(R.id.exo_aspect_ratio)
            tvChannelName = binding.playerView.findViewById(R.id.exo_channel_name)

            tvChannelName?.text = contentName

            setupControllerButtons()
        } catch (e: Exception) {
            Log.e(TAG, "Error binding controller views", e)
        }
    }

    private fun setupControllerButtons() {
        btnBack?.setOnClickListener { 
            if (!isInPipMode) finish() 
        }

        btnPip?.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!isInPipMode) {
                    userRequestedPip = true
                    wasLockedBeforePip = isLocked
                    enterPipMode()
                }
            }
        }

        btnLock?.setOnClickListener {
            toggleLockState()
        }

        btnMute?.setOnClickListener {
            toggleMuteState()
        }

        btnRewind?.setOnClickListener {
            player?.let { p ->
                val newPosition = p.currentPosition - skipMs
                p.seekTo(if (newPosition < 0) 0 else newPosition)
            }
        }

        btnPlayPause?.setOnClickListener {
            player?.let { p ->
                if (p.isPlaying) {
                    p.pause()
                } else {
                    p.play()
                }
            }
        }

        btnForward?.setOnClickListener {
            player?.let { p ->
                val newPosition = p.currentPosition + skipMs
                if (p.isCurrentWindowLive && p.duration != C.TIME_UNSET && newPosition >= p.duration) {
                    p.seekTo(p.duration)
                } else {
                    p.seekTo(newPosition)
                }
            }
        }

        btnFullscreen?.setOnClickListener {
            toggleOrientation()
        }

        btnAspectRatio?.setOnClickListener {
            cycleAspectRatio()
        }

        btnSettings?.setOnClickListener {
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

        binding.playerView.setOnClickListener {
            if (isLocked) {
                showUnlockButton()
            }
        }
    }

    private fun setupLockOverlay() {
        binding.unlockButton.setOnClickListener {
            toggleLockState()
        }
        
        binding.lockOverlay.setOnClickListener {
            showUnlockButton()
        }
    }

    private fun toggleLockState() {
        isLocked = !isLocked
        
        if (isLocked) {
            binding.playerView.useController = false
            binding.playerView.hideController()
            binding.lockOverlay.visibility = View.VISIBLE
            showUnlockButton()
            btnLock?.setImageResource(R.drawable.ic_lock_closed)
        } else {
            binding.playerView.useController = true
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            mainHandler.removeCallbacks(hideUnlockButtonRunnable)
            binding.playerView.showController()
            btnLock?.setImageResource(R.drawable.ic_lock_open)
        }
    }

    private fun showUnlockButton() {
        binding.unlockButton.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        mainHandler.postDelayed(hideUnlockButtonRunnable, 3000)
    }

    private fun toggleMuteState() {
        isMuted = !isMuted
        player?.volume = if (isMuted) 0f else 1f
        btnMute?.setImageResource(
            if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
    }

    private fun cycleAspectRatio() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        binding.playerView.resizeMode = currentResizeMode
    }

    private fun toggleOrientation() {
        requestedOrientation = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { channel ->
            startWithChannel(this, channel)
            finish()
        }

        binding.relatedChannelsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = relatedChannelsAdapter
        }
    }

    private fun setupLinksUI() {
        linkChipAdapter = LinkChipAdapter(
            links = allEventLinks,
            selectedIndex = currentLinkIndex
        ) { index ->
            switchToLink(index)
        }

        binding.linksRecyclerView.apply {
            layoutManager = GridLayoutManager(this@PlayerActivity, 3)
            adapter = linkChipAdapter
        }
    }

    private fun loadRelatedContent() {
        when (contentType) {
            ContentType.CHANNEL -> {
                channelData?.categoryId?.let { categoryId ->
                    viewModel.getChannelsByCategory(categoryId)
                }
            }
            ContentType.EVENT -> {
            }
        }

        viewModel.channels.observe(this) { channels ->
            relatedChannels = channels.filter { it.id != contentId }
            relatedChannelsAdapter.submitList(relatedChannels)
            
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (!isLandscape && relatedChannels.isNotEmpty()) {
                binding.relatedChannelsSection.visibility = View.VISIBLE
            }
        }
    }

    private fun switchToLink(index: Int) {
        if (index < 0 || index >= allEventLinks.size || index == currentLinkIndex) {
            return
        }

        val wasPlaying = player?.isPlaying == true
        val currentPosition = player?.currentPosition ?: 0

        currentLinkIndex = index
        streamUrl = allEventLinks[index].url

        linkChipAdapter.updateSelection(index)

        releasePlayer()
        setupPlayer()

        player?.let { p ->
            if (currentPosition > 0) {
                p.seekTo(currentPosition)
            }
            if (wasPlaying) {
                p.play()
            }
        }
    }

    private fun applyOrientationSettings(isLandscape: Boolean) {
        if (isLandscape) {
            binding.relatedChannelsSection.visibility = View.GONE
            binding.linksSection.visibility = View.GONE
            
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            if (allEventLinks.size > 1) {
                binding.linksSection.visibility = View.VISIBLE
            }
            if (relatedChannels.isNotEmpty()) {
                binding.relatedChannelsSection.visibility = View.VISIBLE
            }
            
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hasPipSupport = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            btnPip?.visibility = if (hasPipSupport && isLandscape) View.VISIBLE else View.GONE
        } else {
            btnPip?.visibility = View.GONE
        }
    }

    private fun updateLinksForOrientation(isLandscape: Boolean) {
        if (isLandscape) {
            binding.linksSection.visibility = View.GONE
        } else {
            if (allEventLinks.size > 1) {
                linkChipAdapter.updateLinks(allEventLinks, currentLinkIndex)
                binding.linksSection.visibility = View.VISIBLE
            } else {
                binding.linksSection.visibility = View.GONE
            }
        }
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.errorView.visibility = View.VISIBLE
        binding.errorMessage.text = message
        binding.retryButton.setOnClickListener {
            retryPlayback()
        }
    }

    private fun retryPlayback() {
        binding.errorView.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        player?.release()
        player = null
        setupPlayer()
    }

    private fun setSubtitleTextSize() {
        val subtitleView = binding.playerView.subtitleView
        subtitleView?.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isLocked) {
                    showUnlockButton()
                } else if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    finish()
                }
            }
        })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        setWindowFlags(isLandscape)
        configurePlayerDimensions(isLandscape)
        
        binding.playerView.postDelayed({
            applyOrientationSettings(isLandscape)
        }, 100)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return
        }

        try {
            val params = buildPipParams()
            enterPictureInPictureMode(params)
        } catch (e: Exception) {
            Log.e(TAG, "Error entering PiP mode", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()

        getVideoAspectRatio()?.let { aspectRatio ->
            builder.setAspectRatio(aspectRatio)
            builder.setSourceRectHint(calculateSourceRect(aspectRatio))
        }

        builder.setActions(createPipActions())

        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getVideoAspectRatio(): Rational? {
        val videoSize = player?.videoSize ?: return null
        val width = videoSize.width
        val height = videoSize.height

        if (width == 0 || height == 0) return null

        return try {
            Rational(width, height).takeIf { it.toFloat() in 0.5f..2.39f }
        } catch (e: Exception) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun calculateSourceRect(aspectRatio: Rational): Rect {
        val viewWidth = binding.playerView.width.toFloat()
        val viewHeight = binding.playerView.height.toFloat()
        val videoAspect = aspectRatio.toFloat()
        val viewAspect = viewWidth / viewHeight

        return if (viewAspect < videoAspect) {
            val height = viewWidth / videoAspect
            val top = ((viewHeight - height) / 2).toInt()
            Rect(0, top, viewWidth.toInt(), (height + top).toInt())
        } else {
            val width = viewHeight * videoAspect
            val left = ((viewWidth - width) / 2).toInt()
            Rect(left, 0, (width + left).toInt(), viewHeight.toInt())
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipParams() {
        if (!isInPipMode || isFinishing || isDestroyed) {
            return
        }

        try {
            val params = buildPipParams()
            setPictureInPictureParams(params)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating PiP params", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createPipActions(): List<RemoteAction> {
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
            Icon.createWithResource(this, R.drawable.ic_skip_back),
            getString(R.string.exo_controls_rewind_description),
            getString(R.string.exo_controls_rewind_description),
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
                getString(R.string.exo_controls_pause_description),
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
                getString(R.string.exo_controls_play_description),
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
            getString(R.string.exo_controls_fastforward_description),
            getString(R.string.exo_controls_fastforward_description),
            forwardIntent
        ))

        return actions
    }

    @RequiresApi(Build.VERSION_CODES.O)
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

    @RequiresApi(Build.VERSION_CODES.O)
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
            Log.e(TAG, "Error unregistering PiP receiver", e)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isInPipMode) {
            player?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPipMode) {
            player?.pause()
        }
    }

    override fun onDestroy() {
        releasePlayer()
        unregisterPipReceiver()
        super.onDestroy()
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

    private fun releasePlayer() {
        try {
            playerListener?.let { player?.removeListener(it) }
            player?.release()
            player = null
            trackSelector = null
            playerListener = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing player", e)
        }
    }
}
