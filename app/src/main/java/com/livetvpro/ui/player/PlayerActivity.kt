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
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
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
import com.livetvpro.ui.player.dialogs.LinkSelectionDialog
import com.livetvpro.ui.player.settings.PlayerSettingsDialog
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
        binding.unlockButton?.visibility = View.GONE
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

        val currentOrientation = resources.configuration.orientation
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE

        setWindowFlags(isLandscape)
        setupWindowInsets()

        val params = binding.playerContainer?.layoutParams as ConstraintLayout.LayoutParams
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
        binding.playerContainer?.layoutParams = params

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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode) {
                    finish()
                } else if (isLocked) {
                    showUnlockButton()
                } else {
                    finish()
                }
            }
        })
    }

    private fun parseIntent() {
        val receivedChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CHANNEL) as? Channel
        }

        val receivedEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_EVENT, LiveEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_EVENT) as? LiveEvent
        }

        val selectedLinkIndex = intent.getIntExtra(EXTRA_SELECTED_LINK_INDEX, -1)

        if (receivedEvent != null) {
            contentType = ContentType.EVENT
            eventData = receivedEvent
            contentId = receivedEvent.id
            contentName = receivedEvent.title

            if (receivedEvent.links.isNotEmpty()) {
                allEventLinks = receivedEvent.links

                currentLinkIndex = if (selectedLinkIndex >= 0 && selectedLinkIndex < allEventLinks.size) {
                    selectedLinkIndex
                } else {
                    0
                }

                streamUrl = allEventLinks[currentLinkIndex].url
            } else {
                streamUrl = ""
            }

        } else if (receivedChannel != null) {
            contentType = ContentType.CHANNEL
            channelData = receivedChannel
            contentId = receivedChannel.id
            contentName = receivedChannel.name ?: ""

            if (receivedChannel.links != null && receivedChannel.links.isNotEmpty()) {
                allEventLinks = receivedChannel.links.map {
                    LiveEventLink(label = it.quality, url = it.url)
                }

                currentLinkIndex = if (selectedLinkIndex >= 0 && selectedLinkIndex < allEventLinks.size) {
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

    private fun setupWindowInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
    }

    private fun setWindowFlags(isLandscape: Boolean) {
        if (isLandscape) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.hide(WindowInsets.Type.systemBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
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

    private fun bindControllerViews() {
        val controllerRoot = binding.playerView.findViewById<View>(R.id.exo_controller_root)
        if (controllerRoot != null) {
            btnBack = controllerRoot.findViewById(R.id.btnBack)
            btnPip = controllerRoot.findViewById(R.id.btnPip)
            btnSettings = controllerRoot.findViewById(R.id.btnSettings)
            btnLock = controllerRoot.findViewById(R.id.btnLock)
            btnMute = controllerRoot.findViewById(R.id.btnMute)
            btnRewind = controllerRoot.findViewById(R.id.btnRewind)
            btnPlayPause = controllerRoot.findViewById(R.id.exo_play_pause)
            btnForward = controllerRoot.findViewById(R.id.btnForward)
            btnFullscreen = controllerRoot.findViewById(R.id.btnFullscreen)
            btnAspectRatio = controllerRoot.findViewById(R.id.btnAspectRatio)
            tvChannelName = controllerRoot.findViewById(R.id.tvChannelName)

            tvChannelName?.text = contentName

            btnBack?.setOnClickListener { finish() }
            btnPip?.setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    enterPipMode()
                }
            }
            btnSettings?.setOnClickListener { showPlayerSettings() }
            btnLock?.setOnClickListener { toggleLock() }
            btnMute?.setOnClickListener { toggleMute() }
            btnRewind?.setOnClickListener { skipBackward() }
            btnForward?.setOnClickListener { skipForward() }
            btnFullscreen?.setOnClickListener { toggleOrientation() }
            btnAspectRatio?.setOnClickListener { cycleAspectRatio() }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                btnPip?.visibility = View.GONE
            }
        }
    }

    private fun applyOrientationSettings(isLandscape: Boolean) {
        val controllerRoot = binding.playerView.findViewById<View>(R.id.exo_controller_root)
        if (controllerRoot != null) {
            val topControls = controllerRoot.findViewById<View>(R.id.topControls)
            val bottomControls = controllerRoot.findViewById<View>(R.id.bottomControls)
            val centerControls = controllerRoot.findViewById<View>(R.id.centerControls)
            val btnAspectRatio = controllerRoot.findViewById<ImageButton>(R.id.btnAspectRatio)
            val btnSettings = controllerRoot.findViewById<ImageButton>(R.id.btnSettings)
            val btnLock = controllerRoot.findViewById<ImageButton>(R.id.btnLock)

            if (isLandscape) {
                topControls?.visibility = View.VISIBLE
                bottomControls?.visibility = View.VISIBLE
                centerControls?.visibility = View.VISIBLE
                btnAspectRatio?.visibility = View.VISIBLE
                btnSettings?.visibility = View.VISIBLE
                btnLock?.visibility = View.VISIBLE
                binding.linksSection?.visibility = View.GONE
                binding.relatedChannelsSection?.visibility = View.GONE
            } else {
                topControls?.visibility = View.VISIBLE
                bottomControls?.visibility = View.VISIBLE
                centerControls?.visibility = View.GONE
                btnAspectRatio?.visibility = View.GONE
                btnSettings?.visibility = View.GONE
                btnLock?.visibility = View.GONE

                if (allEventLinks.size > 1) {
                    binding.linksSection?.visibility = View.VISIBLE
                }

                if (relatedChannels.isNotEmpty()) {
                    binding.relatedChannelsSection?.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun configurePlayerInteractions() {
        binding.playerView.setOnClickListener {
            if (!isLocked && !isInPipMode) {
                if (binding.playerView.isControllerFullyVisible) {
                    binding.playerView.hideController()
                } else {
                    binding.playerView.showController()
                }
            } else if (isLocked) {
                showUnlockButton()
            }
        }
    }

    private fun setupLockOverlay() {
        binding.lockOverlay?.setOnClickListener {
            showUnlockButton()
        }

        binding.unlockButton?.setOnClickListener {
            toggleLock()
        }
    }

    private fun toggleLock() {
        isLocked = !isLocked

        if (isLocked) {
            binding.playerView.useController = false
            binding.playerView.hideController()
            binding.lockOverlay?.visibility = View.VISIBLE
            showUnlockButton()
            btnLock?.setImageResource(R.drawable.ic_lock)
        } else {
            binding.playerView.useController = true
            binding.lockOverlay?.visibility = View.GONE
            binding.unlockButton?.visibility = View.GONE
            mainHandler.removeCallbacks(hideUnlockButtonRunnable)
            btnLock?.setImageResource(R.drawable.ic_lock_open)

            binding.playerView.postDelayed({
                if (!isInPipMode && !isLocked) {
                    binding.playerView.showController()
                }
            }, 100)
        }
    }

    private fun showUnlockButton() {
        binding.unlockButton?.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        mainHandler.postDelayed(hideUnlockButtonRunnable, 3000)
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            btnMute?.setImageResource(
                if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
            )
        }
    }

    private fun skipBackward() {
        player?.let {
            val newPosition = it.currentPosition - skipMs
            it.seekTo(if (newPosition < 0) 0 else newPosition)
        }
    }

    private fun skipForward() {
        player?.let {
            val newPosition = it.currentPosition + skipMs
            if (it.isCurrentWindowLive && it.duration != C.TIME_UNSET && newPosition >= it.duration) {
                it.seekTo(it.duration)
            } else {
                it.seekTo(newPosition)
            }
        }
    }

    private fun toggleOrientation() {
        requestedOrientation = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (isInPipMode) return

        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        setWindowFlags(isLandscape)

        val params = binding.playerContainer?.layoutParams as ConstraintLayout.LayoutParams
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
        binding.playerContainer?.layoutParams = params

        binding.playerView.postDelayed({
            applyOrientationSettings(isLandscape)
            updateLinksForOrientation(isLandscape)
        }, 100)
    }

    private fun cycleAspectRatio() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        binding.playerView.resizeMode = currentResizeMode

        val iconRes = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> R.drawable.ic_aspect_fit
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> R.drawable.ic_aspect_fill
            else -> R.drawable.ic_aspect_zoom
        }
        btnAspectRatio?.setImageResource(iconRes)
    }

    private fun showPlayerSettings() {
        val currentPlayer = player ?: return
        
        // Show quality selector if there are multiple links
        if (allEventLinks.size > 1) {
            showQualitySelector()
        } else {
            // Show player settings dialog for track selection
            val dialog = PlayerSettingsDialog(this, currentPlayer)
            dialog.show()
        }
    }

    private fun showQualitySelector() {
        if (allEventLinks.size <= 1) return

        val items = allEventLinks.mapIndexed { index, link ->
            val prefix = if (index == currentLinkIndex) "✓ " else ""
            "$prefix${link.label}"
        }.toTypedArray()

        val builder = androidx.appcompat.app.AlertDialog.Builder(this, R.style.LinkSelectionDialogTheme)
        builder.setTitle(getString(R.string.select_quality))
        builder.setItems(items) { dialog, which ->
            if (which != currentLinkIndex) {
                switchToLink(which)
            }
            dialog.dismiss()
        }
        builder.show()
    }

    private fun switchToLink(newIndex: Int) {
        if (newIndex < 0 || newIndex >= allEventLinks.size) return

        currentLinkIndex = newIndex
        streamUrl = allEventLinks[newIndex].url

        val wasPlaying = player?.isPlaying == true
        val currentPosition = player?.currentPosition ?: 0L

        releasePlayer()
        setupPlayer()

        if (wasPlaying) {
            player?.seekTo(currentPosition)
            player?.play()
        }

        linkChipAdapter.setSelectedPosition(newIndex)
    }

    private fun setupLinksUI() {
        if (allEventLinks.size > 1) {
            linkChipAdapter = LinkChipAdapter { link, index ->
                switchToLink(index)
            }

            binding.linksRecycler.apply {
                layoutManager = LinearLayoutManager(
                    this@PlayerActivity,
                    LinearLayoutManager.HORIZONTAL,
                    false
                )
                adapter = linkChipAdapter
            }
            
            linkChipAdapter.submitList(allEventLinks)
            linkChipAdapter.setSelectedPosition(currentLinkIndex)

            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            updateLinksForOrientation(isLandscape)
        }
    }

    private fun updateLinksForOrientation(isLandscape: Boolean) {
        if (allEventLinks.size <= 1) {
            binding.linksSection?.visibility = View.GONE
            return
        }

        if (isLandscape) {
            binding.linksSection?.visibility = View.GONE
        } else {
            binding.linksSection?.visibility = View.VISIBLE
        }
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { channel ->
            finish()
            startWithChannel(this, channel)
        }

        binding.relatedChannelsRecycler.apply {
            layoutManager = GridLayoutManager(this@PlayerActivity, 2)
            adapter = relatedChannelsAdapter
        }
    }

    private fun loadRelatedContent() {
        if (contentType == ContentType.CHANNEL && contentId.isNotEmpty()) {
            val categoryId = channelData?.categoryId
            if (categoryId != null) {
                viewModel.loadRelatedChannels(categoryId, contentId)
                viewModel.relatedItems.observe(this) { channels ->
                    relatedChannels = channels.take(6)
                    relatedChannelsAdapter.submitList(relatedChannels)

                    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    if (relatedChannels.isNotEmpty() && !isLandscape) {
                        binding.relatedChannelsSection?.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun setupPlayer() {
        if (streamUrl.isEmpty()) {
            showError(getString(R.string.no_stream_available))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerPipReceiver()
        }

        trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setPreferredAudioLanguage("eng")
                .build()
        }

        val streamInfo = parseStreamUrl(streamUrl)
        val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            setAllowCrossProtocolRedirects(true)
            setConnectTimeoutMs(15000)
            setReadTimeoutMs(15000)

            if (streamInfo.headers.isNotEmpty()) {
                setDefaultRequestProperties(streamInfo.headers)
            }
        }

        val mediaSourceFactory: DefaultMediaSourceFactory

        if (streamInfo.drmScheme != null && streamInfo.drmScheme != C.WIDEVINE_UUID) {
            val drmCallback = if (streamInfo.drmLicenseUrl != null) {
                HttpMediaDrmCallback(streamInfo.drmLicenseUrl, dataSourceFactory)
            } else if (streamInfo.drmKeyId != null && streamInfo.drmKey != null) {
                // LocalMediaDrmCallback only accepts one ByteArray parameter
                LocalMediaDrmCallback(streamInfo.drmKey.toByteArray())
            } else {
                null
            }

            if (drmCallback != null) {
                val drmSessionManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(streamInfo.drmScheme, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .build(drmCallback)

                mediaSourceFactory = DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(dataSourceFactory)
                    .setDrmSessionManagerProvider { drmSessionManager }
            } else {
                mediaSourceFactory = DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(dataSourceFactory)
            }
        } else {
            mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(dataSourceFactory)
        }

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector!!)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        binding.playerView.player = player

        val mediaItem = MediaItem.Builder()
            .setUri(streamInfo.url)
            .build()

        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }

        setSubtitleTextSize()

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
                    }
                    Player.STATE_ENDED -> {
                        binding.progressBar.visibility = View.GONE
                    }
                    Player.STATE_IDLE -> {
                        binding.progressBar.visibility = View.GONE
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode) {
                    updatePipParams()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                binding.progressBar.visibility = View.GONE
                showError(getString(R.string.playback_error))
                Log.e(TAG, "Player error: ${error.message}", error)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode) {
                    updatePipParams()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode) {
                    updatePipParams()
                }
            }
        }

        player?.addListener(playerListener!!)
    }

    private fun setSubtitleTextSize() {
        binding.playerView.subtitleView?.apply {
            val textSizeSp = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                18f
            } else {
                14f
            }
            setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            setStyle(
                androidx.media3.ui.CaptionStyleCompat(
                    Color.WHITE,
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    Color.BLACK,
                    Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                )
            )
        }
    }

    private fun releasePlayer() {
        playerListener?.let {
            player?.removeListener(it)
        }
        player?.release()
        player = null
        trackSelector = null
        playerListener = null
    }

    private fun showError(message: String) {
        binding.errorView.apply {
            text = message
            visibility = View.VISIBLE
        }
        binding.progressBar.visibility = View.GONE
    }

    data class StreamInfo(
        val url: String,
        val headers: Map<String, String>,
        val drmScheme: UUID?,
        val drmKeyId: String?,
        val drmKey: String?,
        val drmLicenseUrl: String?
    )

    private fun parseStreamUrl(input: String): StreamInfo {
        val parts = input.split("|")
        val url = parts[0]
        val headers = mutableMapOf<String, String>()

        var drmScheme: UUID? = null
        var drmKeyId: String? = null
        var drmKey: String? = null
        var drmLicenseUrl: String? = null

        for (i in 1 until parts.size) {
            val pair = parts[i].split("=", limit = 2)
            if (pair.size != 2) continue

            val key = pair[0].trim()
            val value = pair[1].trim()

            when (key.lowercase()) {
                "drmscheme" -> drmScheme = normalizeDrmScheme(value)
                "drmlicense" -> {
                    if (value.startsWith("http://") || value.startsWith("https://")) {
                        drmLicenseUrl = value
                    } else {
                        val colonIndex = value.indexOf(':')
                        if (colonIndex != -1) {
                            drmKeyId = value.substring(0, colonIndex).trim()
                            drmKey = value.substring(colonIndex + 1).trim()
                        }
                    }
                }
                "referer", "referrer" -> headers["Referer"] = value
                "user-agent", "useragent" -> headers["User-Agent"] = value
                "origin" -> headers["Origin"] = value
                "cookie" -> headers["Cookie"] = value
                "x-forwarded-for" -> headers["X-Forwarded-For"] = value
                else -> headers[key] = value
            }
        }

        return StreamInfo(url, headers, drmScheme, drmKeyId, drmKey, drmLicenseUrl)
    }

    private fun normalizeDrmScheme(value: String): UUID? {
        return when (value.lowercase()) {
            "widevine" -> C.WIDEVINE_UUID
            "playready" -> C.PLAYREADY_UUID
            "clearkey" -> C.CLEARKEY_UUID
            else -> try {
                UUID.fromString(value)
            } catch (e: Exception) {
                null
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        val videoSize = player?.videoSize
        val hasValidSize = videoSize != null && videoSize.width > 0 && videoSize.height > 0

        val aspectRatio = if (hasValidSize) {
            Rational(videoSize!!.width, videoSize.height)
        } else {
            Rational(16, 9)
        }

        val pipParamsBuilder = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .setActions(getPipActions())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pipParamsBuilder.setAutoEnterEnabled(true)
        }

        val params = pipParamsBuilder.build()

        try {
            val result = enterPictureInPictureMode(params)
            if (!result) {
                Log.w(TAG, "Failed to enter PiP mode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error entering PiP mode", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipParams() {
        if (!isInPipMode) return

        try {
            val videoSize = player?.videoSize
            val hasValidSize = videoSize != null && videoSize.width > 0 && videoSize.height > 0

            val aspectRatio = if (hasValidSize) {
                Rational(videoSize!!.width, videoSize.height)
            } else {
                Rational(16, 9)
            }

            val sourceRect = if (hasValidSize) {
                val playerView = binding.playerView
                val viewWidth = playerView.width
                val viewHeight = playerView.height
                val videoAspect = videoSize!!.width.toFloat() / videoSize.height.toFloat()
                val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

                val rect: Rect
                if (videoAspect > viewAspect) {
                    val height = (viewWidth / videoAspect).toInt()
                    val top = (viewHeight - height) / 2
                    rect = Rect(0, top, viewWidth, top + height)
                } else {
                    val width = (viewHeight * videoAspect).toInt()
                    val left = (viewWidth - width) / 2
                    rect = Rect(left, 0, left + width, viewHeight)
                }
                rect
            } else {
                null
            }

            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .setActions(getPipActions())

            if (sourceRect != null) {
                builder.setSourceRectHint(sourceRect)
            }

            setPictureInPictureParams(builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Error updating PiP params", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getPipActions(): List<RemoteAction> {
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
            binding.lockOverlay?.visibility = View.GONE
            binding.unlockButton?.visibility = View.GONE
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
                    binding.linksSection?.visibility = View.VISIBLE
                }
                if (relatedChannels.isNotEmpty()) {
                    binding.relatedChannelsSection?.visibility = View.VISIBLE
                }
            }

            if (wasLockedBeforePip) {
                isLocked = true
                binding.playerView.useController = false
                binding.lockOverlay?.visibility = View.VISIBLE
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

    private fun retryPlayback() {
        binding.errorView.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        releasePlayer()
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
