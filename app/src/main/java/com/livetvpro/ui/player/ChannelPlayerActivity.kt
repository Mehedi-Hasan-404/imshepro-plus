package com.livetvpro.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import java.util.UUID

@UnstableApi
@AndroidEntryPoint
class ChannelPlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChannelPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private lateinit var channel: Channel
    
    // Related Channels Properties
    private lateinit var relatedChannelsAdapter: RelatedChannelAdapter
    private var relatedChannels = listOf<Channel>()

    // Controller Views
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

    // State flags
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

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_MEDIA_CONTROL) {
                val controlType = intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)
                when (controlType) {
                    CONTROL_TYPE_PLAY -> {
                        player?.play()
                        updatePipParams()
                    }
                    CONTROL_TYPE_PAUSE -> {
                        player?.pause()
                        updatePipParams()
                    }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL), RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL))
            }
        }

        val currentOrientation = resources.configuration.orientation
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        
        // FIXED: Apply window flags immediately after setContentView to ensure status bar shows in portrait
        setWindowFlags(isLandscape)
        applyOrientationSettings(isLandscape)

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            finish()
            return
        }

        binding.progressBar.visibility = View.GONE
        setupPlayer()
        bindControllerViewsExact()
        tvChannelName?.text = channel.name
        setupControlListenersExact()
        configurePlayerInteractions()
        setupLockOverlay()

        setupRelatedChannels()
        loadRelatedChannels()
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { relatedChannel ->
            switchToChannel(relatedChannel)
        }

        val recyclerView = binding.relatedChannelsRecycler
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = relatedChannelsAdapter
        recyclerView.setHasFixedSize(true)
    }

    private fun loadRelatedChannels() {
        viewModel.loadRelatedChannels(channel.categoryId, channel.id)

        viewModel.relatedChannels.observe(this) { channels ->
            relatedChannels = channels
            relatedChannelsAdapter.submitList(channels)

            binding.relatedChannelsSection.visibility = if (channels.isEmpty()) {
                View.GONE
            } else {
                View.VISIBLE
            }

            binding.relatedLoadingProgress.visibility = View.GONE
            binding.relatedChannelsRecycler.visibility = View.VISIBLE
            binding.relatedCount.text = channels.size.toString()

            Timber.d("Loaded ${channels.size} related channels")
        }
    }

    private fun switchToChannel(newChannel: Channel) {
        Timber.d("Switching to channel: ${newChannel.name}")

        releasePlayer()
        channel = newChannel
        tvChannelName?.text = channel.name
        setupPlayer()

        binding.relatedLoadingProgress.visibility = View.VISIBLE
        binding.relatedChannelsRecycler.visibility = View.GONE
        loadRelatedChannels()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyOrientationSettings(isLandscape)
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT > 23) {
            setupPlayer()
            binding.playerView.onResume()
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
        setWindowFlags(isLandscape)
        adjustLayoutForOrientation(isLandscape)
        binding.playerContainer.requestLayout()
        binding.root.requestLayout()
    }

    private fun adjustLayoutForOrientation(isLandscape: Boolean) {
        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        if (isLandscape) {
            binding.playerView.hideController()
            binding.playerView.controllerAutoShow = false
            binding.playerView.controllerShowTimeoutMs = 3000
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            params.dimensionRatio = null
            params.height = ConstraintLayout.LayoutParams.MATCH_PARENT
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
            binding.relatedChannelsSection.visibility = View.GONE
        } else {
            binding.playerView.controllerAutoShow = true
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
        }
        binding.playerContainer.layoutParams = params
    }

    // FIXED: Status bar visibility - hides in landscape, shows in portrait
    private fun setWindowFlags(isLandscape: Boolean) {
        if (isLandscape) {
            // Landscape - Hide both status bar and navigation bar (full immersive)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { controller ->
                    controller.hide(
                        android.view.WindowInsets.Type.statusBars() or 
                        android.view.WindowInsets.Type.navigationBars()
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
            // Portrait - Always show both status bar and navigation bar
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(true)
                window.insetsController?.show(
                    android.view.WindowInsets.Type.statusBars() or 
                    android.view.WindowInsets.Type.navigationBars()
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
        releasePlayer()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                unregisterReceiver(pipReceiver)
            }
        } catch (e: Exception) {
            Timber.w(e, "Error unregistering PiP receiver")
        }
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun releasePlayer() {
        player?.let {
            try {
                it.stop()
                it.release()
            } catch (t: Throwable) {
                Timber.w(t, "Error releasing player")
            }
        }
        player = null
    }

    private data class StreamInfo(
        val url: String,
        val headers: Map<String, String>,
        val drmScheme: String?,
        val drmKeyId: String?,
        val drmKey: String?
    )

    private fun parseStreamUrl(streamUrl: String): StreamInfo {
        Timber.d("╔═══════════════════════════════════╗")
        Timber.d("🔍 PARSING STREAM URL")
        Timber.d("📥 Raw URL: $streamUrl")
        
        val pipeIndex = streamUrl.indexOf('|')
        if (pipeIndex == -1) {
            Timber.d("ℹ️ No pipe delimiter found - plain URL")
            Timber.d("╚═══════════════════════════════════╝")
            return StreamInfo(streamUrl, mapOf(), null, null, null)
        }
        
        val url = streamUrl.substring(0, pipeIndex).trim()
        val rawParams = streamUrl.substring(pipeIndex + 1).trim()
        
        Timber.d("📺 Clean URL: $url")
        Timber.d("⚙️ Raw Parameters: $rawParams")
        
        val normalizedParams = rawParams.replace("&", "|")
        val parts = normalizedParams.split("|")
        
        Timber.d("📋 Found ${parts.size} parameter parts")
        
        val headers = mutableMapOf<String, String>()
        var drmScheme: String? = null
        var drmKeyId: String? = null
        var drmKey: String? = null

        for (part in parts) {
            val eqIndex = part.indexOf('=')
            if (eqIndex == -1) {
                Timber.w("⚠️ Skipping invalid parameter: $part")
                continue
            }
            
            val key = part.substring(0, eqIndex).trim()
            val value = part.substring(eqIndex + 1).trim()
            
            Timber.d("   📌 Param: $key = ${value.take(20)}${if (value.length > 20) "..." else ""}")
            
            when (key.lowercase()) {
                "drmscheme" -> {
                    drmScheme = value.lowercase()
                    Timber.d("   🔐 DRM Scheme detected: $drmScheme")
                }
                "drmlicense" -> {
                    val keyParts = value.split(":")
                    if (keyParts.size == 2) {
                        drmKeyId = keyParts[0].trim()
                        drmKey = keyParts[1].trim()
                        Timber.d("   🔑 DRM Keys extracted:")
                        Timber.d("      KeyID: ${drmKeyId.take(16)}... (${drmKeyId.length} chars)")
                        Timber.d("      Key:   ${drmKey.take(16)}... (${drmKey.length} chars)")
                    } else {
                        Timber.e("   ❌ Invalid DRM License format: $value")
                    }
                }
                "referer", "referrer" -> {
                    headers["Referer"] = value
                    Timber.d("   🌐 Referer: $value")
                }
                "user-agent", "useragent" -> {
                    headers["User-Agent"] = value
                    Timber.d("   🖥️ User-Agent: ${value.take(30)}...")
                }
                "origin" -> {
                    headers["Origin"] = value
                    Timber.d("   🌍 Origin: $value")
                }
                "cookie" -> {
                    headers["Cookie"] = value
                    Timber.d("   🍪 Cookie: ${value.take(30)}...")
                }
                "x-forwarded-for" -> {
                    headers["X-Forwarded-For"] = value
                    Timber.d("   📡 X-Forwarded-For: $value")
                }
                else -> {
                    headers[key] = value
                    Timber.d("   📎 Custom header: $key")
                }
            }
        }
        
        Timber.d("📊 PARSING SUMMARY:")
        Timber.d("   URL: ✅")
        Timber.d("   Headers: ${headers.size}")
        Timber.d("   DRM Scheme: ${drmScheme ?: "❌ None"}")
        Timber.d("   DRM KeyID: ${if (drmKeyId != null) "✅ Present (${drmKeyId.length} chars)" else "❌ Missing"}")
        Timber.d("   DRM Key: ${if (drmKey != null) "✅ Present (${drmKey.length} chars)" else "❌ Missing"}")
        
        if (drmScheme != null && (drmKeyId == null || drmKey == null)) {
            Timber.e("⚠️ WARNING: DRM Scheme present but keys missing!")
        }
        
        Timber.d("╚═══════════════════════════════════╝")
        
        return StreamInfo(url, headers, drmScheme, drmKeyId, drmKey)
    }

    private fun setupPlayer() {
        if (player != null) return
        binding.errorView.visibility = View.GONE
        binding.errorText.text = ""
        binding.progressBar.visibility = View.VISIBLE

        trackSelector = DefaultTrackSelector(this)

        try {
            val streamInfo = parseStreamUrl(channel.streamUrl)
            
            Timber.d("╔═══════════════════════════════════╗")
            Timber.d("🎬 SETTING UP PLAYER")
            Timber.d("📺 Channel: ${channel.name}")
            Timber.d("🔗 URL: ${streamInfo.url}")
            Timber.d("🔒 DRM Scheme: ${streamInfo.drmScheme ?: "None"}")
            if (streamInfo.drmScheme != null) {
                Timber.d("🔑 DRM KeyID: ${streamInfo.drmKeyId?.take(16)}...")
                Timber.d("🔑 DRM Key: ${streamInfo.drmKey?.take(16)}...")
            }
            Timber.d("╚═══════════════════════════════════╝")

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
                Timber.d("╔═══════════════════════════════════╗")
                Timber.d("🔐 INITIALIZING DRM PROTECTION")
                Timber.d("🔒 Scheme: ${streamInfo.drmScheme}")
                
                val normalizedScheme = streamInfo.drmScheme.lowercase().let { scheme ->
                    when {
                        scheme.contains("clearkey") -> "clearkey"
                        scheme.contains("widevine") -> "widevine"
                        else -> scheme
                    }
                }
                
                val drmSessionManager = if (normalizedScheme == "clearkey") {
                    if (streamInfo.drmKeyId != null && streamInfo.drmKey != null) {
                        Timber.d("🔑 Creating ClearKey DRM Manager with keys...")
                        Timber.d("   KeyID Length: ${streamInfo.drmKeyId.length}")
                        Timber.d("   Key Length: ${streamInfo.drmKey.length}")
                        
                        createClearKeyDrmManager(streamInfo.drmKeyId, streamInfo.drmKey)
                    } else {
                        Timber.w("⚠️ ClearKey scheme but no keys provided - attempting playback without DRM")
                        null
                    }
                } else {
                    Timber.e("❌ Unsupported DRM scheme: $normalizedScheme")
                    null
                }
                
                Timber.d("╚═══════════════════════════════════╝")
                
                if (drmSessionManager != null) {
                    DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(
                    clearKeyUuid,
                    FrameworkMediaDrm.DEFAULT_PROVIDER
                )
                .setMultiSession(false)
                .build(drmCallback).also {
                    Timber.d("✅ ClearKey DRM manager created successfully")
                }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to create ClearKey DRM manager")
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "").replace("-", "")
        return cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        btnPlayPause?.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

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

        btnBack?.setImageResource(R.drawable.ic_arrow_back)
        btnPip?.setImageResource(R.drawable.ic_pip)
        btnSettings?.setImageResource(R.drawable.ic_settings)
        btnLock?.setImageResource(R.drawable.ic_lock_open)
        updateMuteIcon()
        btnRewind?.setImageResource(R.drawable.ic_skip_backward)
        btnPlayPause?.setImageResource(R.drawable.ic_pause)
        btnForward?.setImageResource(R.drawable.ic_skip_forward)
        btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
        btnAspectRatio?.setImageResource(R.drawable.ic_aspect_ratio)

        listOf(
            btnBack, btnPip, btnSettings, btnLock, btnMute,
            btnRewind, btnPlayPause, btnForward, btnFullscreen, btnAspectRatio
        ).forEach { button ->
            button?.apply {
                isClickable = true
                isFocusable = true
            }
        }

        btnAspectRatio?.visibility = View.VISIBLE
        btnPip?.visibility = View.VISIBLE
        btnFullscreen?.visibility = View.VISIBLE
    }

    private fun setupControlListenersExact() {
        btnBack?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!isLocked) finish()
        }

        btnPip?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!isLocked) {
                userRequestedPip = true
                enterPipMode()
            }
        }

        btnSettings?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!isLocked) {
                showPlayerSettingsDialog()
            }
        }

        btnAspectRatio?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!isLocked) toggleAspectRatio()
        }

        btnLock?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            toggleLock()
        }

        btnRewind?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!isLocked) {
                player?.let { p ->
                    val newPosition = p.currentPosition - skipMs
                    if (newPosition < 0) {
                        p.seekTo(0)
                    } else {
                        p.seekTo(newPosition)
                    }
                }
            }
        }

        btnPlayPause?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!isLocked) {
                player?.let { p ->
                    if (p.isPlaying) p.pause() else p.play()
                }
            }
        }

        btnForward?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
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
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!isLocked) toggleFullscreen()
        }

        btnMute?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!isLocked) toggleMute()
        }
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            updateMuteIcon()
            Toast.makeText(
                this,
                if (isMuted) "Muted" else "Unmuted",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateMuteIcon() {
        btnMute?.setImageResource(
            if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
    }

    private fun toggleAspectRatio() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> {
                Toast.makeText(this, "Zoom", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> {
                Toast.makeText(this, "Fit", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            else -> {
                Toast.makeText(this, "Fill", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        }
        binding.playerView.resizeMode = currentResizeMode
    }

    private fun showPlayerSettingsDialog() {
        val exoPlayer = player ?: return 

        try {
            val dialog = com.livetvpro.ui.player.settings.PlayerSettingsDialog(
                context = this,
                player = exoPlayer
            )
            dialog.show()
        } catch (e: Exception) {
            Timber.e(e, "Error showing player settings dialog")
            Toast.makeText(this, "Unable to open settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun configurePlayerInteractions() {
        binding.playerView.apply {
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                when (visibility) {
                    View.VISIBLE -> Timber.d("Controls shown")
                    View.GONE -> Timber.d("Controls hidden")
                }
            })
            setControllerHideDuringAds(false)
            controllerShowTimeoutMs = 5000
            controllerHideOnTouch = true
        }
    }

    private fun setupLockOverlay() {
        binding.unlockButton.setOnClickListener { toggleLock() }
        binding.lockOverlay.setOnClickListener {
            if (binding.unlockButton.visibility == View.VISIBLE) {
                hideUnlockButton()
            } else {
                showUnlockButton()
            }
        }
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE

        binding.retryButton.setOnClickListener {
            binding.errorView.visibility = View.GONE
            binding.progressBar.visibility = View.VISIBLE
            player?.release()
            player = null
            setupPlayer()
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

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            binding.playerView.useController = false
            binding.playerView.hideController()
            binding.lockOverlay.visibility = View.VISIBLE
            showUnlockButton()
            btnLock?.setImageResource(R.drawable.ic_lock_closed)
            binding.lockOverlay.isClickable = true
            binding.lockOverlay.isFocusable = true
        } else {
            binding.playerView.useController = true
            binding.playerView.showController()
            binding.lockOverlay.visibility = View.GONE
            hideUnlockButton()
            btnLock?.setImageResource(R.drawable.ic_lock_open)
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

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "PiP not supported", Toast.LENGTH_SHORT).show()
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, "PiP not available", Toast.LENGTH_SHORT).show()
            return
        }

        player?.let {
            if (!it.isPlaying) {
                it.play()
            }
        }

        binding.playerView.useController = false
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE
        updatePipParams(enter = true)
    }

    private fun updatePipParams(enter: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val format = player?.videoFormat
                val width = format?.width ?: 16
                val height = format?.height ?: 9
                val ratio = if (width > 0 && height > 0) {
                    Rational(width, height)
                } else {
                    Rational(16, 9)
                }

                val builder = PictureInPictureParams.Builder()
                builder.setAspectRatio(ratio)

                val actions = ArrayList<RemoteAction>()

                val isPlaying = player?.isPlaying == true
                val playPauseIconId = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                val playPauseTitle = if (isPlaying) "Pause" else "Play"
                val playPauseControlType = if (isPlaying) CONTROL_TYPE_PAUSE else CONTROL_TYPE_PLAY

                val playPauseIntent = Intent(ACTION_MEDIA_CONTROL).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_CONTROL_TYPE, playPauseControlType)
                }

                val playPausePendingIntent = PendingIntent.getBroadcast(
                    this,
                    playPauseControlType,
                    playPauseIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                val playPauseIcon = Icon.createWithResource(this, playPauseIconId)
                actions.add(RemoteAction(playPauseIcon, playPauseTitle, playPauseTitle, playPausePendingIntent))

                builder.setActions(actions)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(false)
                    builder.setSeamlessResizeEnabled(true)
                }

                if (enter) {
                    val success = enterPictureInPictureMode(builder.build())
                    if (success) {
                        isInPipMode = true
                    }
                } else if (isInPipMode) {
                    setPictureInPictureParams(builder.build())
                }
            } catch (e: Exception) {
                Timber.e(e, "PiP Error")
            }
        }
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
            binding.relatedChannelsSection.visibility = View.GONE
        } else {
            if (!userRequestedPip && lifecycle.currentState == Lifecycle.State.CREATED) {
                finish()
                return
            }
            userRequestedPip = false
            if (isFinishing) {
                return
            }
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            applyOrientationSettings(isLandscape)

            if (isLocked) {
                binding.playerView.useController = false
                binding.lockOverlay.visibility = View.VISIBLE
                showUnlockButton()
            } else {
                binding.playerView.useController = true
                binding.lockOverlay.visibility = View.GONE
                binding.playerView.postDelayed({
                    if (!this.isInPipMode) {
                        binding.playerView.showController()
                    }
                }, 150)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPipMode && player?.isPlaying == true) {
            userRequestedPip = true
            enterPipMode()
        }
    }

    override fun finish() {
        try {
            releasePlayer()
            isInPipMode = false
            userRequestedPip = false
            super.finish()
        } catch (e: Exception) {
            Timber.e(e, "Error in finish()")
            super.finish()
        }
    }
}MediaSourceFactory(this)
                        .setDataSourceFactory(dataSourceFactory)
                        .setDrmSessionManagerProvider { drmSessionManager }
                } else {
                    Timber.w("⚠️ Falling back to non-DRM playback")
                    DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(dataSourceFactory)
                }
            } else {
                Timber.d("🔓 No DRM protection - regular stream")
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
                                    updatePipParams()
                                    Timber.d("✅ Player ready - PLAYING!")
                                }
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar.visibility = View.VISIBLE
                                    binding.errorView.visibility = View.GONE
                                    Timber.d("⏳ Buffering...")
                                }
                                Player.STATE_ENDED -> {
                                    binding.progressBar.visibility = View.GONE
                                    Timber.d("ℹ️ Playback ended")
                                }
                                Player.STATE_IDLE -> {
                                    Timber.d("💤 Player idle")
                                }
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            updatePlayPauseIcon(isPlaying)
                            Timber.d("▶️ Is playing: $isPlaying")
                            if (isInPipMode) {
                                updatePipParams()
                            }
                        }

                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            super.onVideoSizeChanged(videoSize)
                            Timber.d("📐 Video size: ${videoSize.width}x${videoSize.height}")
                            if (isInPipMode) {
                                updatePipParams()
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            super.onPlayerError(error)
                            Timber.e("╔═══════════════════════════════════╗")
                            Timber.e("❌ PLAYBACK ERROR")
                            Timber.e("📺 Channel: ${channel.name}")
                            Timber.e("🔗 URL: ${streamInfo.url}")
                            Timber.e("🔒 DRM: ${streamInfo.drmScheme ?: "None"}")
                            if (streamInfo.drmKeyId != null) {
                                Timber.e("🔑 Has Keys: YES (${streamInfo.drmKeyId.length} + ${streamInfo.drmKey?.length})")
                            }
                            Timber.e("💥 Error: ${error.message}")
                            Timber.e("📋 Error Code: ${error.errorCode}")
                            Timber.e("🔍 Cause: ${error.cause?.message}")
                            Timber.e("╚═══════════════════════════════════╝")
                            
                            binding.progressBar.visibility = View.GONE
                            
                            val errorMessage = when {
                                error.message?.contains("drm", ignoreCase = true) == true ->
                                    "DRM Protection Error\n\nThis stream requires valid decryption keys.\nError: ${error.message}"
                                
                                error.message?.contains("clearkey", ignoreCase = true) == true ->
                                    "ClearKey DRM Error\n\nInvalid or missing license keys.\nError: ${error.message}"
                                
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                                    "Connection Error\n\nUnable to reach the server.\nError: ${error.message}"
                                
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                                    "Network Error\n\nCheck your internet connection.\nError: ${error.message}"
                                
                                error.message?.contains("403", ignoreCase = true) == true ->
                                    "Access Denied\n\nThis stream may require authentication.\nError: ${error.message}"
                                
                                error.message?.contains("404", ignoreCase = true) == true ->
                                    "Stream Not Found\n\nThe stream URL may be invalid or expired.\nError: ${error.message}"
                                
                                else -> "Playback Error\n\n${error.message ?: "Unknown error occurred"}"
                            }
                            
                            Toast.makeText(this@ChannelPlayerActivity, errorMessage, Toast.LENGTH_LONG).show()
                            binding.errorView.visibility = View.VISIBLE
                            binding.errorText.text = errorMessage
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error creating ExoPlayer")
            Toast.makeText(this, "Failed to initialize player: ${e.message}", Toast.LENGTH_LONG).show()
        }

        binding.playerView.apply {
            useController = true
            controllerShowTimeoutMs = 5000
            controllerHideOnTouch = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
    }

    private fun createClearKeyDrmManager(
        keyIdHex: String,
        keyHex: String
    ): DefaultDrmSessionManager? {
        return try {
            val clearKeyUuid = UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")

            Timber.d("🔐 Creating ClearKey DRM manager")
            Timber.d("🔑 KeyID: ${keyIdHex.take(8)}...")
            Timber.d("🔑 Key: ${keyHex.take(8)}...")

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

            Timber.d("🔐 Generated JWK response for ClearKey")

            val drmCallback = LocalMediaDrmCallback(jwkResponse.toByteArray())

            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(
                    clearKeyUuid,
                    FrameworkMediaDrm.DEFAULT_PROVIDER
                )
                .setMultiSession(false)
                .build(drmCallback).also {
                    Timber.d("✅ ClearKey DRM manager created successfully")
                }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to create ClearKey DRM manager")
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "").replace("-", "")
        return cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        btnPlayPause?.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

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

        btnBack?.setImageResource(R.drawable.ic_arrow_back)
        btnPip?.setImageResource(R.drawable.ic_pip)
        btnSettings?.setImageResource(R.drawable.ic_settings)
        btnLock?.setImageResource(R.drawable.ic_lock_open)
        updateMuteIcon()
        btnRewind?.setImageResource(R.drawable.ic_skip_backward)
        btnPlayPause?.setImageResource(R.drawable.ic_pause)
        btnForward?.setImageResource(R.drawable.ic_skip_forward)
        btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
        btnAspectRatio?.setImageResource(R.drawable.ic_aspect_ratio)

        listOf(
            btnBack, btnPip, btnSettings, btnLock, btnMute,
            btnRewind, btnPlayPause, btnForward, btnFullscreen, btnAspectRatio
        ).forEach { button ->
            button?.apply {
                isClickable = true
                isFocusable = true
            }
        }

        btnAspectRatio?.visibility = View.VISIBLE
        btnPip?.visibility = View.VISIBLE
        btnFullscreen?.visibility = View.VISIBLE
    }

    private fun setupControlListenersExact() {
        btnBack?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!isLocked) finish()
        }

        btnPip?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!isLocked) {
                userRequestedPip = true
                enterPipMode()
            }
        }

        btnSettings?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!isLocked) {
                showPlayerSettingsDialog()
            }
        }

        btnAspectRatio?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!isLocked) toggleAspectRatio()
        }

        btnLock?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            toggleLock()
        }

        btnRewind?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!isLocked) {
                player?.let { p ->
                    val newPosition = p.currentPosition - skipMs
                    if (newPosition < 0) {
                        p.seekTo(0)
                    } else {
                        p.seekTo(newPosition)
                    }
                }
            }
        }

        btnPlayPause?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!isLocked) {
                player?.let { p ->
                    if (p.isPlaying) p.pause() else p.play()
                }
            }
        }

        btnForward?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
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
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!isLocked) toggleFullscreen()
        }

        btnMute?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (!isLocked) toggleMute()
        }
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            updateMuteIcon()
            Toast.makeText(
                this,
                if (isMuted) "Muted" else "Unmuted",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateMuteIcon() {
        btnMute?.setImageResource(
            if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
    }

    private fun toggleAspectRatio() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> {
                Toast.makeText(this, "Zoom", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> {
                Toast.makeText(this, "Fit", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            else -> {
                Toast.makeText(this, "Fill", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        }
        binding.playerView.resizeMode = currentResizeMode
    }

    private fun showPlayerSettingsDialog() {
        val exoPlayer = player ?: return 

        try {
            val dialog = com.livetvpro.ui.player.settings.PlayerSettingsDialog(
                context = this,
                player = exoPlayer
            )
            dialog.show()
        } catch (e: Exception) {
            Timber.e(e, "Error showing player settings dialog")
            Toast.makeText(this, "Unable to open settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun configurePlayerInteractions() {
        binding.playerView.apply {
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                when (visibility) {
                    View.VISIBLE -> Timber.d("Controls shown")
                    View.GONE -> Timber.d("Controls hidden")
                }
            })
            setControllerHideDuringAds(false)
            controllerShowTimeoutMs = 5000
            controllerHideOnTouch = true
        }
    }

    private fun setupLockOverlay() {
        binding.unlockButton.setOnClickListener { toggleLock() }
        binding.lockOverlay.setOnClickListener {
            if (binding.unlockButton.visibility == View.VISIBLE) {
                hideUnlockButton()
            } else {
                showUnlockButton()
            }
        }
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE

        binding.retryButton.setOnClickListener {
            binding.errorView.visibility = View.GONE
            binding.progressBar.visibility = View.VISIBLE
            player?.release()
            player = null
            setupPlayer()
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

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            binding.playerView.useController = false
            binding.playerView.hideController()
            binding.lockOverlay.visibility = View.VISIBLE
            showUnlockButton()
            btnLock?.setImageResource(R.drawable.ic_lock_closed)
            binding.lockOverlay.isClickable = true
            binding.lockOverlay.isFocusable = true
        } else {
            binding.playerView.useController = true
            binding.playerView.showController()
            binding.lockOverlay.visibility = View.GONE
            hideUnlockButton()
            btnLock?.setImageResource(R.drawable.ic_lock_open)
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

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "PiP not supported", Toast.LENGTH_SHORT).show()
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, "PiP not available", Toast.LENGTH_SHORT).show()
            return
        }

        player?.let {
            if (!it.isPlaying) {
                it.play()
            }
        }

        binding.playerView.useController = false
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE
        updatePipParams(enter = true)
    }

    private fun updatePipParams(enter: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val format = player?.videoFormat
                val width = format?.width ?: 16
                val height = format?.height ?: 9
                val ratio = if (width > 0 && height > 0) {
                    Rational(width, height)
                } else {
                    Rational(16, 9)
                }

                val builder = PictureInPictureParams.Builder()
                builder.setAspectRatio(ratio)

                val actions = ArrayList<RemoteAction>()

                val isPlaying = player?.isPlaying == true
                val playPauseIconId = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                val playPauseTitle = if (isPlaying) "Pause" else "Play"
                val playPauseControlType = if (isPlaying) CONTROL_TYPE_PAUSE else CONTROL_TYPE_PLAY

                val playPauseIntent = Intent(ACTION_MEDIA_CONTROL).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_CONTROL_TYPE, playPauseControlType)
                }

                val playPausePendingIntent = PendingIntent.getBroadcast(
                    this,
                    playPauseControlType,
                    playPauseIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                val playPauseIcon = Icon.createWithResource(this, playPauseIconId)
                actions.add(RemoteAction(playPauseIcon, playPauseTitle, playPauseTitle, playPausePendingIntent))

                builder.setActions(actions)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(false)
                    builder.setSeamlessResizeEnabled(true)
                }

                if (enter) {
                    val success = enterPictureInPictureMode(builder.build())
                    if (success) {
                        isInPipMode = true
                    }
                } else if (isInPipMode) {
                    setPictureInPictureParams(builder.build())
                }
            } catch (e: Exception) {
                Timber.e(e, "PiP Error")
            }
        }
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
            binding.relatedChannelsSection.visibility = View.GONE
        } else {
            if (!userRequestedPip && lifecycle.currentState == Lifecycle.State.CREATED) {
                finish()
                return
            }
            userRequestedPip = false
            if (isFinishing) {
                return
            }
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            applyOrientationSettings(isLandscape)

            if (isLocked) {
                binding.playerView.useController = false
                binding.lockOverlay.visibility = View.VISIBLE
                showUnlockButton()
            } else {
                binding.playerView.useController = true
                binding.lockOverlay.visibility = View.GONE
                binding.playerView.postDelayed({
                    if (!this.isInPipMode) {
                        binding.playerView.showController()
                    }
                }, 150)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPipMode && player?.isPlaying == true) {
            userRequestedPip = true
            enterPipMode()
        }
    }

    override fun finish() {
        try {
            releasePlayer()
            isInPipMode = false
            userRequestedPip = false
            super.finish()
        } catch (e: Exception) {
            Timber.e(e, "Error in finish()")
            super.finish()
        }
    }
}
