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

@UnstableApi
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

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
        CHANNEL, EVENT, NETWORK_STREAM
    }

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"
        private const val EXTRA_EVENT = "extra_event"
        private const val EXTRA_SELECTED_LINK_INDEX = "extra_selected_link_index"
        private const val EXTRA_RELATED_CHANNELS = "extra_related_channels"
        private const val ACTION_MEDIA_CONTROL = "com.livetvpro.MEDIA_CONTROL"
        private const val EXTRA_CONTROL_TYPE = "control_type"
        private const val CONTROL_TYPE_PLAY = 1
        private const val CONTROL_TYPE_PAUSE = 2
        private const val CONTROL_TYPE_REWIND = 3
        private const val CONTROL_TYPE_FORWARD = 4

        fun startWithChannel(context: Context, channel: Channel, linkIndex: Int = -1, relatedChannels: ArrayList<Channel>? = null) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel as Parcelable)
                putExtra(EXTRA_SELECTED_LINK_INDEX, linkIndex)
                relatedChannels?.let {
                    putParcelableArrayListExtra(EXTRA_RELATED_CHANNELS, it)
                }
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
            if (context is android.app.Activity) {
                context.overridePendingTransition(0, 0)
            }
        }

        fun startWithEvent(context: Context, event: LiveEvent, linkIndex: Int = -1) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_EVENT, event as Parcelable)
                putExtra(EXTRA_SELECTED_LINK_INDEX, linkIndex)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
            if (context is android.app.Activity) {
                context.overridePendingTransition(0, 0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            postponeEnterTransition()
        }
        
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        val currentOrientation = resources.configuration.orientation
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        
        setupWindowFlags(isLandscape)
        setupSystemUI(isLandscape)
        setupWindowInsets()
        
        if (!isLandscape) {
            exitFullscreen()
        }
        
        parseIntent()

        if (contentType == ContentType.CHANNEL && contentId.isNotEmpty()) {
            viewModel.refreshChannelData(contentId)
        }

        bindControllerViews()
        setupRelatedChannels()
        setupLinksUI()
        configurePlayerInteractions()
        setupLockOverlay()
        
        applyOrientationSettings(isLandscape)
        
        binding.playerView.useController = true
        binding.playerView.controllerAutoShow = false
        
        if (!isLandscape) {
            binding.relatedLoadingProgress.visibility = View.VISIBLE
            binding.relatedChannelsRecycler.visibility = View.GONE
        }
        
        binding.progressBar.visibility = View.VISIBLE
        
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
        
        setupPlayer()
        loadRelatedContent()
        
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
    
    private fun setupWindowFlags(isLandscape: Boolean) {
        if (isLandscape) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = 
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    }

    private fun setupSystemUI(isLandscape: Boolean) {
        if (isLandscape) {
            windowInsetsController.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = 
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
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

    override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
        binding.playerView.hideController()
        return
    }
    
    val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
    
    setupWindowFlags(isLandscape)
    setupSystemUI(isLandscape)
    applyOrientationSettings(isLandscape)
    setSubtitleTextSize()
    
    if (player?.playbackState == Player.STATE_BUFFERING) {
        binding.playerView.hideController()
    }

    binding.root.post {
        binding.root.requestLayout()
        binding.playerContainer.requestLayout()
        binding.playerView.requestLayout()
    }
}

    private fun applyOrientationSettings(isLandscape: Boolean) {
        adjustLayoutForOrientation(isLandscape)
        updateLinksForOrientation(isLandscape)
        
        btnFullscreen?.setImageResource(
            if (isLandscape) R.drawable.ic_fullscreen_exit 
            else R.drawable.ic_fullscreen
        )
    }

    private fun adjustLayoutForOrientation(isLandscape: Boolean) {
        if (isLandscape) {
            enterFullscreen()
            
            val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
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
            
            binding.playerView.controllerAutoShow = false
            binding.playerView.controllerShowTimeoutMs = 3000
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            
        } else {
            
            if (contentType == ContentType.NETWORK_STREAM) {
                val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
                params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                params.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                params.topMargin = 0
                params.bottomMargin = 0
                params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                params.dimensionRatio = null
                
                binding.playerContainer.setPadding(0, 0, 0, 0)
                binding.playerContainer.layoutParams = params
                
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            } else {
                exitFullscreen()
                
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            
            binding.playerView.controllerAutoShow = false
            binding.playerView.controllerShowTimeoutMs = 5000
            
            val linksParams = binding.linksSection.layoutParams as ConstraintLayout.LayoutParams
            linksParams.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            linksParams.height = ConstraintLayout.LayoutParams.WRAP_CONTENT
            linksParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            linksParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            linksParams.topToBottom = binding.playerContainer.id
            linksParams.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            binding.linksSection.layoutParams = linksParams
            
            val relatedParams = binding.relatedChannelsSection.layoutParams as ConstraintLayout.LayoutParams
            relatedParams.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            relatedParams.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            relatedParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            relatedParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            relatedParams.topToBottom = binding.linksSection.id
            relatedParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            binding.relatedChannelsSection.layoutParams = relatedParams
            
        }
        
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

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        isInPipMode = isInPictureInPictureMode
        
        if (isInPipMode) {
            enterPipUIMode()
        } else {
            exitPipUIMode(newConfig)
        }
    }

    private fun enterPipUIMode() {
        binding.playerView.useController = false 
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE
        binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        binding.playerView.hideController()
        
        WindowCompat.setDecorFitsSystemWindows(window, true)
        windowInsetsController.apply {
            show(WindowInsetsCompat.Type.systemBars())
            show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun exitPipUIMode(newConfig: Configuration) {
        userRequestedPip = false
        
        if (isFinishing) {
            return
        }
        
        setSubtitleTextSize()
        
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        setupWindowFlags(isLandscape)
        setupSystemUI(isLandscape)
        
        applyOrientationSettings(isLandscape)
        
        if (!isLandscape) {
            if (allEventLinks.size > 1) {
                binding.linksSection.visibility = View.VISIBLE
            }
            val hasRelated = relatedChannels.isNotEmpty() ||
                (contentType == ContentType.EVENT && ::relatedEventsAdapter.isInitialized)
            if (hasRelated) {
                binding.relatedChannelsSection.visibility = View.VISIBLE
                binding.relatedChannelsRecycler.visibility = View.VISIBLE
                binding.relatedLoadingProgress.visibility = View.GONE
            }
        }
        
        // Rebind controls after exiting PiP to ensure they work properly
        bindControllerViews()
        
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
        val isNetworkStream = intent.getBooleanExtra("IS_NETWORK_STREAM", false)
        
        if (isNetworkStream) {
            contentType = ContentType.NETWORK_STREAM
            contentName = intent.getStringExtra("CHANNEL_NAME") ?: "Network Stream"
            contentId = "network_stream_${System.currentTimeMillis()}"
            
            val streamUrlRaw = intent.getStringExtra("STREAM_URL") ?: ""
            
            if (streamUrlRaw.contains("|")) {
                streamUrl = streamUrlRaw
                
                val parsed = parseStreamUrl(streamUrlRaw)
                allEventLinks = listOf(
                    LiveEventLink(
                        quality = "Network Stream",
                        url = parsed.url,
                        cookie = parsed.headers["Cookie"] ?: "",
                        referer = parsed.headers["Referer"] ?: "",
                        origin = parsed.headers["Origin"] ?: "",
                        userAgent = parsed.headers["User-Agent"] ?: "Default",
                        drmScheme = parsed.drmScheme,
                        drmLicenseUrl = parsed.drmLicenseUrl
                    )
                )
            } else {
                val cookie = intent.getStringExtra("COOKIE") ?: ""
                val referer = intent.getStringExtra("REFERER") ?: ""
                val origin = intent.getStringExtra("ORIGIN") ?: ""
                val drmLicense = intent.getStringExtra("DRM_LICENSE") ?: ""
                val userAgent = intent.getStringExtra("USER_AGENT") ?: "Default"
                val drmScheme = intent.getStringExtra("DRM_SCHEME") ?: "clearkey"
                
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
                
                streamUrl = buildStreamUrl(allEventLinks[0])
            }
            
            currentLinkIndex = 0
            return
        }
        
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
        
        val passedRelatedChannels: List<Channel>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(EXTRA_RELATED_CHANNELS, Channel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(EXTRA_RELATED_CHANNELS)
        }

        if (channelData != null) {
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

        } else if (eventData != null) {
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
        } else {
            finish()
            return
        }
    }

    private fun setupRelatedChannels() {
        if (contentType == ContentType.NETWORK_STREAM) {
            binding.relatedChannelsSection.visibility = View.GONE
            return
        }
        
        if (contentType == ContentType.EVENT) {
            relatedEventsAdapter = LiveEventAdapter(
                context = this, 
                events = emptyList(), 
                preferencesManager = preferencesManager,
                onEventClick = { event, linkIndex ->
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
                    val passedRelatedChannels: List<Channel>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(EXTRA_RELATED_CHANNELS, Channel::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra(EXTRA_RELATED_CHANNELS)
                    }
                    
                    if (passedRelatedChannels != null && passedRelatedChannels.isNotEmpty()) {
                        val filteredChannels = passedRelatedChannels.filter { it.id != channel.id }
                        viewModel.setRelatedChannels(filteredChannels)
                    } else {
                        viewModel.loadRelatedChannels(channel.categoryId, channel.id)
                    }
                }
            }
            ContentType.EVENT -> {
                eventData?.let { event ->
                    viewModel.loadRelatedEvents(event.id)
                }
            }
            ContentType.NETWORK_STREAM -> {
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
        if (player != null) return
        binding.errorView.visibility = View.GONE
        binding.errorText.text = ""
        binding.progressBar.visibility = View.VISIBLE
        
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
                    
                    binding.playerView.hideController()
                    
                    val uri = android.net.Uri.parse(streamInfo.url)
                    val mediaItemBuilder = MediaItem.Builder().setUri(uri)
                    
                    if (streamInfo.url.contains("m3u8", ignoreCase = true) || 
                        streamInfo.url.contains("extension=m3u8", ignoreCase = true)) {
                        mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                    }
                    
                    val mediaItem = mediaItemBuilder.build()
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
                                    
                                    
                                    updatePipParams()
                                }
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar.visibility = View.VISIBLE
                                    binding.errorView.visibility = View.GONE
                                }
                                Player.STATE_ENDED -> {
                                    binding.progressBar.visibility = View.GONE
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
        // Use postDelayed to ensure PlayerView controller is fully inflated
        binding.playerView.postDelayed({
            try {
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
                
                // Initialize icons
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
                
                // Enable all controls
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
                
                tvChannelName?.visibility = View.VISIBLE
                if (contentType == ContentType.NETWORK_STREAM) {
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
                    // Font loading failed, continue with default
                }
                
                setupControlListeners()
            } catch (e: Exception) {
                // If binding fails, retry once after a longer delay
                binding.playerView.postDelayed({
                    bindControllerViews()
                }, 100)
            }
        }, 50)
    }

    private fun setupControlListeners() {
        btnBack?.apply {
            setOnClickListener { if (!isLocked) finish() }
        }
        
        btnPip?.apply {
            setOnClickListener {
                if (!isLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    userRequestedPip = true
                    enterPipMode()
                }
            }
        }
        
        btnSettings?.apply {
            setOnClickListener { if (!isLocked) showPlayerSettingsDialog() }
        }
        
        btnAspectRatio?.apply {
            setOnClickListener { if (!isLocked) toggleAspectRatio() }
        }
        
        btnLock?.apply {
            setOnClickListener { toggleLock() }
        }
        
        btnRewind?.apply {
            setOnClickListener {
                if (!isLocked) {
                    player?.let { p ->
                        val newPosition = p.currentPosition - skipMs
                        p.seekTo(if (newPosition < 0) 0 else newPosition)
                    }
                }
            }
        }
        
        btnPlayPause?.apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                handlePlayPauseClick()
            }
        }
        
        btnForward?.apply {
            setOnClickListener {
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
        
        btnMute?.apply {
            setOnClickListener { if (!isLocked) toggleMute() }
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
    
    private fun exitFullscreen() {
        windowInsetsController.apply {
            show(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
        
        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        params.width = ConstraintLayout.LayoutParams.MATCH_PARENT
        params.height = 0 // 0dp means "Match Constraint"
        params.dimensionRatio = "H,16:9"
        
        params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        
        binding.playerContainer.layoutParams = params

        binding.relatedChannelsSection.visibility = View.VISIBLE
        binding.linksSection.visibility = View.VISIBLE
        binding.playerContainer.visibility = View.VISIBLE
    }
    
    private fun enterFullscreen() {
        windowInsetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        params.width = ConstraintLayout.LayoutParams.MATCH_PARENT
        params.height = ConstraintLayout.LayoutParams.MATCH_PARENT
        params.dimensionRatio = null
        
        binding.playerContainer.layoutParams = params
        
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
            
            // Build PiP actions - these work independently of the UI controls
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
            // PiP update failed, but don't crash - log for debugging if needed
            e.printStackTrace()
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
                        // Update PiP params to reflect new play state
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
                        // Update PiP params to reflect new pause state
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
        
        binding.playerView.hideController()
        
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
