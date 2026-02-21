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
import android.view.ViewGroup
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
import android.annotation.SuppressLint
import android.graphics.Rect
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.livetvpro.ui.player.compose.PlayerControls
import com.livetvpro.ui.player.compose.PlayerControlsState
import com.livetvpro.ui.player.compose.GestureState
import android.media.AudioManager
import com.livetvpro.ui.theme.AppTheme
import com.livetvpro.utils.DeviceUtils
import kotlinx.coroutines.delay

@UnstableApi
@AndroidEntryPoint
class FloatingPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var playerListener: Player.Listener? = null
    
    @javax.inject.Inject
    lateinit var preferencesManager: com.livetvpro.data.local.PreferencesManager

    @javax.inject.Inject
    lateinit var listenerManager: com.livetvpro.utils.NativeListenerManager

    private lateinit var relatedChannelsAdapter: RelatedChannelAdapter
    private var relatedChannels = listOf<Channel>()
    private lateinit var relatedEventsAdapter: LiveEventAdapter
    private lateinit var linkChipAdapter: LinkChipAdapter

    private lateinit var windowInsetsController: WindowInsetsControllerCompat
    
    // Compose controls state
    private val controlsState = PlayerControlsState(initialVisible = false)
    private var gestureVolume: Int = 100      // synced with ExoPlayer on init
    private var gestureBrightness: Int = 0    // 0 = auto

    private var isInPipMode = false
    private var isMuted = false
    private val skipMs = 10_000L
    private var userRequestedPip = false
    
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
    private var intentCategoryId: String? = null
    private var intentSelectedGroup: String? = null

    // Network streams use orientation-based resize mode: fit in portrait, fill in landscape
    private var networkPortraitResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var networkLandscapeResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
    
    private var savedPlaybackPosition: Long = -1L

    enum class ContentType {
        CHANNEL, EVENT, NETWORK_STREAM
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
        private const val EXTRA_CATEGORY_ID = "extra_category_id"
        private const val EXTRA_SELECTED_GROUP = "extra_selected_group"

        fun startWithChannel(context: Context, channel: Channel, linkIndex: Int = -1, categoryId: String? = null, selectedGroup: String? = null) {
            val intent = Intent(context, FloatingPlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel as Parcelable)
                putExtra(EXTRA_SELECTED_LINK_INDEX, linkIndex)
                categoryId?.let { putExtra(EXTRA_CATEGORY_ID, it) }
                selectedGroup?.let { putExtra(EXTRA_SELECTED_GROUP, it) }
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
            if (context is android.app.Activity) {
                context.overridePendingTransition(0, 0)
            }
        }

        fun startWithEvent(context: Context, event: LiveEvent, linkIndex: Int = -1) {
            val intent = Intent(context, FloatingPlayerActivity::class.java).apply {
                putExtra(EXTRA_EVENT, event as Parcelable)
                putExtra(EXTRA_SELECTED_LINK_INDEX, linkIndex)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
            if (context is android.app.Activity) {
                context.overridePendingTransition(0, 0)
            }
        }

        fun startWithNetworkStream(
            context: Context,
            streamUrl: String,
            cookie: String = "",
            referer: String = "",
            origin: String = "",
            drmLicense: String = "",
            userAgent: String = "Default",
            drmScheme: String = "clearkey",
            streamName: String = "Network Stream",
            playbackPosition: Long = -1L
        ) {
            val intent = Intent(context, FloatingPlayerActivity::class.java).apply {
                putExtra("IS_NETWORK_STREAM", true)
                
                putExtra("STREAM_URL", streamUrl)
                putExtra("COOKIE", cookie)
                putExtra("REFERER", referer)
                putExtra("ORIGIN", origin)
                putExtra("DRM_LICENSE", drmLicense)
                putExtra("USER_AGENT", userAgent)
                putExtra("DRM_SCHEME", drmScheme)
                
                putExtra("CHANNEL_NAME", streamName)
                
                if (playbackPosition > 0) {
                    putExtra("playback_position", playbackPosition)
                }
                
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

        // On TV devices: force landscape and stay fullscreen at all times
        if (DeviceUtils.isTvDevice) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        val currentOrientation = resources.configuration.orientation
        val isLandscape = DeviceUtils.isTvDevice || currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        
        setupWindowFlags(isLandscape)
        setupSystemUI(isLandscape)
        setupWindowInsets()

        parseIntent()

        val isTransferredFromFloating = intent.getBooleanExtra("use_transferred_player", false)

        if (!isTransferredFromFloating && contentType == ContentType.CHANNEL && contentId.isNotEmpty()) {
            viewModel.refreshChannelData(contentId)
            viewModel.loadAllChannelsForList(intentCategoryId?.takeIf { it.isNotEmpty() } ?: channelData?.categoryId ?: "")
        }

        // Apply orientation ONCE - enterFullscreen/exitFullscreen inside handle container params
        applyOrientationSettings(isLandscape)

        // Seed gesture volume from the actual device volume so OSD starts correct
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        gestureVolume = if (maxVol > 0) (curVol * 100f / maxVol).toInt() else 100

        setupComposeControls()
        setupRelatedChannels()
        setupLinksUI()
        setupMessageBanner()
        configurePlayerInteractions()
        setupLockOverlay()
        
        binding.playerView.useController = false
        
        if (!isLandscape && !isTransferredFromFloating && !DeviceUtils.isTvDevice) {
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
        if (!isTransferredFromFloating) {
            loadRelatedContent()
        }
        
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
            
            if (freshChannel != null && contentType == ContentType.CHANNEL) {
                channelData = freshChannel
                val categoryId = intentCategoryId?.takeIf { it.isNotEmpty() } ?: freshChannel.categoryId
                if (categoryId.isNotEmpty()) {
                    viewModel.loadRandomRelatedChannels(categoryId, freshChannel.id, intentSelectedGroup)
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

                // Use topMargin on playerContainer (not padding) to respect the status bar in
                // portrait. Margin positions the container below the status bar without shrinking
                // the PlayerView inside it. In landscape, system bars are hidden so margin = 0.
                val params = binding.playerContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                if (!isLandscape) {
                    val topInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        insets.getInsets(WindowInsets.Type.systemBars()).top
                    } else {
                        @Suppress("DEPRECATION") insets.systemWindowInsetTop
                    }
                    params.topMargin = topInset
                } else {
                    params.topMargin = 0
                }
                binding.playerContainer.layoutParams = params
                binding.playerContainer.setPadding(0, 0, 0, 0)
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
    
    val wasControllerVisible = binding.playerView.isControllerFullyVisible
    val isBuffering = player?.playbackState == Player.STATE_BUFFERING
    
    setupWindowFlags(isLandscape)
    setupSystemUI(isLandscape)
    applyOrientationSettings(isLandscape)
    setSubtitleTextSize()
    updateMessageBannerForOrientation(isLandscape)
    
    if (wasControllerVisible && !isBuffering) {
        binding.playerView.postDelayed({
            if (player?.playbackState != Player.STATE_BUFFERING) {
                binding.playerView.showController()
            }
        }, 100)
    } else if (isBuffering) {
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
        applyResizeModeForOrientation(isLandscape)
    }

    /** Applies orientation-based resize mode ONLY for network streams. */
    private fun applyResizeModeForOrientation(isLandscape: Boolean) {
        if (contentType == ContentType.NETWORK_STREAM) {
            binding.playerView.resizeMode =
                if (isLandscape) networkLandscapeResizeMode else networkPortraitResizeMode
        }
        // For CHANNEL and EVENT, XML resize_mode="fill" is used
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
            
            binding.playerView.controllerAutoShow = true
            binding.playerView.controllerShowTimeoutMs = 3000
            
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
            } else {
                exitFullscreen()
            }
            
            binding.playerView.controllerAutoShow = true
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
            relatedParams.topToBottom = binding.messageBannerContainer.id
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
        // Lock overlay removed - now managed by Compose PlayerControls
        // Unlock button removed - now managed by Compose
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
            if (relatedChannels.isNotEmpty()) {
                binding.relatedChannelsSection.visibility = View.VISIBLE
            }
        }
        
        if (wasLockedBeforePip) {
            controlsState.lock()
            wasLockedBeforePip = false
        }
        // If not locked before PiP, controls are already unlocked (default state)
        
        // Always keep PlayerView controller disabled - we use Compose controls
        binding.playerView.useController = false
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
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
            
            val savedPosition = intent.getLongExtra("playback_position", -1L)
            if (savedPosition > 0) {
                this.savedPlaybackPosition = savedPosition
            }
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

        intentCategoryId = intent.getStringExtra(EXTRA_CATEGORY_ID)
        intentSelectedGroup = intent.getStringExtra(EXTRA_SELECTED_GROUP)

        if (eventData != null) {
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
            
        } else if (channelData != null) {
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

        } else {
            finish()
            return
        }
        
        val savedPosition = intent.getLongExtra("playback_position", -1L)
        if (savedPosition > 0) {
            this.savedPlaybackPosition = savedPosition
        }
    }

    private fun setupRelatedChannels() {
        if (contentType == ContentType.NETWORK_STREAM || DeviceUtils.isTvDevice) {
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
            
            binding.relatedChannelsRecycler.layoutManager = GridLayoutManager(this, resources.getInteger(R.integer.event_span_count))
            binding.relatedChannelsRecycler.adapter = relatedEventsAdapter
        } else {
            relatedChannelsAdapter = RelatedChannelAdapter { relatedItem ->
                switchToChannel(relatedItem)
            }
            
            binding.relatedChannelsRecycler.layoutManager = GridLayoutManager(this, resources.getInteger(R.integer.grid_column_count))
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

        val landscapeLinksRecycler = binding.playerContainer.findViewById<RecyclerView>(R.id.exo_links_recycler)
        landscapeLinksRecycler?.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        // The RecyclerView is inside player_container (ConstraintLayout) with width="0dp"
        // pinned start+end = full width, which is why chips appear centered.
        // Fix: WRAP_CONTENT + remove endToEnd constraint so the view only takes the
        // width of its chips and naturally sits at the start edge.
        // paddingStart aligns chips with title text:
        //   Compose bar paddingStart(4dp) + back button(40dp) + title marginStart(12dp) = 56dp.
        landscapeLinksRecycler?.let { rv ->
            val params = rv.layoutParams as? ConstraintLayout.LayoutParams
            if (params != null) {
                params.width = ConstraintLayout.LayoutParams.WRAP_CONTENT
                params.endToEnd = ConstraintLayout.LayoutParams.UNSET
                rv.layoutParams = params
            }
            val startPx = (56 * resources.displayMetrics.density).toInt()
            rv.setPaddingRelative(startPx, rv.paddingTop, rv.paddingEnd, rv.paddingBottom)
        }

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
        if (!::linkChipAdapter.isInitialized) return
        val landscapeLinksRecycler = binding.playerContainer.findViewById<RecyclerView>(R.id.exo_links_recycler)

        if (allEventLinks.size > 1) {
            if (isLandscape) {
                binding.linksSection.visibility = View.GONE
                // Respect controls state: chips only visible when controls are shown and not locked
                val chipsVisible = controlsState.isVisible && !controlsState.isLocked
                landscapeLinksRecycler?.visibility = if (chipsVisible) View.VISIBLE else View.GONE

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
        if (DeviceUtils.isTvDevice) return
        when (contentType) {
            ContentType.CHANNEL -> {
                channelData?.let { channel ->
                    val categoryId = intentCategoryId?.takeIf { it.isNotEmpty() } ?: channel.categoryId
                    viewModel.loadRandomRelatedChannels(categoryId, channel.id, intentSelectedGroup)
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
        
        setupPlayer()
        setupLinksUI()
        
        binding.relatedLoadingProgress.visibility = View.VISIBLE
        binding.relatedChannelsRecycler.visibility = View.GONE
        val categoryId = intentCategoryId?.takeIf { it.isNotEmpty() } ?: newChannel.categoryId
        viewModel.loadRandomRelatedChannels(categoryId, newChannel.id, intentSelectedGroup)
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

        if (::linkChipAdapter.isInitialized) {
            linkChipAdapter.setSelectedPosition(position)
        }

        val landscapeLinksRecycler = binding.playerContainer.findViewById<RecyclerView>(R.id.exo_links_recycler)
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
        FloatingPlayerService.showAll(this)
        // mainHandler removed - no longer needed
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
        // Split on | first, then also handle & as separator — but only between top-level params,
        // not inside URL values (which may contain & in query strings).
        // Strategy: split on | first, then for each part check if it looks like a URL value
        // that contains &; if not, split further on &.
        val parts = buildList {
            for (segment in rawParams.split("|")) {
                val eqIdx = segment.indexOf('=')
                val value = if (eqIdx != -1) segment.substring(eqIdx + 1) else ""
                if (value.startsWith("http://", ignoreCase = true) ||
                    value.startsWith("https://", ignoreCase = true)) {
                    add(segment)  // keep URL values intact — don't split on their & chars
                } else {
                    addAll(segment.split("&"))  // non-URL params: & is a separator
                }
            }
        }

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
                    } else if (value.trimStart().startsWith("{")) {
                        drmLicenseUrl = value   // raw JWK JSON
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
        val useTransferredPlayer = intent.getBooleanExtra("use_transferred_player", false)
        
        if (useTransferredPlayer) {
            val (transferredPlayer, transferredUrl, transferredName) = PlayerHolder.retrievePlayer()
            
            if (transferredPlayer != null) {
                
                binding.progressBar.visibility = View.GONE
                binding.errorView.visibility = View.GONE
                
                player = transferredPlayer
                binding.playerView.player = player
                
                PlayerHolder.clearReferences()
                
                // bindControllerViews() // REMOVED: Compose handles view binding
                configurePlayerInteractions()
                setupLockOverlay()
                
                playerListener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                // updatePlayPauseIcon removed - Compose handles icons
                                binding.progressBar.visibility = View.GONE
                                binding.errorView.visibility = View.GONE
                                updatePipParams()
                            }
                            Player.STATE_BUFFERING -> {
                                binding.progressBar.visibility = View.VISIBLE
                                binding.errorView.visibility = View.GONE
                                binding.playerView.hideController()
                            }
                            Player.STATE_ENDED -> {
                                binding.progressBar.visibility = View.GONE
                                binding.playerView.showController()
                            }
                            Player.STATE_IDLE -> {}
                        }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // updatePlayPauseIcon removed - Compose handles icons
                        if (isInPipMode) updatePipParams()
                    }
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
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
                            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when {
                                error.message?.contains("403") == true -> "Access Denied"
                                error.message?.contains("404") == true -> "Stream Not Found"
                                else -> "Playback Error"
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
                transferredPlayer.addListener(playerListener!!)
                
                return
            }
        }
        
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
                        } else if (streamInfo.drmLicenseUrl?.trimStart()?.startsWith("{") == true) {
                            createClearKeyDrmManagerFromJwk(streamInfo.drmLicenseUrl)
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
                    
                    // FIX: Add DRM configuration to MediaItem if DRM is used
                    if (streamInfo.drmScheme != null && streamInfo.drmLicenseUrl != null) {
                        val drmUuid = when (streamInfo.drmScheme) {
                            "widevine" -> C.WIDEVINE_UUID
                            "playready" -> C.PLAYREADY_UUID
                            "clearkey" -> java.util.UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")
                            else -> C.WIDEVINE_UUID
                        }
                        
                        val drmConfigBuilder = MediaItem.DrmConfiguration.Builder(drmUuid)
                            .setLicenseUri(streamInfo.drmLicenseUrl)
                        
                        mediaItemBuilder.setDrmConfiguration(drmConfigBuilder.build())
                    }
                    
                    val mediaItem = mediaItemBuilder.build()
                    exo.setMediaItem(mediaItem)
                    exo.prepare()
                    
                    if (savedPlaybackPosition > 0) {
                        exo.seekTo(savedPlaybackPosition)
                        savedPlaybackPosition = -1L
                    }
                    
                    exo.playWhenReady = true

                    playerListener = object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    // updatePlayPauseIcon removed - Compose handles icons
                                    binding.progressBar.visibility = View.GONE
                                    binding.errorView.visibility = View.GONE

                                    updatePipParams()
                                }
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar.visibility = View.VISIBLE
                                    binding.errorView.visibility = View.GONE
                                    
                                    binding.playerView.hideController()
                                }
                                Player.STATE_ENDED -> {
                                    binding.progressBar.visibility = View.GONE
                                    binding.playerView.showController()
                                }
                                Player.STATE_IDLE -> {}
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            // updatePlayPauseIcon removed - Compose handles icons
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

    private fun createClearKeyDrmManagerFromJwk(jwkJson: String): DefaultDrmSessionManager? {
        return try {
            val clearKeyUuid = UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")
            val drmCallback = LocalMediaDrmCallback(jwkJson.toByteArray())
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

    // OBSOLETE: // updatePlayPauseIcon removed - Compose handles icons
    // toggleAspectRatio(), showPlayerSettingsDialog() deleted - duplicates of functions in setupComposeControls

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            // Icon updated automatically by Compose controls
        }
    }

    private fun setupComposeControls() {
        binding.playerControlsCompose.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    val isPlaying by produceState(initialValue = false, player) {
                        while (true) {
                            value = player?.isPlaying == true
                            delay(100)
                        }
                    }
                    
                    val currentPosition by produceState(initialValue = 0L) {
                        while (true) {
                            value = player?.currentPosition ?: 0L
                            delay(100)
                        }
                    }
                    
                    val duration = player?.duration ?: 0L
                    val bufferedPosition = player?.bufferedPosition ?: 0L
                    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

                    // Channel list panel state
                    var showChannelList by remember { mutableStateOf(false) }
                    val channelListItems by viewModel.channelListItems.observeAsState(emptyList())
                    val isChannelListAvailable = contentType == ContentType.CHANNEL && channelListItems.isNotEmpty() && (isLandscape || DeviceUtils.isTvDevice)

                    // Sync link chips visibility with controls in landscape.
                    // Chips show only when controls are visible AND not locked.
                    LaunchedEffect(controlsState.isVisible, controlsState.isLocked, isLandscape, showChannelList) {
                        if (isLandscape) {
                            val landscapeLinksRecycler = binding.playerContainer.findViewById<RecyclerView>(R.id.exo_links_recycler)
                            val chipsVisible = controlsState.isVisible && !controlsState.isLocked && !showChannelList
                            landscapeLinksRecycler?.visibility = if (chipsVisible) View.VISIBLE else View.GONE
                        }
                    }
                    
                    Box(modifier = Modifier.fillMaxSize()) {
                        PlayerControls(
                            state = controlsState,
                            isPlaying = isPlaying,
                            isMuted = isMuted,
                            currentPosition = currentPosition,
                            duration = duration,
                            bufferedPosition = bufferedPosition,
                            channelName = contentName,
                            showPipButton = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                isPipSupported()
                            } else {
                                false
                            },
                            showAspectRatioButton = true,
                            isLandscape = isLandscape,
                            isTvMode = DeviceUtils.isTvDevice,
                            isChannelListAvailable = isChannelListAvailable,
                            onBackClick = { finish() },
                            onPipClick = {
                                // Transfer player to floating service
                                val currentChannel = channelData
                                val currentEvent = eventData
                                val currentPlayer = player
                                val currentStreamUrl = streamUrl
                                val currentName = contentName
                                val sourceInstanceId = intent.getStringExtra("source_instance_id")

                                if (currentPlayer != null && contentType == ContentType.NETWORK_STREAM) {
                                    // Network stream: transfer the live player to the floating overlay
                                    PlayerHolder.transferPlayer(currentPlayer, currentStreamUrl, currentName)

                                    val link = allEventLinks.getOrNull(currentLinkIndex)
                                    val serviceIntent = Intent(this@FloatingPlayerActivity, FloatingPlayerService::class.java).apply {
                                        putExtra("IS_NETWORK_STREAM", true)
                                        putExtra("use_transferred_player", true)
                                        putExtra("STREAM_URL", link?.url ?: currentStreamUrl)
                                        putExtra("COOKIE", link?.cookie ?: "")
                                        putExtra("REFERER", link?.referer ?: "")
                                        putExtra("ORIGIN", link?.origin ?: "")
                                        putExtra("DRM_LICENSE", link?.drmLicenseUrl ?: "")
                                        putExtra("USER_AGENT", link?.userAgent ?: "Default")
                                        putExtra("DRM_SCHEME", link?.drmScheme ?: "clearkey")
                                        putExtra("CHANNEL_NAME", currentName)
                                        putExtra(FloatingPlayerService.EXTRA_RESTORE_POSITION, true)
                                        if (sourceInstanceId != null) {
                                            putExtra(FloatingPlayerService.EXTRA_INSTANCE_ID, sourceInstanceId)
                                        }
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        startForegroundService(serviceIntent)
                                    } else {
                                        startService(serviceIntent)
                                    }

                                    player = null
                                    finish()

                                } else if (currentPlayer != null && (currentChannel != null || currentEvent != null)) {
                                    PlayerHolder.transferPlayer(currentPlayer, currentStreamUrl, currentName)

                                    val serviceIntent = Intent(this@FloatingPlayerActivity, FloatingPlayerService::class.java).apply {
                                        if (currentChannel != null) {
                                            putExtra(FloatingPlayerService.EXTRA_CHANNEL, currentChannel)
                                        }
                                        if (currentEvent != null) {
                                            putExtra(FloatingPlayerService.EXTRA_EVENT, currentEvent)
                                        }
                                        putExtra(FloatingPlayerService.EXTRA_RESTORE_POSITION, true)
                                        putExtra("use_transferred_player", true)
                                        if (sourceInstanceId != null) {
                                            putExtra(FloatingPlayerService.EXTRA_INSTANCE_ID, sourceInstanceId)
                                        }
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        startForegroundService(serviceIntent)
                                    } else {
                                        startService(serviceIntent)
                                    }

                                    player = null
                                    finish()
                                }
                            },
                            onSettingsClick = { showSettingsDialog() },
                            onMuteClick = { toggleMute() },
                            onLockClick = { locked -> },
                            onChannelListClick = { showChannelList = true },
                            onPlayPauseClick = {
                                player?.let {
                                    val hasError = binding.errorView.visibility == View.VISIBLE
                                    val hasEnded = it.playbackState == Player.STATE_ENDED
                                    
                                    if (hasError || hasEnded) {
                                        retryPlayback()
                                    } else {
                                        if (it.isPlaying) it.pause() else it.play()
                                    }
                                }
                            },
                            onSeek = { position ->
                                player?.seekTo(position)
                            },
                            onRewindClick = {
                                player?.let {
                                    val newPosition = it.currentPosition - skipMs
                                    it.seekTo(if (newPosition < 0) 0 else newPosition)
                                }
                            },
                            onForwardClick = {
                                player?.let {
                                    val newPosition = it.currentPosition + skipMs
                                    if (it.isCurrentWindowLive && it.duration != C.TIME_UNSET && newPosition >= it.duration) {
                                        it.seekTo(it.duration)
                                    } else {
                                        it.seekTo(newPosition)
                                    }
                                }
                            },
                            onAspectRatioClick = {
                                // Only allow aspect ratio change in landscape or network streams
                                if (isLandscape || contentType == ContentType.NETWORK_STREAM) {
                                    cycleAspectRatio()
                                }
                            },
                            onFullscreenClick = { toggleFullscreen() },
                            onVolumeSwipe = { vol ->
                                gestureVolume = vol
                                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val target = (vol / 100f * max).toInt()
                                audioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    target,
                                    0 // no UI flag — our OSD replaces it
                                )
                            },
                            onBrightnessSwipe = { bri ->
                                gestureBrightness = bri
                                val lp = window.attributes
                                lp.screenBrightness = if (bri == 0) {
                                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                                } else {
                                    bri / 100f
                                }
                                window.attributes = lp
                            },
                            initialVolume = gestureVolume,
                            initialBrightness = gestureBrightness,
                        )

                        // Channel list panel — landscape or TV always, rendered on top of controls
                        if ((isLandscape || DeviceUtils.isTvDevice) && isChannelListAvailable) {
                            com.livetvpro.ui.player.compose.ChannelListPanel(
                                visible = showChannelList,
                                channels = channelListItems,
                                currentChannelId = contentId,
                                onChannelClick = { channel -> switchToChannel(channel) },
                                onDismiss = { showChannelList = false },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }

    private fun cycleAspectRatio() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val current = binding.playerView.resizeMode
        val next = when (current) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT   -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM  -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL  -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            else                                     -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        // Persist the chosen mode per orientation for network streams
        if (contentType == ContentType.NETWORK_STREAM) {
            if (isLandscape) networkLandscapeResizeMode = next
            else networkPortraitResizeMode = next
        }
        binding.playerView.resizeMode = next
    }

    private fun showSettingsDialog() {
        val exoPlayer = player ?: return
        if (isFinishing || isDestroyed) return
        try {
            val dialog = com.livetvpro.ui.player.settings.PlayerSettingsDialog(this, exoPlayer)
            dialog.show()
        } catch (e: Exception) {
            android.util.Log.e("FloatingPlayerActivity", "Error showing settings dialog", e)
        }
    }

    private fun setupMessageBanner() {
        val message = listenerManager.getMessage()
        if (message.isNotBlank()) {
            binding.tvMessageBanner.text = message
            binding.tvMessageBanner.isSelected = true
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            binding.messageBannerContainer.visibility = if (isLandscape) View.GONE else View.VISIBLE
            val url = listenerManager.getMessageUrl()
            if (url.isNotBlank()) {
                binding.tvMessageBanner.setOnClickListener {
                    try {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    } catch (e: Exception) { }
                }
            } else {
                binding.tvMessageBanner.setOnClickListener(null)
            }
        }
    }

    private fun updateMessageBannerForOrientation(isLandscape: Boolean) {
        if (binding.tvMessageBanner.text.isNotBlank()) {
            binding.messageBannerContainer.visibility = if (isLandscape) View.GONE else View.VISIBLE
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
        // Lock overlay functionality now handled by Compose PlayerControls
        // The controlsState manages the lock state
        // Lock overlay removed - now managed by Compose PlayerControls
        // Unlock button removed - now managed by Compose
    }

    private fun showUnlockButton() {
        // Unlock button now managed by Compose PlayerControls
        // Tapping the screen when locked shows the unlock button automatically
    }

    private fun hideUnlockButton() {
        // Unlock button now managed by Compose PlayerControls
    }

    private fun toggleLock() {
        if (controlsState.isLocked) {
            // Need a scope — borrow lifecycleScope for this Activity-level call
            controlsState.unlock(lifecycleScope)
        } else {
            controlsState.lock()
        }
        binding.playerView.useController = false
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
        params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
        params.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
        params.dimensionRatio = "H,16:9"
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        binding.playerContainer.layoutParams = params
        binding.playerContainer.visibility = View.VISIBLE

        // Restore sections based on actual data state.
        // Observers only fire once when data arrives, so we must manually
        // restore visibility on rotation back to portrait.
        if (allEventLinks.size > 1) {
            binding.linksSection.visibility = View.VISIBLE
        }
        val hasRelated = relatedChannels.isNotEmpty() ||
            (contentType == ContentType.EVENT && ::relatedEventsAdapter.isInitialized)
        if (hasRelated) {
            binding.relatedChannelsSection.visibility = View.VISIBLE
        }
    }

    private fun enterFullscreen() {
        windowInsetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Clear portrait topMargin and padding so landscape is truly edge-to-edge
        binding.root.setPadding(0, 0, 0, 0)
        binding.playerContainer.setPadding(0, 0, 0, 0)

        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
        params.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
        params.topMargin = 0
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
        // Lock overlay removed - now managed by Compose PlayerControls
        // Unlock button removed - now managed by Compose

        setSubtitleTextSizePiP()

        updatePipParams(enter = true)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun isPipSupported(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        } else {
            false
        }
    }

    private fun updatePipParams(enter: Boolean = false): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        
        return try {
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
            
            val params = builder.build()
            
            if (enter) {
                enterPictureInPictureMode(params)
            } else {
                setPictureInPictureParams(params)
            }
            
            params
        } catch (e: Exception) {
            null
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
        
        binding.playerView.hideController()
        
        player?.release()
        player = null
        setupPlayer()
    }

    override fun finish() {
        try {
            FloatingPlayerService.showAll(this)

            if (player != null) {
                releasePlayer()
            }
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
