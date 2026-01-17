package com.livetvpro.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private lateinit var pipHelper: PipHelper
    
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
        private const val ACTION_MEDIA_CONTROL = "com.livetvpro.MEDIA_CONTROL"
        private const val EXTRA_CONTROL_TYPE = "control_type"
        private const val CONTROL_TYPE_PLAY = 1
        private const val CONTROL_TYPE_PAUSE = 2
        private const val CONTROL_TYPE_REWIND = 3
        private const val CONTROL_TYPE_FORWARD = 4

        fun startWithChannel(context: Context, channel: Channel) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel as Parcelable)
            }
            context.startActivity(intent)
        }

        fun startWithEvent(context: Context, event: LiveEvent) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_EVENT, event as Parcelable)
            }
            context.startActivity(intent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pipHelper = PipHelper(this, binding.playerView) { player }
            pipHelper.initialize()
        }

        parseIntent()

        binding.root.post {
            val currentOrientation = resources.configuration.orientation
            val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
            applyOrientationSettings(isLandscape)
        }

        binding.progressBar.visibility = View.GONE
        setupPlayer()

        binding.playerView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPipMode) {
                    pipHelper.updatePictureInPictureParams()
                }
            }
        }

        binding.playerView.postDelayed({
            bindControllerViews()
            setupControlListeners()
        }, 300)

        configurePlayerInteractions()
        setupLockOverlay()
        setupRelatedChannels()
        setupLinksUI()
        loadRelatedContent()
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

        if (channelData != null) {
            contentType = ContentType.CHANNEL
            val channel = channelData!!
            contentId = channel.id
            contentName = channel.name
            streamUrl = channel.streamUrl
        } else if (eventData != null) {
            contentType = ContentType.EVENT
            val event = eventData!!
            contentId = event.id
            contentName = event.title.ifEmpty { "${event.team1Name} vs ${event.team2Name}" }
            allEventLinks = event.links
            currentLinkIndex = 0
            streamUrl = allEventLinks.firstOrNull()?.url ?: ""
        } else {
            finish()
            return
        }
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
        
        val landscapeLinksRecycler = binding.playerView.findViewById<RecyclerView>(R.id.exo_links_recycler)
        landscapeLinksRecycler?.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        
        val landscapeLinkAdapter = LinkChipAdapter { link, position ->
            switchToLink(link, position)
        }
        landscapeLinksRecycler?.adapter = landscapeLinkAdapter
        
        if (contentType == ContentType.EVENT && allEventLinks.size > 1) {
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
        
        if (contentType == ContentType.EVENT && allEventLinks.size > 1) {
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
    }

    private fun switchToChannel(newChannel: Channel) {
        releasePlayer()
        channelData = newChannel
        eventData = null
        contentType = ContentType.CHANNEL
        contentId = newChannel.id
        contentName = newChannel.name
        streamUrl = newChannel.streamUrl
        tvChannelName?.text = contentName
        setupPlayer()
        binding.relatedLoadingProgress.visibility = View.VISIBLE
        binding.relatedChannelsRecycler.visibility = View.GONE
        loadRelatedContent()
    }

    private fun switchToEvent(eventItem: Channel) {
        lifecycleScope.launch {
            try {
                val events = viewModel.getAllEvents()
                val selectedEvent = events.find { it.id == eventItem.id }
                selectedEvent?.let { event ->
                    runOnUiThread {
                        releasePlayer()
                        channelData = null
                        eventData = event
                        contentType = ContentType.EVENT
                        contentId = event.id
                        contentName = event.title.ifEmpty { "${event.team1Name} vs ${event.team2Name}" }
                        allEventLinks = event.links
                        currentLinkIndex = 0
                        streamUrl = allEventLinks.firstOrNull()?.url ?: ""
                        tvChannelName?.text = contentName
                        setupPlayer()
                        setupLinksUI()
                        binding.relatedLoadingProgress.visibility = View.VISIBLE
                        binding.relatedChannelsRecycler.visibility = View.GONE
                        loadRelatedContent()
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun switchToLink(link: LiveEventLink, position: Int) {
        if (position == currentLinkIndex) return
        
        currentLinkIndex = position
        streamUrl = link.url
        
        linkChipAdapter.setSelectedPosition(position)
        
        val landscapeLinksRecycler = binding.playerView.findViewById<RecyclerView>(R.id.exo_links_recycler)
        val landscapeAdapter = landscapeLinksRecycler?.adapter as? LinkChipAdapter
        landscapeAdapter?.setSelectedPosition(position)
        
        releasePlayer()
        setupPlayer()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!isInPictureInPictureMode) {
                val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
                applyOrientationSettings(isLandscape)
                setSubtitleTextSize()
            } else {
                binding.playerView.hideController()
            }
        } else {
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            applyOrientationSettings(isLandscape)
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT > 23) {
            setupPlayer()
            binding.playerView.onResume()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pipHelper.isPipSupported()) {
            setPictureInPictureParams(createPipParams())
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT <= 23 || player == null) {
            setupPlayer()
            binding.playerView.onResume()
        }
    }

    private fun applyOrientationSettings(isLandscape: Boolean) {
        adjustLayoutForOrientation(isLandscape)
        setWindowFlags(isLandscape)
        updateLinksForOrientation(isLandscape)
    }

    private fun adjustLayoutForOrientation(isLandscape: Boolean) {
        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        if (isLandscape) {
            binding.playerView.controllerAutoShow = false
            binding.playerView.controllerShowTimeoutMs = 3000
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            params.dimensionRatio = null
            params.height = ConstraintLayout.LayoutParams.MATCH_PARENT
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
            binding.relatedChannelsSection.visibility = View.GONE
            binding.linksSection.visibility = View.GONE
        } else {
            binding.playerView.controllerAutoShow = false
            binding.playerView.controllerShowTimeoutMs = 5000
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            params.dimensionRatio = "16:9"
            params.height = 0
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
            if (relatedChannels.isNotEmpty()) {
                binding.relatedChannelsSection.visibility = View.VISIBLE
            }
            if (contentType == ContentType.EVENT && allEventLinks.size > 1) {
                binding.linksSection.visibility = View.VISIBLE
            }
        }
        binding.playerContainer.layoutParams = params
        binding.playerContainer.requestLayout()
    }

    private fun setWindowFlags(isLandscape: Boolean) {
        if (isLandscape) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { controller ->
                    controller.hide(
                        WindowInsets.Type.statusBars() or
                                WindowInsets.Type.navigationBars()
                    )
                    controller.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_FULLSCREEN
                        )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(true)
                window.insetsController?.show(
                    WindowInsets.Type.statusBars() or
                            WindowInsets.Type.navigationBars()
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            isInPictureInPictureMode
        } else {
            false
        }
        if (!isPip) {
            if (Build.VERSION.SDK_INT <= 23) {
                releasePlayer()
            } else {
                binding.playerView.onPause()
                player?.pause()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT > 23) {
            releasePlayer()
        }
        if (isInPipMode) {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                pipReceiver?.let { unregisterReceiver(it) }
            }
        } catch (e: Exception) {
        }
    }

    private fun releasePlayer() {
        player?.let {
            try {
                it.stop()
                it.release()
            } catch (t: Throwable) {
            }
        }
        player = null
    }

    private data class StreamInfo(
        val url: String,
        val headers: Map<String, String>,
        val drmScheme: String?,
        val drmKeyId: String?,
        val drmKey: String?,
        val drmLicenseUrl: String? = null
    )

    private fun parseStreamUrl(streamUrl: String): StreamInfo {
        val pipeIndex = streamUrl.indexOf('|')
        if (pipeIndex == -1) {
            return StreamInfo(streamUrl, mapOf(), null, null, null, null)
        }

        val url = streamUrl.substring(0, pipeIndex).trim()
        val rawParams = streamUrl.substring(pipeIndex + 1).trim()
        val normalizedParams = rawParams.replace("&", "|")
        val parts = normalizedParams.split("|")

        val headers = mutableMapOf<String, String>()
        var drmScheme: String? = null
        var drmKeyId: String? = null
        var drmKey: String? = null
        var drmLicenseUrl: String? = null

        for (part in parts) {
            val eqIndex = part.indexOf('=')
            if (eqIndex == -1) continue

            val key = part.substring(0, eqIndex).trim()
            val value = part.substring(eqIndex + 1).trim()

            when (key.lowercase()) {
                "drmscheme" -> drmScheme = normalizeDrmScheme(value)
                "drmlicense" -> {
                    if (value.startsWith("http://", ignoreCase = true) ||
                        value.startsWith("https://", ignoreCase = true)) {
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

    private fun normalizeDrmScheme(scheme: String): String {
        val lower = scheme.lowercase()
        return when {
            lower.contains("clearkey") || lower == "org.w3.clearkey" -> "clearkey"
            lower.contains("widevine") || lower == "com.widevine.alpha" -> "widevine"
            lower.contains("playready") || lower == "com.microsoft.playready" -> "playready"
            lower.contains("fairplay") -> "fairplay"
            else -> lower
        }
    }

    private fun setupPlayer() {
        if (player != null) return
        binding.errorView.visibility = View.GONE
        binding.errorText.text = ""
        binding.progressBar.visibility = View.VISIBLE

        trackSelector = DefaultTrackSelector(this)

        try {
            val streamInfo = parseStreamUrl(streamUrl)

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

                    exo.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    updatePlayPauseIcon(exo.playWhenReady)
                                    binding.progressBar.visibility = View.GONE
                                    binding.errorView.visibility = View.GONE
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        pipHelper.updatePictureInPictureParams()
                                    }
                                }
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar.visibility = View.VISIBLE
                                    binding.errorView.visibility = View.GONE
                                }
                                Player.STATE_ENDED -> {
                                    binding.progressBar.visibility = View.GONE
                                }
                                Player.STATE_IDLE -> {}
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            updatePlayPauseIcon(isPlaying)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                pipHelper.updatePlaybackAction(isPlaying)
                            }
                        }

                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            super.onVideoSizeChanged(videoSize)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                pipHelper.updatePictureInPictureParams()
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            super.onPlayerError(error)
                            binding.progressBar.visibility = View.GONE

                            val errorMessage = when {
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT ->
                                    "Connection Failed"
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                                    when {
                                        error.message?.contains("403") == true -> "Access Denied"
                                        error.message?.contains("404") == true -> "Stream Not Found"
                                        (error.message?.contains("500") == true) ||
                                        (error.message?.contains("503") == true) -> "Server Error"
                                        else -> "Connection Failed"
                                    }
                                }
                                (error.message?.contains("drm", ignoreCase = true) == true) ||
                                (error.message?.contains("widevine", ignoreCase = true) == true) ||
                                (error.message?.contains("clearkey", ignoreCase = true) == true) ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                                    "Stream Error"
                                (error.message?.contains("geo", ignoreCase = true) == true) ||
                                (error.message?.contains("region", ignoreCase = true) == true) ->
                                    "Not Available"
                                else -> "Playback Error"
                            }

                            binding.errorText.apply {
                                text = errorMessage
                                setTextColor(android.graphics.Color.WHITE)
                                textSize = 16f
                                setPadding(48, 24, 48, 24)
                                setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
                                elevation = 8f
                            }
                            binding.errorView.visibility = View.VISIBLE
                        }
                    })
                }
        } catch (e: Exception) {
        }

        binding.playerView.apply {
            useController = true
            controllerShowTimeoutMs = 5000
            controllerHideOnTouch = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            controllerAutoShow = false
        }
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
        val currentOrientation = resources.configuration.orientation
        if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
        }
        btnAspectRatio?.setImageResource(R.drawable.ic_aspect_ratio)
        listOf(btnBack, btnPip, btnSettings, btnLock, btnMute, btnRewind, btnPlayPause, btnForward, btnFullscreen, btnAspectRatio).forEach {
            it?.apply { isClickable = true; isFocusable = true; isEnabled = true }
        }
        btnAspectRatio?.visibility = View.VISIBLE
        btnPip?.visibility = View.VISIBLE
        btnFullscreen?.visibility = View.VISIBLE
        tvChannelName?.text = contentName
    }

    private fun setupControlListeners() {
        btnBack?.setOnClickListener { if (!isLocked) finish() }
        btnPip?.setOnClickListener {
            if (!isLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                userRequestedPip = true
                pipHelper.enterPipMode()
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
        btnFullscreen?.setOnClickListener { if (!isLocked) toggleFullscreen() }
        btnMute?.setOnClickListener { if (!isLocked) toggleMute() }
    }

    private fun handlePlayPauseClick() {
        val hasError = binding.errorView.visibility == View.VISIBLE
        val hasEnded = player?.playbackState == Player.STATE_ENDED
        
        if (hasError || hasEnded) {
            binding.errorView.visibility = View.GONE
            binding.progressBar.visibility = View.VISIBLE
            player?.release()
            player = null
            setupPlayer()
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
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun setSubtitleTextSize() {
        val subtitleView = binding.playerView.subtitleView ?: return
        subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
    }

    private fun setSubtitleTextSizePiP() {
        val subtitleView = binding.playerView.subtitleView ?: return
        subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 2)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        if (!isInPictureInPictureMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                pipReceiver?.let {
                    unregisterReceiver(pipReceiver)
                    pipReceiver = null
                }
            }
            
            if (!userRequestedPip) {
                finish()
                return
            }
            userRequestedPip = false
            
            super.onPictureInPictureModeChanged(false, newConfig)
            isInPipMode = false
            
            setSubtitleTextSize()
            
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            
            if (isLocked) {
                binding.playerView.useController = false
                binding.lockOverlay.visibility = View.VISIBLE
                showUnlockButton()
            } else {
                binding.playerView.useController = true
            }
            
            binding.playerView.postDelayed({
                applyOrientationSettings(isLandscape)
                if (!isLocked) {
                    binding.playerView.showController()
                }
            }, 50)
            return
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setPictureInPictureParams(createPipParams())
        }
        isInPipMode = true
        binding.playerView.hideController()
        binding.relatedChannelsSection.visibility = View.GONE
        binding.linksSection.visibility = View.GONE
        setSubtitleTextSizePiP()
        
        if (pipReceiver == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pipReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null || intent.action != ACTION_MEDIA_CONTROL) return
                    val player = player ?: return
                    
                    val hasError = binding.errorView.visibility == View.VISIBLE
                    val hasEnded = player.playbackState == Player.STATE_ENDED
                    
                    when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                        CONTROL_TYPE_PLAY -> {
                            if (hasError || hasEnded) {
                                binding.errorView.visibility = View.GONE
                                binding.progressBar.visibility = View.VISIBLE
                                player.release()
                                this@PlayerActivity.player = null
                                setupPlayer()
                            } else {
                                player.play()
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                setPictureInPictureParams(createPipParams())
                            }
                        }
                        CONTROL_TYPE_PAUSE -> {
                            if (hasError || hasEnded) {
                                binding.errorView.visibility = View.GONE
                                binding.progressBar.visibility = View.VISIBLE
                                player.release()
                                this@PlayerActivity.player = null
                                setupPlayer()
                            } else {
                                player.pause()
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                setPictureInPictureParams(createPipParams())
                            }
                        }
                        CONTROL_TYPE_REWIND -> {
                            if (!hasError && !hasEnded) {
                                val newPosition = player.currentPosition - skipMs
                                player.seekTo(if (newPosition < 0) 0 else newPosition)
                            }
                        }
                        CONTROL_TYPE_FORWARD -> {
                            if (!hasError && !hasEnded) {
                                val newPosition = player.currentPosition + skipMs
                                if (player.isCurrentWindowLive && player.duration != C.TIME_UNSET && newPosition >= player.duration) {
                                    player.seekTo(player.duration)
                                } else {
                                    player.seekTo(newPosition)
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
        super.onPictureInPictureModeChanged(true, newConfig)
    }
    
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        
        val player = player
        if (player != null) {
            val format = player.videoFormat
            if (format != null) {
                val width = format.width
                val height = format.height
                if (width > 0 && height > 0) {
                    var rational = Rational(width, height)
                    val rationalLimitWide = Rational(239, 100)
                    val rationalLimitTall = Rational(100, 239)
                    
                    if (rational.toFloat() > rationalLimitWide.toFloat()) {
                        rational = rationalLimitWide
                    } else if (rational.toFloat() < rationalLimitTall.toFloat()) {
                        rational = rationalLimitTall
                    }
                    builder.setAspectRatio(rational)
                }
            }
        }
        
        val actions = ArrayList<RemoteAction>()
        
        val rewindIntent = PendingIntent.getBroadcast(
            this,
            3,
            Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_REWIND).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_skip_backward),
            "Rewind",
            "Rewind",
            rewindIntent
        ))
        
        if (player?.isPlaying == true) {
            val pauseIntent = PendingIntent.getBroadcast(
                this,
                2,
                Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PAUSE).setPackage(packageName),
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
                1,
                Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PLAY).setPackage(packageName),
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
            4,
            Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_FORWARD).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_skip_forward),
            "Forward",
            "Forward",
            forwardIntent
        ))
        
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
        
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPipMode && player?.isPlaying == true && pipHelper.isPipSupported()) {
            userRequestedPip = true
            pipHelper.enterPipMode()
        }
    }

    override fun finish() {
        try {
            releasePlayer()
            isInPipMode = false
            userRequestedPip = false
            super.finish()
        } catch (e: Exception) {
            super.finish()
        }
    }
}
