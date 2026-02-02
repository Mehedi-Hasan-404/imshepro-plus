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

    private lateinit var windowInsetsController: WindowInsetsControllerCompat

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
        
        windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        val currentOrientation = resources.configuration.orientation
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        
        setupWindowFlags(isLandscape)
        setupSystemUI(isLandscape)
        setupWindowInsets()
        
        parseIntent()

        if (!isLandscape) {
            binding.relatedChannelsSection.visibility = View.VISIBLE
            binding.relatedLoadingProgress.visibility = View.VISIBLE
            binding.relatedChannelsRecycler.visibility = View.GONE
            
            if (allEventLinks.size > 1) {
                binding.linksSection.visibility = View.VISIBLE
            }
        }
        
        configurePlayerInteractions()
        setupLockOverlay()
        setupRelatedChannels()
        setupLinksUI()
        
        bindControllerViews()
        applyOrientationSettings(isLandscape)
        
        binding.playerView.useController = true
        binding.playerView.showController()
        binding.playerView.controllerShowTimeoutMs = Int.MAX_VALUE
        
        binding.progressBar.visibility = View.VISIBLE
        
        if (contentType == ContentType.CHANNEL && contentId.isNotEmpty()) {
            viewModel.refreshChannelData(contentId)
        }
        
        observeViewModel()
        
        setupPlayer()
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
                contentId = channelData!!.id
                contentName = channelData!!.name
                
                val allLinks = channelData!!.links ?: emptyList()
                allEventLinks = allLinks.map { link ->
                    LiveEventLink(
                        id = UUID.randomUUID().toString(),
                        eventId = contentId,
                        name = link.name,
                        url = link.url,
                        quality = link.quality ?: "Auto",
                        isActive = true,
                        headers = link.headers,
                        drmScheme = link.drmScheme,
                        drmLicenseUrl = link.drmLicenseUrl,
                        drmKeyId = link.drmKeyId,
                        drmKey = link.drmKey
                    )
                }
                
                currentLinkIndex = if (selectedLinkIndex >= 0 && selectedLinkIndex < allEventLinks.size) {
                    selectedLinkIndex
                } else {
                    allEventLinks.indexOfFirst { it.isActive }.takeIf { it >= 0 } ?: 0
                }
                
                streamUrl = if (allEventLinks.isNotEmpty()) {
                    allEventLinks[currentLinkIndex].url
                } else {
                    ""
                }
            }
            eventData != null -> {
                contentType = ContentType.EVENT
                contentId = eventData!!.id
                contentName = eventData!!.title
                
                allEventLinks = eventData!!.links ?: emptyList()
                
                currentLinkIndex = if (selectedLinkIndex >= 0 && selectedLinkIndex < allEventLinks.size) {
                    selectedLinkIndex
                } else {
                    allEventLinks.indexOfFirst { it.isActive }.takeIf { it >= 0 } ?: 0
                }
                
                streamUrl = if (allEventLinks.isNotEmpty()) {
                    allEventLinks[currentLinkIndex].url
                } else {
                    ""
                }
            }
            else -> {
                contentType = ContentType.CHANNEL
                contentId = ""
                contentName = "Unknown"
                streamUrl = ""
            }
        }
    }

    private fun setupWindowFlags(isLandscape: Boolean) {
        window.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun setupSystemUI(isLandscape: Boolean) {
        windowInsetsController.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            
            if (isLandscape) {
                hide(WindowInsetsCompat.Type.systemBars())
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
            
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun setupWindowInsets() {
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val displayCutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                insets.displayCutout
            } else {
                null
            }
            
            val topInset = maxOf(
                systemBars.top,
                displayCutout?.safeInsetTop ?: 0
            )
            val bottomInset = maxOf(
                systemBars.bottom,
                displayCutout?.safeInsetBottom ?: 0
            )
            val leftInset = maxOf(
                systemBars.left,
                displayCutout?.safeInsetLeft ?: 0
            )
            val rightInset = maxOf(
                systemBars.right,
                displayCutout?.safeInsetRight ?: 0
            )
            
            binding.playerView.setPadding(0, 0, 0, 0)
            
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            
            if (!isLandscape) {
                binding.relatedChannelsSection.setPadding(
                    leftInset,
                    0,
                    rightInset,
                    bottomInset
                )
                
                binding.linksSection.setPadding(
                    leftInset,
                    0,
                    rightInset,
                    0
                )
            } else {
                binding.relatedChannelsSection.setPadding(0, 0, 0, 0)
                binding.linksSection.setPadding(0, 0, 0, 0)
            }
            
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun configurePlayerInteractions() {
        binding.playerView.apply {
            useController = true
            setControllerHideDuringAds(false)
            controllerShowTimeoutMs = 5000
            controllerHideOnTouch = true
        }
    }

    private fun setupLockOverlay() {
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE
        
        binding.lockOverlay.setOnClickListener {
            binding.unlockButton.visibility = View.VISIBLE
            mainHandler.removeCallbacks(hideUnlockButtonRunnable)
            mainHandler.postDelayed(hideUnlockButtonRunnable, 3000)
        }
        
        binding.unlockButton.setOnClickListener {
            setLocked(false)
        }
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter(
            onChannelClick = { channel ->
                if (!isInPipMode && !isLocked) {
                    releasePlayer()
                    channelData = channel
                    parseIntent()
                    setupPlayer()
                    updateChannelName()
                }
            }
        )
        
        binding.relatedChannelsRecycler.apply {
            adapter = relatedChannelsAdapter
            
            val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 2
            layoutManager = GridLayoutManager(this@PlayerActivity, spanCount)
            
            setHasFixedSize(true)
        }
    }

    private fun setupLinksUI() {
        linkChipAdapter = LinkChipAdapter(
            links = allEventLinks,
            selectedIndex = currentLinkIndex,
            onLinkSelected = { linkIndex ->
                if (linkIndex != currentLinkIndex && linkIndex in allEventLinks.indices) {
                    currentLinkIndex = linkIndex
                    streamUrl = allEventLinks[currentLinkIndex].url
                    
                    linkChipAdapter.updateSelection(currentLinkIndex)
                    
                    releasePlayer()
                    setupPlayer()
                }
            }
        )
        
        binding.linksRecyclerView.apply {
            adapter = linkChipAdapter
            layoutManager = LinearLayoutManager(
                this@PlayerActivity,
                LinearLayoutManager.HORIZONTAL,
                false
            )
        }
        
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (allEventLinks.size > 1 && !isLandscape) {
            binding.linksSection.visibility = View.VISIBLE
        } else {
            binding.linksSection.visibility = View.GONE
        }
    }

    private fun bindControllerViews() {
        val customController = binding.playerView.findViewById<View>(R.id.exo_controller_container)
        
        if (customController != null) {
            btnBack = customController.findViewById(R.id.btn_back)
            btnPip = customController.findViewById(R.id.btn_pip)
            btnSettings = customController.findViewById(R.id.btn_settings)
            btnLock = customController.findViewById(R.id.btn_lock)
            btnMute = customController.findViewById(R.id.btn_mute)
            btnRewind = customController.findViewById(R.id.exo_rew)
            btnPlayPause = customController.findViewById(R.id.exo_play_pause)
            btnForward = customController.findViewById(R.id.exo_ffwd)
            btnFullscreen = customController.findViewById(R.id.btn_fullscreen)
            btnAspectRatio = customController.findViewById(R.id.btn_aspect_ratio)
            tvChannelName = customController.findViewById(R.id.tv_channel_name)
            
            btnBack?.setOnClickListener { finish() }
            btnPip?.setOnClickListener { enterPipMode() }
            btnSettings?.setOnClickListener { showQualitySelector() }
            btnLock?.setOnClickListener { setLocked(true) }
            btnMute?.setOnClickListener { toggleMute() }
            btnRewind?.setOnClickListener { handleRewindClick() }
            btnPlayPause?.setOnClickListener { handlePlayPauseClick() }
            btnForward?.setOnClickListener { handleForwardClick() }
            btnFullscreen?.setOnClickListener { toggleFullscreen() }
            btnAspectRatio?.setOnClickListener { cycleAspectRatio() }
            
            updateChannelName()
        }
    }

    private fun applyOrientationSettings(isLandscape: Boolean) {
        if (isLandscape) {
            binding.relatedChannelsSection.visibility = View.GONE
            binding.linksSection.visibility = View.GONE
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            binding.relatedChannelsSection.visibility = View.VISIBLE
            
            if (allEventLinks.size > 1) {
                binding.linksSection.visibility = View.VISIBLE
            } else {
                binding.linksSection.visibility = View.GONE
            }
            
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
        }
        
        val pipSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && 
                          packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        btnPip?.visibility = if (pipSupported) View.VISIBLE else View.GONE
        
        binding.playerView.resizeMode = currentResizeMode
        
        binding.root.requestLayout()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.relatedChannels.collect { channels ->
                relatedChannels = channels
                relatedChannelsAdapter.submitList(channels)
                
                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                if (!isLandscape) {
                    binding.relatedLoadingProgress.visibility = View.GONE
                    
                    if (channels.isNotEmpty()) {
                        binding.relatedChannelsRecycler.visibility = View.VISIBLE
                        binding.relatedChannelsSection.visibility = View.VISIBLE
                    } else {
                        binding.relatedChannelsRecycler.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun setupPlayer() {
        if (player != null) return
        
        binding.progressBar.visibility = View.VISIBLE
        
        binding.playerView.useController = true

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
                    val mediaItem = MediaItem.fromUri(streamInfo.url)
                    exo.setMediaItem(mediaItem)
                    exo.prepare()
                    exo.playWhenReady = true

                    playerListener = object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    updatePlayPauseIcon(exo.playWhenReady)
                                    binding.progressBar.visibility = View.GONE
                                    binding.errorView.visibility = View.GONE
                                    
                                    binding.playerView.controllerShowTimeoutMs = 5000
                                    
                                    updatePipParams()
                                }
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar.visibility = View.VISIBLE
                                }
                                Player.STATE_ENDED -> {
                                    binding.progressBar.visibility = View.GONE
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
        
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!isLandscape) {
            if (relatedChannels.isNotEmpty()) {
                binding.relatedChannelsSection.visibility = View.VISIBLE
            }
            
            if (allEventLinks.size > 1) {
                binding.linksSection.visibility = View.VISIBLE
            }
        }
        
        btnPlayPause?.setImageResource(R.drawable.ic_play)
    }

    data class StreamInfo(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val drmScheme: String? = null,
        val drmLicenseUrl: String? = null,
        val drmKeyId: String? = null,
        val drmKey: String? = null
    )

    private fun parseStreamUrl(rawUrl: String): StreamInfo {
        if (rawUrl.isBlank()) return StreamInfo("")
        
        if (!rawUrl.contains("|")) {
            return StreamInfo(url = rawUrl)
        }
        
        val parts = rawUrl.split("|")
        val url = parts[0].trim()
        val headers = mutableMapOf<String, String>()
        var drmScheme: String? = null
        var drmLicenseUrl: String? = null
        var drmKeyId: String? = null
        var drmKey: String? = null
        
        for (i in 1 until parts.size) {
            val part = parts[i].trim()
            
            if (part.startsWith("drm=", ignoreCase = true)) {
                drmScheme = part.substring(4).trim().lowercase()
            } else if (part.startsWith("drm-license=", ignoreCase = true)) {
                drmLicenseUrl = part.substring(12).trim()
            } else if (part.startsWith("drm-key-id=", ignoreCase = true)) {
                drmKeyId = part.substring(11).trim()
            } else if (part.startsWith("drm-key=", ignoreCase = true)) {
                drmKey = part.substring(8).trim()
            } else if (part.contains("=")) {
                val keyValue = part.split("=", limit = 2)
                if (keyValue.size == 2) {
                    val key = keyValue[0].trim()
                    val value = keyValue[1].trim()
                    headers[key] = value
                }
            }
        }
        
        return StreamInfo(
            url = url,
            headers = headers,
            drmScheme = drmScheme,
            drmLicenseUrl = drmLicenseUrl,
            drmKeyId = drmKeyId,
            drmKey = drmKey
        )
    }

    private fun createClearKeyDrmManager(keyId: String, key: String): DefaultDrmSessionManager? {
        return try {
            val uuid = C.CLEARKEY_UUID
            val drmCallback = LocalMediaDrmCallback(
                """{"keys":[{"kty":"oct","k":"$key","kid":"$keyId"}],"type":"temporary"}""".toByteArray()
            )
            
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(uuid) { uuid ->
                    FrameworkMediaDrm.newInstance(uuid)
                }
                .build(drmCallback)
        } catch (e: Exception) {
            null
        }
    }

    private fun createWidevineDrmManager(licenseUrl: String, headers: Map<String, String>): DefaultDrmSessionManager? {
        return try {
            val uuid = C.WIDEVINE_UUID
            val drmCallback = HttpMediaDrmCallback(licenseUrl, DefaultHttpDataSource.Factory())
            
            headers.forEach { (key, value) ->
                drmCallback.setKeyRequestProperty(key, value)
            }
            
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(uuid) { uuid ->
                    FrameworkMediaDrm.newInstance(uuid)
                }
                .build(drmCallback)
        } catch (e: Exception) {
            null
        }
    }

    private fun createPlayReadyDrmManager(licenseUrl: String, headers: Map<String, String>): DefaultDrmSessionManager? {
        return try {
            val uuid = C.PLAYREADY_UUID
            val drmCallback = HttpMediaDrmCallback(licenseUrl, DefaultHttpDataSource.Factory())
            
            headers.forEach { (key, value) ->
                drmCallback.setKeyRequestProperty(key, value)
            }
            
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(uuid) { uuid ->
                    FrameworkMediaDrm.newInstance(uuid)
                }
                .build(drmCallback)
        } catch (e: Exception) {
            null
        }
    }

    private fun showQualitySelector() {
        val dialog = QualitySelectionDialog()
        dialog.show(supportFragmentManager, "quality_selection")
    }

    private fun updateChannelName() {
        tvChannelName?.text = contentName
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        if (isPlaying) {
            btnPlayPause?.setImageResource(R.drawable.ic_pause)
        } else {
            btnPlayPause?.setImageResource(R.drawable.ic_play)
        }
    }

    private fun handleRewindClick() {
        if (!isLocked) {
            player?.let { p ->
                val newPosition = p.currentPosition - skipMs
                p.seekTo(if (newPosition < 0) 0 else newPosition)
            }
        }
    }

    private fun handlePlayPauseClick() {
        val hasError = binding.errorView.visibility == View.VISIBLE
        val hasEnded = player?.playbackState == Player.STATE_ENDED
        
        if (hasError || hasEnded) {
            retryPlayback()
        } else {
            if (!isLocked) {
                player?.let { p ->
                    if (p.isPlaying) {
                        p.pause()
                    } else {
                        p.play()
                    }
                }
            }
        }
    }

    private fun handleForwardClick() {
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

    private fun toggleMute() {
        if (!isLocked) {
            player?.let { p ->
                isMuted = !isMuted
                p.volume = if (isMuted) 0f else 1f
                
                if (isMuted) {
                    btnMute?.setImageResource(R.drawable.ic_volume_mute)
                } else {
                    btnMute?.setImageResource(R.drawable.ic_volume)
                }
            }
        }
    }

    private fun toggleFullscreen() {
        if (!isLocked) {
            val isCurrentlyLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            
            requestedOrientation = if (isCurrentlyLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
    }

    private fun cycleAspectRatio() {
        if (!isLocked) {
            currentResizeMode = when (currentResizeMode) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            
            binding.playerView.resizeMode = currentResizeMode
            
            val iconRes = when (currentResizeMode) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> R.drawable.ic_aspect_ratio_fit
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> R.drawable.ic_aspect_ratio_zoom
                AspectRatioFrameLayout.RESIZE_MODE_FILL -> R.drawable.ic_aspect_ratio_fill
                else -> R.drawable.ic_aspect_ratio_fit
            }
            btnAspectRatio?.setImageResource(iconRes)
        }
    }

    private fun setLocked(locked: Boolean) {
        isLocked = locked
        
        if (locked) {
            binding.lockOverlay.visibility = View.VISIBLE
            binding.unlockButton.visibility = View.VISIBLE
            
            binding.playerView.hideController()
            binding.playerView.useController = false
            
            mainHandler.removeCallbacks(hideUnlockButtonRunnable)
            mainHandler.postDelayed(hideUnlockButtonRunnable, 3000)
        } else {
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            
            binding.playerView.useController = true
            binding.playerView.showController()
            
            mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        }
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
        trackSelector = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        val isNowLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        setupSystemUI(isNowLandscape)
        
        applyOrientationSettings(isNowLandscape)
        
        binding.root.requestLayout()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && 
            !isLocked && 
            isLandscape &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            
            userRequestedPip = true
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        isInPipMode = isInPictureInPictureMode
        
        if (isInPictureInPictureMode) {
            onEnterPipMode()
        } else {
            onExitPipMode()
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            
            userRequestedPip = true
            
            wasLockedBeforePip = isLocked
            if (isLocked) {
                setLocked(false)
            }
            
            registerPipReceiver()
            updatePipParams(enter = true)
        }
    }

    private fun onEnterPipMode() {
        binding.relatedChannelsSection.visibility = View.GONE
        binding.linksSection.visibility = View.GONE
        binding.errorView.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE

        setSubtitleTextSizePiP()

        updatePipParams(enter = true)
    }

    private fun onExitPipMode() {
        if (!userRequestedPip) {
            finish()
            return
        }
        
        userRequestedPip = false
        
        val currentOrientation = resources.configuration.orientation
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        
        setupWindowFlags(isLandscape)
        setupSystemUI(isLandscape)
        
        applyOrientationSettings(isLandscape)
        
        setSubtitleTextSizeNormal()
        
        if (wasLockedBeforePip) {
            setLocked(true)
            wasLockedBeforePip = false
        }
        
        binding.root.requestLayout()
        
        unregisterPipReceiver()
    }

    private fun setSubtitleTextSizePiP() {
        val subtitleView = binding.playerView.subtitleView
        if (subtitleView != null) {
            subtitleView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            subtitleView.setApplyEmbeddedStyles(false)
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
    }

    private fun setSubtitleTextSizeNormal() {
        val subtitleView = binding.playerView.subtitleView
        if (subtitleView != null) {
            subtitleView.setUserDefaultStyle()
            subtitleView.setUserDefaultTextSize()
        }
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
        binding.errorText.text = ""
        binding.progressBar.visibility = View.VISIBLE
        
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
