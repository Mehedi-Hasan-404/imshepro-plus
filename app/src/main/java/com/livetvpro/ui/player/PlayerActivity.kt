package com.livetvpro.ui.player

import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import java.util.UUID

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class ChannelPlayerActivity : AppCompatActivity() {

    // --- UI & State ---
    private lateinit var binding: ActivityChannelPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()
    private lateinit var channel: Channel
    
    // --- Player ---
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    
    // --- Related Channels ---
    private lateinit var relatedChannelsAdapter: RelatedChannelAdapter
    private var relatedChannels = listOf<Channel>()

    // --- Controls ---
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

    // --- Flags & Handlers ---
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

    // --- PiP Variables ---
    private var mPictureInPictureParamsBuilder: PictureInPictureParams.Builder? = null
    private val rationalLimitWide = Rational(239, 100)
    private val rationalLimitTall = Rational(100, 239)
    private val pipSourceRect = Rect()
    
    // --- Control Binding Helper ---
    private var isBindingControls = false
    private var controlsBindingRunnable: Runnable? = null

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || ACTION_MEDIA_CONTROL != intent.action) return
            val player = player ?: return
            
            when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                CONTROL_TYPE_PLAY -> {
                    player.play()
                    updatePipAction(R.drawable.ic_pause, R.string.exo_controls_pause_description, CONTROL_TYPE_PAUSE, REQUEST_PAUSE)
                }
                CONTROL_TYPE_PAUSE -> {
                    player.pause()
                    updatePipAction(R.drawable.ic_play, R.string.exo_controls_play_description, CONTROL_TYPE_PLAY, REQUEST_PLAY)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"
        private const val ACTION_MEDIA_CONTROL = "media_control"
        private const val EXTRA_CONTROL_TYPE = "control_type"
        private const val CONTROL_TYPE_PLAY = 1
        private const val CONTROL_TYPE_PAUSE = 2
        private const val REQUEST_PLAY = 1
        private const val REQUEST_PAUSE = 2

        fun start(context: Context, channel: Channel) {
            val intent = Intent(context, ChannelPlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 1. Get Channel Data
        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            finish()
            return
        }

        // 2. Setup PiP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mPictureInPictureParamsBuilder = PictureInPictureParams.Builder()
            // Initial receiver registration
            val filter = IntentFilter(ACTION_MEDIA_CONTROL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pipReceiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(pipReceiver, filter)
            }
        }

        // 3. UI Initialization
        binding.progressBar.visibility = View.VISIBLE
        setupPlayer()
        setupLockOverlay()
        setupRelatedChannels()
        loadRelatedChannels()

        // 4. Orientation Handling
        binding.root.post {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            applyOrientationSettings(isLandscape)
        }

        // 5. Late Binding for Controls (wait for PlayerView inflation)
        binding.playerView.postDelayed({
            bindControllerViewsOnce()
            setupControlListenersOnce()
        }, 300)

        // 6. Source Rect Hint Update for PiP animation
        binding.playerView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    updatePipParamsBasic()
                }
            }
        }
    }

    // ============================================================================================
    // PLAYER SETUP & DRM
    // ============================================================================================

    private fun setupPlayer() {
        if (player != null) return
        
        binding.errorView.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE

        trackSelector = DefaultTrackSelector(this)
        val streamInfo = parseStreamUrl(channel.streamUrl)

        // Default Headers
        val headers = streamInfo.headers.toMutableMap()
        if (!headers.containsKey("User-Agent")) {
            headers["User-Agent"] = "LiveTVPro/1.0"
        }

        // DataSource Factory (Used for both Stream and DRM)
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"]!!)
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(30000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)

        // DRM Configuration
        val drmSessionManager = buildDrmSessionManager(streamInfo, headers, dataSourceFactory)
        
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)
        
        if (drmSessionManager != null) {
            mediaSourceFactory.setDrmSessionManagerProvider { drmSessionManager }
        }

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector!!)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(skipMs)
            .setSeekForwardIncrementMs(skipMs)
            .build().apply {
                binding.playerView.player = this
                setMediaItem(MediaItem.fromUri(streamInfo.url))
                prepare()
                playWhenReady = true
                addListener(playerListener)
            }

        configurePlayerView()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    binding.progressBar.visibility = View.GONE
                    updatePlayPauseIcon(player?.playWhenReady == true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) updatePipParamsBasic()
                }
                Player.STATE_BUFFERING -> binding.progressBar.visibility = View.VISIBLE
                Player.STATE_ENDED -> binding.progressBar.visibility = View.GONE
                Player.STATE_IDLE -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon(isPlaying)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPiPSupported()) {
                if (isPlaying) {
                    updatePipAction(R.drawable.ic_pause, R.string.exo_controls_pause_description, CONTROL_TYPE_PAUSE, REQUEST_PAUSE)
                } else {
                    updatePipAction(R.drawable.ic_play, R.string.exo_controls_play_description, CONTROL_TYPE_PLAY, REQUEST_PLAY)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            binding.progressBar.visibility = View.GONE
            val errorMsg = mapPlaybackError(error)
            Timber.e(error, "Player Error: $errorMsg")
            binding.errorView.visibility = View.VISIBLE
            binding.errorText.text = errorMsg
        }
        
        override fun onVideoSizeChanged(videoSize: VideoSize) {
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) updatePipParamsBasic()
        }
    }

    private fun mapPlaybackError(error: PlaybackException): String {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_TIMEOUT -> "Connection Failed"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Stream Unavailable (${error.message})"
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED -> "DRM License Failed"
            else -> "Playback Error: ${error.errorCodeName}"
        }
    }

    private fun buildDrmSessionManager(
        info: StreamInfo, 
        headers: Map<String, String>, 
        dataSourceFactory: DefaultHttpDataSource.Factory
    ): DefaultDrmSessionManager? {
        val scheme = info.drmScheme ?: return null

        return try {
            when (scheme) {
                "clearkey" -> {
                    if (info.drmKeyId != null && info.drmKey != null) {
                        createClearKeyDrmManager(info.drmKeyId, info.drmKey)
                    } else null
                }
                "widevine" -> {
                    if (info.drmLicenseUrl != null) {
                        createHttpDrmManager(C.WIDEVINE_UUID, info.drmLicenseUrl, headers, dataSourceFactory)
                    } else null
                }
                "playready" -> {
                    if (info.drmLicenseUrl != null) {
                        createHttpDrmManager(C.PLAYREADY_UUID, info.drmLicenseUrl, headers, dataSourceFactory)
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            Timber.e(e, "DRM Init Failed")
            null
        }
    }

    private fun createHttpDrmManager(
        uuid: UUID, 
        url: String, 
        headers: Map<String, String>,
        factory: DefaultHttpDataSource.Factory
    ): DefaultDrmSessionManager {
        val callback = HttpMediaDrmCallback(url, factory)
        headers.forEach { (k, v) -> callback.setKeyRequestProperty(k, v) }
        
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(false)
            .build(callback)
    }

    private fun createClearKeyDrmManager(keyIdHex: String, keyHex: String): DefaultDrmSessionManager {
        val clearKeyUuid = UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")
        // Convert Hex to Base64 (URL Safe)
        val kId = android.util.Base64.encodeToString(hexToBytes(keyIdHex), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        val k = android.util.Base64.encodeToString(hexToBytes(keyHex), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        
        val jwk = """{"keys":[{"kty":"oct","k":"$k","kid":"$kId"}]}"""
        val callback = LocalMediaDrmCallback(jwk.toByteArray())
        
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(clearKeyUuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(callback)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { it.isLetterOrDigit() }.lowercase()
        if (clean.length % 2 != 0) return ByteArray(0)
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun parseStreamUrl(streamUrl: String): StreamInfo {
        val pipeIndex = streamUrl.indexOf('|')
        if (pipeIndex == -1) return StreamInfo(streamUrl, emptyMap(), null, null, null, null)

        val url = streamUrl.substring(0, pipeIndex).trim()
        val params = streamUrl.substring(pipeIndex + 1).split("&", "|")
        val headers = mutableMapOf<String, String>()
        var drmScheme: String? = null
        var drmKeyId: String? = null
        var drmKey: String? = null
        var drmLicenseUrl: String? = null

        for (param in params) {
            val parts = param.split("=", limit = 2)
            if (parts.size != 2) continue
            val key = parts[0].trim().lowercase()
            val value = parts[1].trim()

            when (key) {
                "drmscheme" -> drmScheme = normalizeDrmScheme(value)
                "drmlicense" -> {
                    if (value.startsWith("http")) drmLicenseUrl = value
                    else {
                        val kParts = value.split(":", limit = 2)
                        if (kParts.size == 2) {
                            drmKeyId = kParts[0]
                            drmKey = kParts[1]
                        }
                    }
                }
                "user-agent", "useragent" -> headers["User-Agent"] = value
                "referer" -> headers["Referer"] = value
                "cookie" -> headers["Cookie"] = value
                else -> headers[parts[0].trim()] = value
            }
        }
        return StreamInfo(url, headers, drmScheme, drmKeyId, drmKey, drmLicenseUrl)
    }

    private fun normalizeDrmScheme(scheme: String): String {
        val lower = scheme.lowercase()
        return when {
            lower.contains("widevine") -> "widevine"
            lower.contains("playready") -> "playready"
            lower.contains("clearkey") -> "clearkey"
            else -> lower
        }
    }

    private data class StreamInfo(
        val url: String,
        val headers: Map<String, String>,
        val drmScheme: String?,
        val drmKeyId: String?,
        val drmKey: String?,
        val drmLicenseUrl: String?
    )

    private fun configurePlayerView() {
        binding.playerView.apply {
            useController = true
            controllerShowTimeoutMs = 5000
            controllerHideOnTouch = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            controllerAutoShow = false
        }
    }

    // ============================================================================================
    // PIP LOGIC
    // ============================================================================================

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        if (!isPiPSupported()) return
        
        // Hide UI
        binding.playerView.hideController()
        
        val format = player?.videoFormat
        val builder = mPictureInPictureParamsBuilder ?: PictureInPictureParams.Builder()
        
        if (format != null) {
            // Fix surface view sizing
            val videoSurfaceView = binding.playerView.videoSurfaceView
            if (videoSurfaceView is SurfaceView) {
                videoSurfaceView.holder.setFixedSize(format.width, format.height)
            }

            var rational = Rational(format.width, format.height)
            
            // Aspect Ratio Clamping
            if (rational.toFloat() > rationalLimitWide.toFloat()) rational = rationalLimitWide
            if (rational.toFloat() < rationalLimitTall.toFloat()) rational = rationalLimitTall
            
            builder.setAspectRatio(rational)
        }
        
        // Update source rect for smooth transition
        binding.playerView.getGlobalVisibleRect(pipSourceRect)
        builder.setSourceRectHint(pipSourceRect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(false)
        }

        enterPictureInPictureMode(builder.build())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipParamsBasic() {
        if (!isInPipMode || mPictureInPictureParamsBuilder == null) return
        try {
            val format = player?.videoFormat ?: return
            var rational = Rational(format.width, format.height)
             // Aspect Ratio Clamping
            if (rational.toFloat() > rationalLimitWide.toFloat()) rational = rationalLimitWide
            if (rational.toFloat() < rationalLimitTall.toFloat()) rational = rationalLimitTall

            val builder = mPictureInPictureParamsBuilder!!
            builder.setAspectRatio(rational)
            setPictureInPictureParams(builder.build())
        } catch (e: Exception) {
            Timber.w("Failed to update PiP params")
        }
    }
    
    private fun updatePipAction(iconId: Int, titleRes: Int, controlType: Int, reqCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        
        try {
            val builder = mPictureInPictureParamsBuilder ?: return
            val actions = ArrayList<RemoteAction>()
            
            val intent = Intent(ACTION_MEDIA_CONTROL).apply {
                setPackage(packageName)
                putExtra(EXTRA_CONTROL_TYPE, controlType)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                this, 
                reqCode, 
                intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val icon = Icon.createWithResource(this, iconId)
            actions.add(RemoteAction(icon, getString(titleRes), getString(titleRes), pendingIntent))
            
            builder.setActions(actions)
            setPictureInPictureParams(builder.build())
        } catch (e: Exception) {
            Timber.e(e, "Error updating PiP actions")
        }
    }

    private fun isPiPSupported() = 
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && 
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        
        if (isInPipMode) {
            binding.playerView.hideController()
            binding.relatedChannelsSection.visibility = View.GONE
        } else {
            binding.playerView.setControllerAutoShow(true)
            
            // If user didn't intentionally leave, finish activity
            if (!userRequestedPip && lifecycle.currentState == Lifecycle.State.CREATED) {
                finish()
                return
            }
            userRequestedPip = false
            
            // Restore UI
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            applyOrientationSettings(isLandscape)
            
            if (isLocked) {
                binding.playerView.useController = false
                binding.lockOverlay.visibility = View.VISIBLE
            } else {
                binding.playerView.useController = true
                binding.playerView.showController()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPipMode && player?.isPlaying == true && isPiPSupported()) {
            userRequestedPip = true
            enterPipMode()
        }
    }

    // ============================================================================================
    // UI & CONTROLS
    // ============================================================================================

    private fun bindControllerViewsExact() {
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
        
        // Setup initial UI state
        tvChannelName?.text = channel.name
        btnLock?.setImageResource(if (isLocked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open)
        updatePlayPauseIcon(player?.isPlaying == true)
        
        // Enable buttons
        listOf(btnBack, btnPip, btnSettings, btnLock, btnMute, btnRewind, btnPlayPause, btnForward, btnFullscreen, btnAspectRatio)
            .forEach { it?.apply { isClickable = true; isFocusable = true; isEnabled = true } }
    }

    private fun bindControllerViewsOnce() {
        if (isBindingControls) return
        isBindingControls = true
        bindControllerViewsExact()
        mainHandler.postDelayed({ isBindingControls = false }, 500)
    }

    private fun setupControlListenersOnce() {
        controlsBindingRunnable?.let { mainHandler.removeCallbacks(it) }
        controlsBindingRunnable = Runnable {
            btnBack?.setOnClickListener { if (!isLocked) finish() }
            btnPip?.setOnClickListener { 
                if (!isLocked && isPiPSupported()) {
                    userRequestedPip = true
                    enterPipMode() 
                }
            }
            btnSettings?.setOnClickListener { if (!isLocked) showPlayerSettingsDialog() }
            btnAspectRatio?.setOnClickListener { if (!isLocked) toggleAspectRatio() }
            btnLock?.setOnClickListener { toggleLock() }
            btnPlayPause?.setOnClickListener { 
                if (!isLocked) {
                    if (binding.errorView.visibility == View.VISIBLE) {
                        releasePlayer()
                        setupPlayer()
                    } else {
                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                    }
                }
            }
            btnFullscreen?.setOnClickListener { if (!isLocked) toggleFullscreen() }
            // Add other listeners as needed (Rewind, Forward, Mute)
            controlsBindingRunnable = null
        }
        mainHandler.post(controlsBindingRunnable!!)
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        btnPlayPause?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun toggleAspectRatio() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        binding.playerView.resizeMode = currentResizeMode
        Toast.makeText(this, when(currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"
            else -> "Fill"
        }, Toast.LENGTH_SHORT).show()
    }

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            binding.playerView.hideController()
            binding.playerView.useController = false
            binding.lockOverlay.visibility = View.VISIBLE
            btnLock?.setImageResource(R.drawable.ic_lock_closed)
            showUnlockButton()
        } else {
            binding.playerView.useController = true
            binding.playerView.showController()
            binding.lockOverlay.visibility = View.GONE
            btnLock?.setImageResource(R.drawable.ic_lock_open)
            hideUnlockButton()
        }
    }

    private fun setupLockOverlay() {
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE
        binding.unlockButton.setOnClickListener { toggleLock() }
        binding.lockOverlay.setOnClickListener {
            if (binding.unlockButton.visibility == View.VISIBLE) hideUnlockButton() else showUnlockButton()
        }
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
    
    private fun showPlayerSettingsDialog() {
        player?.let { 
            try {
                com.livetvpro.ui.player.settings.PlayerSettingsDialog(this, it).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Settings unavailable", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleFullscreen() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // ============================================================================================
    // LIFECYCLE & UTILS
    // ============================================================================================

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { switchToChannel(it) }
        binding.relatedChannelsRecycler.apply {
            layoutManager = GridLayoutManager(this@ChannelPlayerActivity, 3)
            adapter = relatedChannelsAdapter
            setHasFixedSize(true)
        }
    }

    private fun loadRelatedChannels() {
        viewModel.loadRelatedChannels(channel.categoryId, channel.id)
        viewModel.relatedChannels.observe(this) { channels ->
            relatedChannels = channels
            relatedChannelsAdapter.submitList(channels)
            binding.relatedChannelsSection.visibility = if (channels.isEmpty()) View.GONE else View.VISIBLE
            binding.relatedLoadingProgress.visibility = View.GONE
            binding.relatedChannelsRecycler.visibility = View.VISIBLE
            binding.relatedCount.text = channels.size.toString()
        }
    }

    private fun switchToChannel(newChannel: Channel) {
        releasePlayer()
        channel = newChannel
        tvChannelName?.text = channel.name
        setupPlayer()
        binding.relatedLoadingProgress.visibility = View.VISIBLE
        binding.relatedChannelsRecycler.visibility = View.GONE
        loadRelatedChannels()
    }

    private fun applyOrientationSettings(isLandscape: Boolean) {
        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        if (isLandscape) {
            hideSystemUi()
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            params.dimensionRatio = null
            params.height = ConstraintLayout.LayoutParams.MATCH_PARENT
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            binding.relatedChannelsSection.visibility = View.GONE
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            showSystemUi()
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            params.dimensionRatio = "16:9"
            params.height = 0
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            if (relatedChannels.isNotEmpty()) binding.relatedChannelsSection.visibility = View.VISIBLE
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
        }
        binding.playerContainer.layoutParams = params
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    private fun showSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT > 23) setupPlayer()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT <= 23 || player == null) setupPlayer()
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT <= 23) releasePlayer()
        else if (!isInPipMode) player?.pause()
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT > 23) releasePlayer()
        if (isInPipMode) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { unregisterReceiver(pipReceiver) } catch (e: Exception) { /* Ignored */ }
        }
    }
}
