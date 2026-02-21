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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.annotation.SuppressLint
import android.graphics.Rect
import android.media.AudioManager
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.databinding.ActivityPlayerBinding
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import com.livetvpro.ui.adapters.LiveEventAdapter
import com.livetvpro.ui.adapters.LinkChipAdapter
import com.livetvpro.data.models.LiveEventLink
import com.livetvpro.ui.player.compose.PlayerControls
import com.livetvpro.ui.player.compose.PlayerControlsState
import com.livetvpro.ui.theme.AppTheme
import com.livetvpro.utils.DeviceUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@UnstableApi
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding

    private val mainHandler = Handler(Looper.getMainLooper())
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

    // ── Compose controls state ──────────────────────────────────────────────────
    private val controlsState = PlayerControlsState()
    private var gestureVolume: Int = 100
    private var gestureBrightness: Int = 0

    // ── Channel list state hoisted to activity level ────────────────────────────
    // This allows dispatchKeyEvent (and any future deep link / voice command) to
    // open / close the panel without going through the Compose lambda.
    private val _showChannelList = mutableStateOf(false)

    // ── Number-pad digit accumulation (channel-number jump) ────────────────────
    // Exposed as Compose state so PlayerControls can render the digit OSD
    private val _digitBufferState = mutableStateOf("")
    private var digitBuffer: String
        get()      = _digitBufferState.value
        set(value) { _digitBufferState.value = value }
    private val digitHandler = Handler(Looper.getMainLooper())
    private val digitCommitRunnable = Runnable { commitDigitBuffer() }
    private val DIGIT_COMMIT_DELAY_MS = 1500L   // wait 1.5 s after last digit

    // ── MediaSession (enables online / IP remotes, Google Assistant, etc.) ──────
    private var mediaSession: MediaSession? = null

    private var isInPipMode = false
    private var isMuted by mutableStateOf(false)
    private val skipMs = 10_000L

    private var networkPortraitResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var networkLandscapeResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL

    private var pipReceiver: BroadcastReceiver? = null
    private var wasLockedBeforePip = false
    private var pipRect: Rect? = null
    val isPipSupported by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) false
        else packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

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

    enum class ContentType { CHANNEL, EVENT, NETWORK_STREAM }

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"
        private const val EXTRA_EVENT = "extra_event"
        private const val EXTRA_SELECTED_LINK_INDEX = "extra_selected_link_index"
        private const val EXTRA_RELATED_CHANNELS = "extra_related_channels"
        private const val EXTRA_CATEGORY_ID = "extra_category_id"
        private const val EXTRA_SELECTED_GROUP = "extra_selected_group"

        private const val PIP_INTENTS_FILTER = "com.livetvpro.PIP_CONTROL"
        private const val PIP_INTENT_ACTION = "pip_action"
        private const val PIP_PLAY = 1
        private const val PIP_PAUSE = 2
        private const val PIP_FR = 3
        private const val PIP_FF = 4

        fun startWithChannel(
            context: Context,
            channel: Channel,
            linkIndex: Int = -1,
            relatedChannels: ArrayList<Channel>? = null,
            categoryId: String? = null,
            selectedGroup: String? = null
        ) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel as Parcelable)
                putExtra(EXTRA_SELECTED_LINK_INDEX, linkIndex)
                relatedChannels?.let { putParcelableArrayListExtra(EXTRA_RELATED_CHANNELS, it) }
                categoryId?.let { putExtra(EXTRA_CATEGORY_ID, it) }
                selectedGroup?.let { putExtra(EXTRA_SELECTED_GROUP, it) }
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
            if (context is android.app.Activity) context.overridePendingTransition(0, 0)
        }

        fun startWithEvent(context: Context, event: LiveEvent, linkIndex: Int = -1) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_EVENT, event as Parcelable)
                putExtra(EXTRA_SELECTED_LINK_INDEX, linkIndex)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
            if (context is android.app.Activity) context.overridePendingTransition(0, 0)
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // onCreate
    // ════════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) postponeEnterTransition()

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (DeviceUtils.isTvDevice) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            binding.root.findViewById<android.widget.Button>(R.id.btn_error_retry)
                ?.setOnClickListener { retryPlayback() }
        }

        val currentOrientation = resources.configuration.orientation
        val isLandscape = DeviceUtils.isTvDevice || currentOrientation == Configuration.ORIENTATION_LANDSCAPE

        setupWindowFlags(isLandscape)
        setupSystemUI(isLandscape)
        setupWindowInsets()

        parseIntent()
        savedInstanceState?.let { restoreFromBundle(it) }

        if (contentType == ContentType.CHANNEL && contentId.isNotEmpty()) {
            viewModel.refreshChannelData(contentId)
            viewModel.loadAllChannelsForList(
                intentCategoryId?.takeIf { it.isNotEmpty() } ?: channelData?.categoryId ?: ""
            )
        }

        applyResizeModeForOrientation(isLandscape)
        applyOrientationSettings(isLandscape)

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        gestureVolume = if (maxVol > 0) (curVol * 100f / maxVol).toInt() else 100

        setupComposeControls()
        setupRelatedChannels()
        setupLinksUI()
        setupMessageBanner()
        configurePlayerInteractions()

        binding.playerView.useController = false

        if (!isLandscape && !DeviceUtils.isTvDevice) {
            binding.relatedLoadingProgress.visibility = View.VISIBLE
            binding.relatedChannelsRecycler.visibility = View.GONE
        }

        binding.progressBar.visibility = View.VISIBLE

        binding.root.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    binding.root.viewTreeObserver.removeOnPreDrawListener(this)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                        startPostponedEnterTransition()
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
                            quality = it.quality, url = it.url,
                            cookie = it.cookie, referer = it.referer,
                            origin = it.origin, userAgent = it.userAgent,
                            drmScheme = it.drmScheme, drmLicenseUrl = it.drmLicenseUrl
                        )
                    }
                    val matchIndex = allEventLinks.indexOfFirst { it.url == streamUrl }
                    if (matchIndex != -1) currentLinkIndex = matchIndex
                    val isLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    updateLinksForOrientation(isLand)
                }
            }
        }

        viewModel.relatedItems.observe(this) { channels ->
            relatedChannels = channels
            relatedChannelsAdapter.submitList(channels)
            binding.relatedChannelsSection.visibility =
                if (channels.isEmpty()) View.GONE else View.VISIBLE
            binding.relatedLoadingProgress.visibility = View.GONE
            binding.relatedChannelsRecycler.visibility = View.VISIBLE
        }

        viewModel.relatedLiveEvents.observe(this) { liveEvents ->
            if (contentType == ContentType.EVENT && ::relatedEventsAdapter.isInitialized) {
                relatedEventsAdapter.updateData(liveEvents)
                binding.relatedChannelsSection.visibility =
                    if (liveEvents.isEmpty()) View.GONE else View.VISIBLE
                binding.relatedLoadingProgress.visibility = View.GONE
                binding.relatedChannelsRecycler.visibility = View.VISIBLE
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPipSupported)
            setPictureInPictureParams(updatePipParams())
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Remote key handling — covers ALL remote types
    //
    //  Physical TV remotes  → standard KEYCODE_DPAD_* / KEYCODE_MEDIA_* events
    //  Fire TV remote       → same keycodes via standard Android dispatch
    //  Online / IP remotes  → go through MediaSession callbacks (set up below)
    //                         OR arrive as injected KeyEvents (ADB / CetusPlay /
    //                         AnyMote / etc.) — all land here via dispatchKeyEvent
    //  Number-pad (0-9)     → digit buffer → jump to channel by position
    //  CH+ / CH-            → previous / next channel in list
    //  MENU / PROG_RED      → toggle channel list panel
    //  Coloured buttons     → Green=play/pause  Yellow=mute  Blue=settings
    // ════════════════════════════════════════════════════════════════════════════

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // While in PiP mode, ignore everything — the OS handles it via PendingIntents
        if (isInPipMode) return super.dispatchKeyEvent(event)

        // Only act on ACTION_DOWN to avoid double-firing
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        val channelListOpen = _showChannelList.value
        val channelListItems = viewModel.channelListItems.value ?: emptyList()

        when (event.keyCode) {

            // ── OK / SELECT / ENTER ──────────────────────────────────────────
            // If controls are hidden → show them.
            // If controls are visible and channel list is available → open list.
            // If channel list is already open → close it.
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                return when {
                    channelListOpen -> {
                        _showChannelList.value = false
                        true
                    }
                    !controlsState.isVisible && !controlsState.isLocked -> {
                        controlsState.show(lifecycleScope)
                        true
                    }
                    controlsState.isVisible
                            && !controlsState.isLocked
                            && contentType == ContentType.CHANNEL
                            && channelListItems.isNotEmpty() -> {
                        _showChannelList.value = true
                        true
                    }
                    else -> super.dispatchKeyEvent(event)
                }
            }

            // ── BACK ─────────────────────────────────────────────────────────
            KeyEvent.KEYCODE_BACK -> {
                return when {
                    channelListOpen -> {
                        _showChannelList.value = false
                        true
                    }
                    controlsState.isLocked -> {
                        // Show the lock overlay so user can unlock
                        true
                    }
                    else -> super.dispatchKeyEvent(event)
                }
            }

            // ── MENU / PROG_RED → toggle channel list ────────────────────────
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_PROG_RED -> {
                return if (contentType == ContentType.CHANNEL && channelListItems.isNotEmpty()) {
                    _showChannelList.value = !channelListOpen
                    true
                } else {
                    super.dispatchKeyEvent(event)
                }
            }

            // ── CH+ / CHANNEL_UP → next channel ─────────────────────────────
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP -> {
                navigateChannelRelative(+1, channelListItems)
                return true
            }

            // ── CH- / CHANNEL_DOWN → previous channel ────────────────────────
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                navigateChannelRelative(-1, channelListItems)
                return true
            }

            // ── MEDIA PLAY / PAUSE / PLAY_PAUSE ─────────────────────────────
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_PROG_GREEN -> {       // Green = play/pause on many remotes
                togglePlayPause()
                return true
            }

            // ── MEDIA REWIND / FAST-FORWARD ──────────────────────────────────
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                player?.let {
                    if (event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                        it.seekTo(maxOf(0L, it.currentPosition - skipMs))
                    } else {
                        val newPos = it.currentPosition + skipMs
                        if (it.isCurrentMediaItemLive && it.duration != C.TIME_UNSET)
                            it.seekTo(minOf(newPos, it.duration))
                        else
                            it.seekTo(newPos)
                    }
                }
                return true
            }

            // ── MEDIA NEXT / PREVIOUS (headsets, Bluetooth remotes) ──────────
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                navigateChannelRelative(+1, channelListItems)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                navigateChannelRelative(-1, channelListItems)
                return true
            }

            // ── MUTE / VOLUME MUTE ───────────────────────────────────────────
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE,
            KeyEvent.KEYCODE_PROG_YELLOW -> {      // Yellow = mute on many remotes
                toggleMute()
                return true
            }

            // ── BLUE → settings ──────────────────────────────────────────────
            KeyEvent.KEYCODE_PROG_BLUE -> {
                showSettingsDialog()
                return true
            }

            // ── NUMBER PAD 0-9 → channel-number jump ─────────────────────────
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> { appendDigit("0"); return true }
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> { appendDigit("1"); return true }
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> { appendDigit("2"); return true }
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> { appendDigit("3"); return true }
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> { appendDigit("4"); return true }
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> { appendDigit("5"); return true }
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> { appendDigit("6"); return true }
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> { appendDigit("7"); return true }
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> { appendDigit("8"); return true }
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> { appendDigit("9"); return true }

            else -> return super.dispatchKeyEvent(event)
        }
    }

    // ── Digit buffer helpers ──────────────────────────────────────────────────

    private fun appendDigit(digit: String) {
        digitBuffer += digit
        // Reset the auto-commit timer on every new digit
        digitHandler.removeCallbacks(digitCommitRunnable)
        digitHandler.postDelayed(digitCommitRunnable, DIGIT_COMMIT_DELAY_MS)
        // Show controls so user can see what they typed (optional UX hint)
        controlsState.show(lifecycleScope)
    }

    private fun commitDigitBuffer() {
        val number = digitBuffer.toIntOrNull() ?: run { digitBuffer = ""; return }
        digitBuffer = ""
        val channels = viewModel.channelListItems.value ?: return
        // Channel numbers are 1-based (matching ChannelItemRow serialNumber)
        val idx = number - 1
        if (idx in channels.indices) switchToChannel(channels[idx])
    }

    // ── CH+/CH- navigation ────────────────────────────────────────────────────

    private fun navigateChannelRelative(delta: Int, channels: List<Channel>) {
        if (channels.isEmpty()) return
        val currentIdx = channels.indexOfFirst { it.id == contentId }
        val nextIdx = when {
            currentIdx == -1 -> 0
            else -> (currentIdx + delta).coerceIn(0, channels.lastIndex)
        }
        if (nextIdx != currentIdx) switchToChannel(channels[nextIdx])
    }

    // ── Play/pause helper (used by key handler + Compose) ────────────────────

    private fun togglePlayPause() {
        player?.let {
            val hasError = binding.errorView.visibility == View.VISIBLE
            val hasEnded = it.playbackState == Player.STATE_ENDED
            if (hasError || hasEnded) {
                retryPlayback()
            } else {
                val effectivelyPlaying = it.isPlaying ||
                        (it.playbackState == Player.STATE_BUFFERING && it.playWhenReady)
                if (effectivelyPlaying) it.pause() else it.play()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // MediaSession — makes the player visible to online / IP remotes,
    //                Google Assistant, Bluetooth headsets, Android Auto, etc.
    // ════════════════════════════════════════════════════════════════════════════

    private fun setupMediaSession() {
        val exo = player ?: return
        mediaSession = MediaSession.Builder(this, exo)
            .setCallback(object : MediaSession.Callback {
                // The default callback already wires all standard transport controls
                // (play, pause, seekTo, skipToNext, skipToPrevious) directly to ExoPlayer.
                // We only override the ones that need custom app behaviour.

                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    // Accept connections from all controllers (online remote apps, etc.)
                    return MediaSession.ConnectionResult.accept(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
                        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                    )
                }
            })
            .build()
    }

    private fun releaseMediaSession() {
        mediaSession?.release()
        mediaSession = null
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Window / system UI helpers (unchanged)
    // ════════════════════════════════════════════════════════════════════════════

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
            binding.root.setOnApplyWindowInsetsListener { _, insets ->
                val isLandscape =
                    resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val params =
                    binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
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

    // ════════════════════════════════════════════════════════════════════════════
    // Configuration change
    // ════════════════════════════════════════════════════════════════════════════

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            binding.playerView.hideController()
            return
        }

        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE

        setupWindowFlags(isLandscape)
        setupSystemUI(isLandscape)
        applyResizeModeForOrientation(isLandscape)
        applyOrientationSettings(isLandscape)
        setSubtitleTextSize()
        updateMessageBannerForOrientation(isLandscape)

        if (player?.playbackState == Player.STATE_BUFFERING)
            binding.playerView.hideController()

        binding.root.post {
            binding.root.requestLayout()
            binding.playerContainer.requestLayout()
            binding.playerView.requestLayout()
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Compose controls — showChannelList now reads from hoisted activity state
    // ════════════════════════════════════════════════════════════════════════════

    private fun setupComposeControls() {
        binding.playerControlsCompose.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    val isPlaying by produceState(initialValue = false, player) {
                        while (true) {
                            val p = player
                            value = if (p != null && p.playbackState == Player.STATE_BUFFERING)
                                p.playWhenReady
                            else
                                p?.isPlaying == true
                            delay(100)
                        }
                    }
                    val currentPosition by produceState(initialValue = 0L) {
                        while (true) { value = player?.currentPosition ?: 0L; delay(100) }
                    }
                    val duration by produceState(initialValue = 0L, player) {
                        while (true) {
                            value = player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L
                            delay(100)
                        }
                    }
                    val bufferedPosition by produceState(initialValue = 0L, player) {
                        while (true) { value = player?.bufferedPosition ?: 0L; delay(100) }
                    }

                    val isLandscape =
                        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

                    // ── Hoisted channel-list state ────────────────────────────
                    var showChannelList by _showChannelList
                    val channelListItems by viewModel.channelListItems.observeAsState(emptyList())
                    val isChannelListAvailable = contentType == ContentType.CHANNEL
                            && channelListItems.isNotEmpty()
                            && (isLandscape || DeviceUtils.isTvDevice)

                    // Sync link chips visibility
                    LaunchedEffect(
                        controlsState.isVisible, controlsState.isLocked,
                        isLandscape, showChannelList
                    ) {
                        if (isLandscape) {
                            val lr = binding.playerContainer
                                .findViewById<RecyclerView>(R.id.exo_links_recycler)
                            val chipsVisible =
                                controlsState.isVisible && !controlsState.isLocked && !showChannelList
                            lr?.visibility = if (chipsVisible) View.VISIBLE else View.GONE
                        }
                    }

                    // Track pipRect
                    DisposableEffect(Unit) {
                        val listener = ViewTreeObserver.OnGlobalLayoutListener {
                            val rect = Rect()
                            binding.playerView.getGlobalVisibleRect(rect)
                            if (!rect.isEmpty) pipRect = rect
                        }
                        binding.playerView.viewTreeObserver.addOnGlobalLayoutListener(listener)
                        onDispose {
                            binding.playerView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
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
                            showPipButton = isPipSupported,
                            showAspectRatioButton = true,
                            isLandscape = isLandscape,
                            isTvMode = DeviceUtils.isTvDevice,
                            isChannelListAvailable = isChannelListAvailable,
                            digitBuffer = _digitBufferState.value,
                            onBackClick = { finish() },
                            onPipClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    wasLockedBeforePip = controlsState.isLocked
                                    enterPictureInPictureMode(updatePipParams(enter = true))
                                }
                            },
                            onSettingsClick = { showSettingsDialog() },
                            onMuteClick = { toggleMute() },
                            onLockClick = { locked -> wasLockedBeforePip = locked },
                            onChannelListClick = { showChannelList = true },
                            onPlayPauseClick = { togglePlayPause() },
                            onSeek = { position -> player?.seekTo(position) },
                            onRewindClick = {
                                player?.let {
                                    it.seekTo(maxOf(0L, it.currentPosition - skipMs))
                                }
                            },
                            onForwardClick = {
                                player?.let {
                                    val newPos = it.currentPosition + skipMs
                                    if (it.isCurrentMediaItemLive && it.duration != C.TIME_UNSET
                                        && newPos >= it.duration
                                    ) it.seekTo(it.duration)
                                    else it.seekTo(newPos)
                                }
                            },
                            onAspectRatioClick = {
                                if (isLandscape || contentType == ContentType.NETWORK_STREAM)
                                    cycleAspectRatio()
                            },
                            onFullscreenClick = { toggleFullscreen() },
                            onVolumeSwipe = { vol ->
                                gestureVolume = vol
                                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                audioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    (vol / 100f * max).toInt(), 0
                                )
                            },
                            onBrightnessSwipe = { bri ->
                                gestureBrightness = bri
                                val lp = window.attributes
                                lp.screenBrightness = if (bri == 0)
                                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                                else bri / 100f
                                window.attributes = lp
                            },
                            initialVolume = gestureVolume,
                            initialBrightness = gestureBrightness,
                        )

                        // Channel list panel — TV always qualifies as landscape
                        if (isChannelListAvailable) {
                            com.livetvpro.ui.player.compose.ChannelListPanel(
                                visible = showChannelList,
                                channels = channelListItems,
                                currentChannelId = contentId,
                                onChannelClick = { channel ->
                                    showChannelList = false
                                    switchToChannel(channel)
                                },
                                onDismiss = { showChannelList = false },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Orientation / layout helpers (unchanged from original)
    // ════════════════════════════════════════════════════════════════════════════

    private fun applyResizeModeForOrientation(isLandscape: Boolean) {
        if (contentType == ContentType.NETWORK_STREAM) {
            binding.playerView.resizeMode =
                if (isLandscape) networkLandscapeResizeMode else networkPortraitResizeMode
        }
    }

    private fun applyOrientationSettings(isLandscape: Boolean) {
        adjustLayoutForOrientation(isLandscape)
        updateLinksForOrientation(isLandscape)
    }

    private fun adjustLayoutForOrientation(isLandscape: Boolean) {
        if (isLandscape) {
            enterFullscreen()
            val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
            params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.topMargin = 0; params.bottomMargin = 0
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            binding.playerContainer.setPadding(0, 0, 0, 0)
            binding.playerContainer.layoutParams = params
            binding.playerView.controllerAutoShow = false
            binding.playerView.controllerShowTimeoutMs = 3000
        } else {
            if (contentType == ContentType.NETWORK_STREAM) {
                val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
                params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                params.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                params.topMargin = 0; params.bottomMargin = 0
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

            val relatedParams =
                binding.relatedChannelsSection.layoutParams as ConstraintLayout.LayoutParams
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

    // ════════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════════════════════════════════════════

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPipSupported)
            setPictureInPictureParams(updatePipParams())
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

    override fun onPause() {
        super.onPause()
        val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode
        else false
        if (!isPip) {
            binding.playerView.onPause()
            player?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing || isChangingConfigurations) releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        digitHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacksAndMessages(null)
        unregisterPipReceiver()
        releaseMediaSession()
        releasePlayer()
    }

    // ════════════════════════════════════════════════════════════════════════════
    // PiP
    // ════════════════════════════════════════════════════════════════════════════

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        if (!isInPictureInPictureMode) {
            pipReceiver?.let { unregisterReceiver(it); pipReceiver = null }
            isInPipMode = false
            // If the activity is finishing (user swiped away / closed the PiP window),
            // just let it die — don't restore the full-screen UI.
            if (isFinishing) {
                super.onPictureInPictureModeChanged(false, newConfig)
                return
            }
            controlsState.show(lifecycleScope)
            if (wasLockedBeforePip) { controlsState.lock(); wasLockedBeforePip = false }
            super.onPictureInPictureModeChanged(false, newConfig)
            exitPipUIMode(newConfig)
            return
        }

        isInPipMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            setPictureInPictureParams(updatePipParams(enter = true))
        controlsState.hide()

        pipReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null || intent.action != PIP_INTENTS_FILTER) return
                when (intent.getIntExtra(PIP_INTENT_ACTION, 0)) {
                    PIP_PAUSE -> {
                        val hasError = binding.errorView.visibility == View.VISIBLE
                        val hasEnded = player?.playbackState == Player.STATE_ENDED
                        if (hasError || hasEnded) retryPlayback() else player?.pause()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            setPictureInPictureParams(updatePipParams(enter = false))
                    }
                    PIP_PLAY -> {
                        val hasError = binding.errorView.visibility == View.VISIBLE
                        val hasEnded = player?.playbackState == Player.STATE_ENDED
                        val eff = player?.isPlaying == true ||
                                (player?.playbackState == Player.STATE_BUFFERING && player?.playWhenReady == true)
                        if (hasError || hasEnded) retryPlayback()
                        else if (!eff) player?.play()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            setPictureInPictureParams(updatePipParams(enter = false))
                    }
                    PIP_FF -> player?.let { p ->
                        val newPos = p.currentPosition + 10_000L
                        if (p.isCurrentMediaItemLive && p.duration != C.TIME_UNSET)
                            p.seekTo(minOf(newPos, p.duration))
                        else p.seekTo(newPos)
                    }
                    PIP_FR -> player?.let { p ->
                        p.seekTo(maxOf(0L, p.currentPosition - 10_000L))
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(pipReceiver, IntentFilter(PIP_INTENTS_FILTER), Context.RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(pipReceiver, IntentFilter(PIP_INTENTS_FILTER))

        super.onPictureInPictureModeChanged(true, newConfig)
    }

    private fun exitPipUIMode(newConfig: Configuration) {
        setSubtitleTextSize()
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        setupWindowFlags(isLandscape)
        setupSystemUI(isLandscape)
        applyOrientationSettings(isLandscape)
        if (!isLandscape) {
            if (allEventLinks.size > 1) binding.linksSection.visibility = View.VISIBLE
            val hasRelated = relatedChannels.isNotEmpty() ||
                    (contentType == ContentType.EVENT && ::relatedEventsAdapter.isInitialized)
            if (hasRelated) {
                binding.relatedChannelsSection.visibility = View.VISIBLE
                binding.relatedChannelsRecycler.visibility = View.VISIBLE
                binding.relatedLoadingProgress.visibility = View.GONE
            }
        }
        if (wasLockedBeforePip) { controlsState.lock(); wasLockedBeforePip = false }
        else if (controlsState.isLocked) controlsState.unlock(lifecycleScope)
        binding.playerView.useController = false
    }

    @SuppressLint("NewApi")
    override fun onUserLeaveHint() {
        if (isPipSupported && player?.isPlaying == true) {
            wasLockedBeforePip = controlsState.isLocked
            enterPictureInPictureMode(updatePipParams(enter = true))
        }
        super.onUserLeaveHint()
    }

    @SuppressLint("NewApi")
    override fun onBackPressed() {
        // Back = user explicitly wants to leave → close the player entirely.
        // PiP is only triggered when the user navigates home (onUserLeaveHint).
        super.onBackPressed()
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Player setup
    // ════════════════════════════════════════════════════════════════════════════

    private fun setupPlayer() {
        if (player != null) return
        binding.errorView.visibility = View.GONE
        binding.errorText.text = ""
        binding.progressBar.visibility = View.VISIBLE
        binding.playerView.hideController()

        trackSelector = DefaultTrackSelector(this)

        try {
            val streamInfo = parseStreamUrl(streamUrl)
            if (streamInfo.url.isBlank()) { showError("Invalid stream URL"); return }

            val headers = streamInfo.headers.toMutableMap()
            if (!headers.containsKey("User-Agent"))
                headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(headers["User-Agent"] ?: "LiveTVPro/1.0")
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(30000).setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true).setKeepPostFor302Redirects(true)

            val mediaSourceFactory = if (streamInfo.drmScheme != null) {
                val drmManager = when (streamInfo.drmScheme) {
                    "clearkey" -> when {
                        streamInfo.drmKeyId != null && streamInfo.drmKey != null ->
                            createClearKeyDrmManager(streamInfo.drmKeyId, streamInfo.drmKey)
                        streamInfo.drmLicenseUrl?.trimStart()?.startsWith("{") == true ->
                            createClearKeyDrmManagerFromJwk(streamInfo.drmLicenseUrl)
                        else -> null
                    }
                    "widevine" -> streamInfo.drmLicenseUrl?.let {
                        createWidevineDrmManager(it, headers)
                    }
                    "playready" -> streamInfo.drmLicenseUrl?.let {
                        createPlayReadyDrmManager(it, headers)
                    }
                    else -> null
                }
                if (drmManager != null)
                    DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
                        .setDrmSessionManagerProvider { drmManager }
                else
                    DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
            } else {
                DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
            }

            player = ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector!!)
                .setMediaSourceFactory(mediaSourceFactory)
                .setSeekBackIncrementMs(skipMs)
                .setSeekForwardIncrementMs(skipMs)
                .build().also { exo ->
                    binding.playerView.player = exo
                    applyResizeModeForOrientation(
                        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    )
                    binding.playerView.hideController()

                    val uri = android.net.Uri.parse(streamInfo.url)
                    val mediaItemBuilder = MediaItem.Builder().setUri(uri)
                    if (streamInfo.url.contains("m3u8", ignoreCase = true) ||
                        streamInfo.url.contains("extension=m3u8", ignoreCase = true)
                    ) mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)

                    if (streamInfo.drmScheme != null && streamInfo.drmLicenseUrl != null) {
                        val drmUuid = when (streamInfo.drmScheme) {
                            "widevine" -> C.WIDEVINE_UUID
                            "playready" -> C.PLAYREADY_UUID
                            "clearkey" -> UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")
                            else -> C.WIDEVINE_UUID
                        }
                        mediaItemBuilder.setDrmConfiguration(
                            MediaItem.DrmConfiguration.Builder(drmUuid)
                                .setLicenseUri(streamInfo.drmLicenseUrl).build()
                        )
                    }

                    exo.setMediaItem(mediaItemBuilder.build())
                    exo.prepare()
                    exo.playWhenReady = true

                    // Set up MediaSession after player is ready so online remotes work
                    setupMediaSession()

                    playerListener = object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    binding.progressBar.visibility = View.GONE
                                    binding.errorView.visibility = View.GONE
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                        setPictureInPictureParams(updatePipParams())
                                }
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar.visibility = View.VISIBLE
                                    binding.errorView.visibility = View.GONE
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode)
                                        setPictureInPictureParams(updatePipParams())
                                }
                                Player.STATE_ENDED -> {
                                    binding.progressBar.visibility = View.GONE
                                    binding.playerView.showController()
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode)
                                        setPictureInPictureParams(updatePipParams())
                                }
                                Player.STATE_IDLE -> {}
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                setPictureInPictureParams(updatePipParams(enter = false))
                        }

                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            super.onVideoSizeChanged(videoSize)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                setPictureInPictureParams(updatePipParams())
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            super.onPlayerError(error)
                            binding.progressBar.visibility = View.GONE
                            val msg = when {
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT ->
                                    "Connection Failed"
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                                    when {
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
                            showError(msg)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode)
                                setPictureInPictureParams(updatePipParams())
                        }
                    }
                    exo.addListener(playerListener!!)
                }
        } catch (e: Exception) {
            showError("Failed to initialize player")
        }
    }

    private fun releasePlayer() {
        releaseMediaSession()
        player?.let {
            try {
                playerListener?.let { l -> it.removeListener(l) }
                it.stop(); it.release()
            } catch (_: Throwable) {}
        }
        player = null
        playerListener = null
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Channel / event switching helpers
    // ════════════════════════════════════════════════════════════════════════════

    private fun switchToChannel(newChannel: Channel) {
        releasePlayer()
        channelData = newChannel; eventData = null
        contentType = ContentType.CHANNEL
        contentId = newChannel.id; contentName = newChannel.name

        allEventLinks = if (newChannel.links != null && newChannel.links.isNotEmpty()) {
            newChannel.links.map {
                LiveEventLink(
                    quality = it.quality, url = it.url,
                    cookie = it.cookie, referer = it.referer,
                    origin = it.origin, userAgent = it.userAgent,
                    drmScheme = it.drmScheme, drmLicenseUrl = it.drmLicenseUrl
                )
            }
        } else emptyList()

        currentLinkIndex = 0
        streamUrl = allEventLinks.firstOrNull()?.let { buildStreamUrl(it) } ?: newChannel.streamUrl

        setupPlayer()
        setupLinksUI()

        binding.relatedLoadingProgress.visibility = View.VISIBLE
        binding.relatedChannelsRecycler.visibility = View.GONE
        val categoryId = intentCategoryId?.takeIf { it.isNotEmpty() } ?: newChannel.categoryId
        viewModel.loadRandomRelatedChannels(categoryId, newChannel.id, intentSelectedGroup)
    }

    private fun switchToEventFromLiveEvent(newEvent: LiveEvent) {
        try {
            releasePlayer()
            eventData = newEvent; channelData = null
            contentType = ContentType.EVENT
            contentId = newEvent.id
            contentName = newEvent.title.ifEmpty { "${newEvent.team1Name} vs ${newEvent.team2Name}" }
            allEventLinks = newEvent.links
            currentLinkIndex = 0
            streamUrl = allEventLinks.firstOrNull()?.let { buildStreamUrl(it) } ?: ""
            setupPlayer(); setupLinksUI()
            binding.relatedLoadingProgress.visibility = View.VISIBLE
            binding.relatedChannelsRecycler.visibility = View.GONE
            viewModel.loadRelatedEvents(newEvent.id)
        } catch (_: Exception) {}
    }

    private fun switchToLink(link: LiveEventLink, position: Int) {
        currentLinkIndex = position
        streamUrl = buildStreamUrl(link)
        if (::linkChipAdapter.isInitialized) linkChipAdapter.setSelectedPosition(position)
        val lr = binding.playerContainer.findViewById<RecyclerView>(R.id.exo_links_recycler)
        (lr?.adapter as? LinkChipAdapter)?.setSelectedPosition(position)
        releasePlayer(); setupPlayer()
    }

    // ════════════════════════════════════════════════════════════════════════════
    // UI helpers
    // ════════════════════════════════════════════════════════════════════════════

    private fun showSettingsDialog() {
        val exoPlayer = player ?: return
        if (isFinishing || isDestroyed) return
        try {
            com.livetvpro.ui.player.settings.PlayerSettingsDialog(this, exoPlayer).show()
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivity", "Error showing settings dialog", e)
        }
    }

    private fun cycleAspectRatio() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val next = when (binding.playerView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        if (contentType == ContentType.NETWORK_STREAM) {
            if (isLandscape) networkLandscapeResizeMode = next else networkPortraitResizeMode = next
        }
        binding.playerView.resizeMode = next
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
        }
    }

    private fun toggleFullscreen() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun enterFullscreen() {
        windowInsetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        binding.root.setPadding(0, 0, 0, 0)
        binding.playerContainer.setPadding(0, 0, 0, 0)
        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
        params.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
        params.topMargin = 0; params.dimensionRatio = null
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        binding.playerContainer.layoutParams = params
        binding.relatedChannelsSection.visibility = View.GONE
        binding.linksSection.visibility = View.GONE
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
        if (allEventLinks.size > 1) binding.linksSection.visibility = View.VISIBLE
        val hasRelated = relatedChannels.isNotEmpty() ||
                (contentType == ContentType.EVENT && ::relatedEventsAdapter.isInitialized)
        if (hasRelated) binding.relatedChannelsSection.visibility = View.VISIBLE
    }

    private fun retryPlayback() {
        binding.errorView.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        binding.playerView.hideController()
        releasePlayer(); setupPlayer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode)
            setPictureInPictureParams(updatePipParams(enter = false))
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.errorText.apply {
            text = message
            typeface = try { resources.getFont(R.font.bergen_sans) }
            catch (_: Exception) { android.graphics.Typeface.DEFAULT }
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f; setPadding(48, 20, 48, 20)
            setBackgroundResource(R.drawable.error_message_background)
            elevation = 0f
        }
        val lp = binding.errorView.layoutParams
        if (lp is ConstraintLayout.LayoutParams) {
            lp.verticalBias = 0.35f; binding.errorView.layoutParams = lp
        }
        binding.errorView.visibility = View.VISIBLE
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
                    try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                    catch (_: Exception) {}
                }
            } else {
                binding.tvMessageBanner.setOnClickListener(null)
            }
        }
    }

    private fun updateMessageBannerForOrientation(isLandscape: Boolean) {
        if (binding.tvMessageBanner.text.isNotBlank())
            binding.messageBannerContainer.visibility = if (isLandscape) View.GONE else View.VISIBLE
    }

    private fun setSubtitleTextSize() {
        binding.playerView.subtitleView
            ?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
    }

    private fun setSubtitleTextSizePiP() {
        binding.playerView.subtitleView
            ?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 2)
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Links / related content
    // ════════════════════════════════════════════════════════════════════════════

    private fun setupRelatedChannels() {
        if (contentType == ContentType.NETWORK_STREAM || DeviceUtils.isTvDevice) {
            binding.relatedChannelsSection.visibility = View.GONE
            return
        }
        if (contentType == ContentType.EVENT) {
            relatedEventsAdapter = LiveEventAdapter(
                context = this, events = emptyList(),
                preferencesManager = preferencesManager,
                onEventClick = { event, _ -> switchToEventFromLiveEvent(event) }
            )
            binding.relatedChannelsRecycler.layoutManager =
                GridLayoutManager(this, resources.getInteger(R.integer.event_span_count))
            binding.relatedChannelsRecycler.adapter = relatedEventsAdapter
        } else {
            relatedChannelsAdapter = RelatedChannelAdapter { switchToChannel(it) }
            binding.relatedChannelsRecycler.layoutManager =
                GridLayoutManager(this, resources.getInteger(R.integer.grid_column_count))
            binding.relatedChannelsRecycler.adapter = relatedChannelsAdapter
        }
    }

    private fun setupLinksUI() {
        linkChipAdapter = LinkChipAdapter { link, position -> switchToLink(link, position) }
        val portraitLr = binding.linksRecyclerView
        portraitLr.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        portraitLr.adapter = linkChipAdapter

        val landscapeLr = binding.playerContainer.findViewById<RecyclerView>(R.id.exo_links_recycler)
        landscapeLr?.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        landscapeLr?.let { rv ->
            val params = rv.layoutParams as? ConstraintLayout.LayoutParams
            if (params != null) {
                params.width = ConstraintLayout.LayoutParams.WRAP_CONTENT
                params.endToEnd = ConstraintLayout.LayoutParams.UNSET
                rv.layoutParams = params
            }
            val startPx = (56 * resources.displayMetrics.density).toInt()
            rv.setPaddingRelative(startPx, rv.paddingTop, rv.paddingEnd, rv.paddingBottom)
        }

        val landscapeAdapter = LinkChipAdapter { link, position -> switchToLink(link, position) }
        landscapeLr?.adapter = landscapeAdapter

        if (allEventLinks.size > 1) {
            val isLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (isLand) {
                binding.linksSection.visibility = View.GONE
                landscapeLr?.visibility = View.VISIBLE
                landscapeAdapter.submitList(allEventLinks)
                landscapeAdapter.setSelectedPosition(currentLinkIndex)
            } else {
                binding.linksSection.visibility = View.VISIBLE
                landscapeLr?.visibility = View.GONE
                linkChipAdapter.submitList(allEventLinks)
                linkChipAdapter.setSelectedPosition(currentLinkIndex)
            }
        } else {
            binding.linksSection.visibility = View.GONE
            landscapeLr?.visibility = View.GONE
        }
    }

    private fun updateLinksForOrientation(isLandscape: Boolean) {
        if (!::linkChipAdapter.isInitialized) return
        val landscapeLr =
            binding.playerContainer.findViewById<RecyclerView>(R.id.exo_links_recycler)
        if (allEventLinks.size > 1) {
            if (isLandscape) {
                binding.linksSection.visibility = View.GONE
                val chipsVisible = controlsState.isVisible && !controlsState.isLocked
                landscapeLr?.visibility = if (chipsVisible) View.VISIBLE else View.GONE
                (landscapeLr?.adapter as? LinkChipAdapter)?.let {
                    it.submitList(allEventLinks); it.setSelectedPosition(currentLinkIndex)
                }
            } else {
                binding.linksSection.visibility = View.VISIBLE
                landscapeLr?.visibility = View.GONE
                linkChipAdapter.submitList(allEventLinks)
                linkChipAdapter.setSelectedPosition(currentLinkIndex)
            }
        } else {
            binding.linksSection.visibility = View.GONE
            landscapeLr?.visibility = View.GONE
        }
    }

    private fun loadRelatedContent() {
        if (DeviceUtils.isTvDevice) return
        when (contentType) {
            ContentType.CHANNEL -> {
                channelData?.let { channel ->
                    val passed: List<Channel>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableArrayListExtra(EXTRA_RELATED_CHANNELS, Channel::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(EXTRA_RELATED_CHANNELS)

                    if (passed != null && passed.isNotEmpty()) {
                        viewModel.setRelatedChannels(passed.filter { it.id != channel.id })
                    } else {
                        val catId = intentCategoryId?.takeIf { it.isNotEmpty() } ?: channel.categoryId
                        viewModel.loadRandomRelatedChannels(catId, channel.id, intentSelectedGroup)
                    }
                }
            }
            ContentType.EVENT -> eventData?.let { viewModel.loadRelatedEvents(it.id) }
            ContentType.NETWORK_STREAM -> {}
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Instance state
    // ════════════════════════════════════════════════════════════════════════════

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("SAVE_CONTENT_TYPE", contentType.name)
        outState.putParcelable("SAVE_CHANNEL_DATA", channelData)
        outState.putParcelable("SAVE_EVENT_DATA", eventData)
        outState.putParcelableArrayList("SAVE_ALL_LINKS", ArrayList(allEventLinks))
        outState.putInt("SAVE_LINK_INDEX", currentLinkIndex)
        outState.putString("SAVE_CONTENT_ID", contentId)
        outState.putString("SAVE_CONTENT_NAME", contentName)
        outState.putString("SAVE_STREAM_URL", streamUrl)
        outState.putString("SAVE_CATEGORY_ID", intentCategoryId)
        outState.putString("SAVE_SELECTED_GROUP", intentSelectedGroup)
        outState.putLong("SAVE_PLAYBACK_POSITION", player?.currentPosition ?: 0L)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        restoreFromBundle(savedInstanceState)
    }

    private fun restoreFromBundle(bundle: Bundle) {
        val typeName = bundle.getString("SAVE_CONTENT_TYPE") ?: return
        contentType = ContentType.valueOf(typeName)
        channelData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            bundle.getParcelable("SAVE_CHANNEL_DATA", Channel::class.java)
        else @Suppress("DEPRECATION") bundle.getParcelable("SAVE_CHANNEL_DATA")

        eventData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            bundle.getParcelable("SAVE_EVENT_DATA", LiveEvent::class.java)
        else @Suppress("DEPRECATION") bundle.getParcelable("SAVE_EVENT_DATA")

        allEventLinks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            bundle.getParcelableArrayList("SAVE_ALL_LINKS", LiveEventLink::class.java) ?: emptyList()
        else @Suppress("DEPRECATION") bundle.getParcelableArrayList<LiveEventLink>("SAVE_ALL_LINKS") ?: emptyList()

        currentLinkIndex = bundle.getInt("SAVE_LINK_INDEX", 0)
        contentId = bundle.getString("SAVE_CONTENT_ID", "")
        contentName = bundle.getString("SAVE_CONTENT_NAME", "")
        streamUrl = bundle.getString("SAVE_STREAM_URL", "")
        intentCategoryId = bundle.getString("SAVE_CATEGORY_ID")
        intentSelectedGroup = bundle.getString("SAVE_SELECTED_GROUP")
    }

    // ════════════════════════════════════════════════════════════════════════════
    // PiP params / actions
    // ════════════════════════════════════════════════════════════════════════════

    @RequiresApi(Build.VERSION_CODES.O)
    fun updatePipParams(enter: Boolean = false): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) builder.setTitle(contentName)
        val p = player
        val eff = p?.isPlaying == true ||
                (p?.playbackState == Player.STATE_BUFFERING && p.playWhenReady == true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(eff); builder.setSeamlessResizeEnabled(eff)
        }
        builder.setActions(createPipActions(this, isPaused = !eff))
        builder.setSourceRectHint(pipRect)
        p?.videoFormat?.let { fmt ->
            if (fmt.height > 0 && fmt.width > 0) {
                val r = Rational(fmt.width, fmt.height).toFloat()
                if (r in 0.42..2.38) builder.setAspectRatio(Rational(fmt.width, fmt.height))
            }
        }
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createPipActions(context: Context, isPaused: Boolean): List<RemoteAction> {
        val actions = mutableListOf<RemoteAction>()
        actions.add(RemoteAction(
            Icon.createWithResource(context, R.drawable.ic_skip_backward),
            "Rewind", "Rewind 10s",
            PendingIntent.getBroadcast(context, PIP_FR,
                Intent(PIP_INTENTS_FILTER).setPackage(context.packageName)
                    .putExtra(PIP_INTENT_ACTION, PIP_FR), PendingIntent.FLAG_IMMUTABLE)
        ))
        if (isPaused) actions.add(RemoteAction(
            Icon.createWithResource(context, R.drawable.ic_play),
            context.getString(R.string.play), context.getString(R.string.play),
            PendingIntent.getBroadcast(context, PIP_PLAY,
                Intent(PIP_INTENTS_FILTER).setPackage(context.packageName)
                    .putExtra(PIP_INTENT_ACTION, PIP_PLAY), PendingIntent.FLAG_IMMUTABLE)
        )) else actions.add(RemoteAction(
            Icon.createWithResource(context, R.drawable.ic_pause),
            context.getString(R.string.pause), context.getString(R.string.pause),
            PendingIntent.getBroadcast(context, PIP_PAUSE,
                Intent(PIP_INTENTS_FILTER).setPackage(context.packageName)
                    .putExtra(PIP_INTENT_ACTION, PIP_PAUSE), PendingIntent.FLAG_IMMUTABLE)
        ))
        actions.add(RemoteAction(
            Icon.createWithResource(context, R.drawable.ic_skip_forward),
            "Forward", "Forward 10s",
            PendingIntent.getBroadcast(context, PIP_FF,
                Intent(PIP_INTENTS_FILTER).setPackage(context.packageName)
                    .putExtra(PIP_INTENT_ACTION, PIP_FF), PendingIntent.FLAG_IMMUTABLE)
        ))
        return actions
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Intent parsing
    // ════════════════════════════════════════════════════════════════════════════

    private fun parseIntent() {
        val isNetworkStream = intent.getBooleanExtra("IS_NETWORK_STREAM", false)
        if (isNetworkStream) {
            contentType = ContentType.NETWORK_STREAM
            contentName = intent.getStringExtra("CHANNEL_NAME") ?: "Network Stream"
            contentId = "network_stream_${System.currentTimeMillis()}"
            val rawUrl = intent.getStringExtra("STREAM_URL") ?: ""
            if (rawUrl.contains("|")) {
                streamUrl = rawUrl
                val parsed = parseStreamUrl(rawUrl)
                allEventLinks = listOf(LiveEventLink(
                    quality = "Network Stream", url = parsed.url,
                    cookie = parsed.headers["Cookie"] ?: "",
                    referer = parsed.headers["Referer"] ?: "",
                    origin = parsed.headers["Origin"] ?: "",
                    userAgent = parsed.headers["User-Agent"] ?: "Default",
                    drmScheme = parsed.drmScheme, drmLicenseUrl = parsed.drmLicenseUrl
                ))
            } else {
                allEventLinks = listOf(LiveEventLink(
                    quality = "Network Stream", url = rawUrl,
                    cookie = intent.getStringExtra("COOKIE") ?: "",
                    referer = intent.getStringExtra("REFERER") ?: "",
                    origin = intent.getStringExtra("ORIGIN") ?: "",
                    userAgent = intent.getStringExtra("USER_AGENT") ?: "Default",
                    drmScheme = intent.getStringExtra("DRM_SCHEME") ?: "clearkey",
                    drmLicenseUrl = intent.getStringExtra("DRM_LICENSE") ?: ""
                ))
                streamUrl = buildStreamUrl(allEventLinks[0])
            }
            currentLinkIndex = 0
            return
        }

        channelData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(EXTRA_CHANNEL, Channel::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_CHANNEL)

        eventData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(EXTRA_EVENT, LiveEvent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_EVENT)

        val passedLinkIndex = intent.getIntExtra(EXTRA_SELECTED_LINK_INDEX, -1)
        intentCategoryId = intent.getStringExtra(EXTRA_CATEGORY_ID)
        intentSelectedGroup = intent.getStringExtra(EXTRA_SELECTED_GROUP)

        if (channelData != null) {
            val ch = channelData!!
            contentType = ContentType.CHANNEL; contentId = ch.id; contentName = ch.name
            if (ch.links != null && ch.links.isNotEmpty()) {
                allEventLinks = ch.links.map {
                    LiveEventLink(
                        quality = it.quality, url = it.url,
                        cookie = it.cookie, referer = it.referer,
                        origin = it.origin, userAgent = it.userAgent,
                        drmScheme = it.drmScheme, drmLicenseUrl = it.drmLicenseUrl
                    )
                }
                currentLinkIndex = if (passedLinkIndex in allEventLinks.indices) passedLinkIndex
                else maxOf(0, allEventLinks.indexOfFirst { it.url == ch.streamUrl })
                streamUrl = buildStreamUrl(allEventLinks[currentLinkIndex])
            } else {
                streamUrl = ch.streamUrl; allEventLinks = emptyList()
            }
        } else if (eventData != null) {
            val ev = eventData!!
            contentType = ContentType.EVENT; contentId = ev.id
            contentName = ev.title.ifEmpty { "${ev.team1Name} vs ${ev.team2Name}" }
            allEventLinks = ev.links
            if (allEventLinks.isNotEmpty()) {
                currentLinkIndex = if (passedLinkIndex in allEventLinks.indices) passedLinkIndex else 0
                streamUrl = buildStreamUrl(allEventLinks[currentLinkIndex])
            } else { currentLinkIndex = 0; streamUrl = "" }
        } else { finish(); return }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Stream URL / DRM helpers (unchanged from original)
    // ════════════════════════════════════════════════════════════════════════════

    private data class StreamInfo(
        val url: String, val headers: Map<String, String>,
        val drmScheme: String?, val drmKeyId: String?,
        val drmKey: String?, val drmLicenseUrl: String? = null
    )

    private fun parseStreamUrl(streamUrl: String): StreamInfo {
        val pipeIndex = streamUrl.indexOf('|')
        if (pipeIndex == -1) return StreamInfo(streamUrl, mapOf(), null, null, null, null)
        val url = streamUrl.substring(0, pipeIndex).trim()
        val rawParams = streamUrl.substring(pipeIndex + 1).trim()
        val parts = buildList {
            for (segment in rawParams.split("|")) {
                val eqIdx = segment.indexOf('=')
                val value = if (eqIdx != -1) segment.substring(eqIdx + 1) else ""
                if (value.startsWith("http://", ignoreCase = true) ||
                    value.startsWith("https://", ignoreCase = true)) add(segment)
                else addAll(segment.split("&"))
            }
        }
        val headers = mutableMapOf<String, String>()
        var drmScheme: String? = null; var drmKeyId: String? = null
        var drmKey: String? = null; var drmLicenseUrl: String? = null
        for (part in parts) {
            val eqIndex = part.indexOf('='); if (eqIndex == -1) continue
            val key = part.substring(0, eqIndex).trim()
            val value = part.substring(eqIndex + 1).trim()
            when (key.lowercase()) {
                "drmscheme" -> drmScheme = normalizeDrmScheme(value)
                "drmlicense" -> {
                    if (value.startsWith("http://", ignoreCase = true) ||
                        value.startsWith("https://", ignoreCase = true)) drmLicenseUrl = value
                    else if (value.trimStart().startsWith("{")) drmLicenseUrl = value
                    else {
                        val ci = value.indexOf(':')
                        if (ci != -1) { drmKeyId = value.substring(0, ci).trim(); drmKey = value.substring(ci + 1).trim() }
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
        if (params.isNotEmpty()) url += "|" + params.joinToString("|")
        return url
    }

    private fun createClearKeyDrmManager(keyIdHex: String, keyHex: String): DefaultDrmSessionManager? {
        return try {
            val uuid = UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")
            val kidB64 = android.util.Base64.encodeToString(hexToBytes(keyIdHex),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            val keyB64 = android.util.Base64.encodeToString(hexToBytes(keyHex),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            val jwk = """{"keys":[{"kty":"oct","k":"$keyB64","kid":"$kidB64"}]}"""
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false).build(LocalMediaDrmCallback(jwk.toByteArray()))
        } catch (_: Exception) { null }
    }

    private fun createClearKeyDrmManagerFromJwk(jwkJson: String): DefaultDrmSessionManager? {
        return try {
            val uuid = UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false).build(LocalMediaDrmCallback(jwkJson.toByteArray()))
        } catch (_: Exception) { null }
    }

    private fun createWidevineDrmManager(licenseUrl: String, headers: Map<String, String>): DefaultDrmSessionManager? {
        return try {
            val dsf = DefaultHttpDataSource.Factory()
                .setUserAgent(headers["User-Agent"] ?: "LiveTVPro/1.0")
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(30000).setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)
            val cb = HttpMediaDrmCallback(licenseUrl, dsf)
            headers.forEach { (k, v) -> cb.setKeyRequestProperty(k, v) }
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false).build(cb)
        } catch (_: Exception) { null }
    }

    private fun createPlayReadyDrmManager(licenseUrl: String, headers: Map<String, String>): DefaultDrmSessionManager? {
        return try {
            val dsf = DefaultHttpDataSource.Factory()
                .setUserAgent(headers["User-Agent"] ?: "LiveTVPro/1.0")
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(30000).setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)
            val cb = HttpMediaDrmCallback(licenseUrl, dsf)
            headers.forEach { (k, v) -> cb.setKeyRequestProperty(k, v) }
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.PLAYREADY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(false).build(cb)
        } catch (_: Exception) { null }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return try {
            val clean = hex.replace(" ", "").replace("-", "").lowercase()
            if (clean.length % 2 != 0) return ByteArray(0)
            clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) { ByteArray(0) }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Misc stubs retained for compatibility
    // ════════════════════════════════════════════════════════════════════════════

    private fun configurePlayerInteractions() { /* handled by Compose + dispatchKeyEvent */ }
    private fun unregisterPipReceiver() {
        try { pipReceiver?.let { unregisterReceiver(it); pipReceiver = null } }
        catch (_: Exception) {}
    }

    override fun finish() {
        try {
            releasePlayer()
            pipReceiver?.let { unregisterReceiver(it); pipReceiver = null }
            isInPipMode = false; wasLockedBeforePip = false
            super.finish()
        } catch (_: Exception) { super.finish() }
    }
}
