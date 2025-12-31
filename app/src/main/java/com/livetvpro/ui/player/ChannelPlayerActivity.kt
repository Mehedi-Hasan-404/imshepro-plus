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
import androidx.annotation.RequiresApi
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
import androidx.media3.ui.SubtitleView
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
    
    // PiP specific properties
    private var mPictureInPictureParamsBuilder: Any? = null
    private val rationalLimitWide = Rational(239, 100)
    private val rationalLimitTall = Rational(100, 239)
    
    // Cache the source rect so we don't lose it while in PiP
    private val pipSourceRect = Rect()
    
    private var isBindingControls = false
    private var controlsBindingRunnable: Runnable? = null

    // FIXED: Single broadcast receiver for PiP controls
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || ACTION_MEDIA_CONTROL != intent.action) {
                return
            }
            
            val player = player ?: return

            when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                CONTROL_TYPE_PLAY -> {
                    player.play()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        updatePictureInPictureActions(
                            R.drawable.ic_pause,
                            R.string.exo_controls_pause_description,
                            CONTROL_TYPE_PAUSE,
                            REQUEST_PAUSE
                        )
                    }
                }
                CONTROL_TYPE_PAUSE -> {
                    player.pause()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        updatePictureInPictureActions(
                            R.drawable.ic_play,
                            R.string.exo_controls_play_description,
                            CONTROL_TYPE_PLAY,
                            REQUEST_PLAY
                        )
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
        private const val REQUEST_PLAY = 1
        private const val REQUEST_PAUSE = 2

        fun start(context: Context, channel: Channel) {
            val intent = Intent(context, ChannelPlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel)
            }
            context.startActivity(intent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                // Initialize PiP builder
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    mPictureInPictureParamsBuilder = PictureInPictureParams.Builder()
    
    updatePictureInPictureActions(
        R.drawable.ic_pause,
        R.string.exo_controls_pause_description,
        CONTROL_TYPE_PAUSE,
        REQUEST_PAUSE
    )
}


                // FIXED: Register receiver only once
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // MUST BE EXPORTED for System UI to trigger it
                registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL), RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL))
            }
        }


        binding.root.post {
            val currentOrientation = resources.configuration.orientation
            val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
            applyOrientationSettings(isLandscape)
        }

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            finish()
            return
        }

        binding.progressBar.visibility = View.GONE
        setupPlayer()
        
        // Update PiP params when layout changes so the sourceRect is always accurate
        binding.playerView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    updatePipParamsBasic()
                }
            }
        }
        
        // Single delayed binding after player setup
        binding.playerView.postDelayed({
            bindControllerViewsOnce()
            setupControlListenersOnce()
        }, 300)
        
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        // Don't apply settings if in PiP mode
        if (isInPipMode) {
            return
        }
        
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyOrientationSettings(isLandscape)
        
        // Update subtitle text size based on orientation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isInPip()) {
            setSubtitleTextSize()
        }
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
        }
        
        binding.playerContainer.layoutParams = params
    }

    private fun setWindowFlags(isLandscape: Boolean) {
        if (isLandscape) {
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
        mainHandler.removeCallbacksAndMessages(null)
        controlsBindingRunnable?.let { mainHandler.removeCallbacks(it) }
        controlsBindingRunnable = null
        releasePlayer()
        
        // FIXED: Unregister receiver only once
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                unregisterReceiver(pipReceiver)
            }
        } catch (e: Exception) {
            Timber.w(e, "Error unregistering PiP receiver")
        }
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
                "drmscheme" -> drmScheme = value.lowercase()
                "drmlicense" -> {
                    if (value.startsWith("http://", ignoreCase = true) || 
                        value.startsWith("https://", ignoreCase = true)) {
                        drmLicenseUrl = value
                    } else {
                        val keyParts = value.split(":")
                        if (keyParts.size == 2) {
                            drmKeyId = keyParts[0].trim()
                            drmKey = keyParts[1].trim()
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

    private fun setupPlayer() {
        if (player != null) return
        binding.errorView.visibility = View.GONE
        binding.errorText.text = ""
        binding.progressBar.visibility = View.VISIBLE

        trackSelector = DefaultTrackSelector(this)

        try {
            val streamInfo = parseStreamUrl(channel.streamUrl)
            
            val isDash = streamInfo.url.contains(".mpd", ignoreCase = true)
            val isHls = streamInfo.url.contains(".m3u8", ignoreCase = true) || streamInfo.url.contains("/hls/", ignoreCase = true)

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
                val normalizedScheme = streamInfo.drmScheme.lowercase().let { scheme ->
                    when {
                        scheme.contains("clearkey") -> "clearkey"
                        scheme.contains("widevine") -> "widevine"
                        scheme.contains("playready") -> "playready"
                        else -> scheme
                    }
                }
                
                val drmSessionManager = when (normalizedScheme) {
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
                                        updatePipParamsBasic()
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
                            
                            // Update PiP actions based on playback state
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPiPSupported()) {
                                if (isPlaying) {
                                    updatePictureInPictureActions(
                                        R.drawable.ic_pause,
                                        R.string.exo_controls_pause_description,
                                        CONTROL_TYPE_PAUSE,
                                        REQUEST_PAUSE
                                    )
                                } else {
                                    updatePictureInPictureActions(
                                        R.drawable.ic_play,
                                        R.string.exo_controls_play_description,
                                        CONTROL_TYPE_PLAY,
                                        REQUEST_PLAY
                                    )
                                }
                            }
                        }

                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            super.onVideoSizeChanged(videoSize)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                updatePipParamsBasic()
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            super.onPlayerError(error)
                            binding.progressBar.visibility = View.GONE
                            
                            // Simple, user-friendly error messages
                            val errorMessage = when {
                                // Network/Connection errors
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT ->
                                    "Connection Failed"
                                
                                // HTTP errors
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                                    when {
                                        error.message?.contains("403") == true -> "Access Denied"
                                        error.message?.contains("404") == true -> "Stream Not Found"
                                        error.message?.contains("500") == true ||
                                        error.message?.contains("503") == true -> "Server Error"
                                        else -> "Connection Failed"
                                    }
                                }
                                
                                // DRM, parsing, decoder - all just "Stream Error"
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
                                
                                // Geo-blocked
                                error.message?.contains("geo", ignoreCase = true) == true ||
                                error.message?.contains("region", ignoreCase = true) == true ->
                                    "Not Available"
                                
                                // Generic fallback
                                else -> "Playback Error"
                            }
                            
                            Toast.makeText(this@ChannelPlayerActivity, errorMessage, Toast.LENGTH_SHORT).show()
                            binding.errorView.visibility = View.VISIBLE
                            binding.errorText.text = errorMessage
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "Error creating ExoPlayer")
            Toast.makeText(this, "Failed to initialize player: ${e.message}", Toast.LENGTH_LONG).show()
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

val drmCallback = androidx.media3.exoplayer.drm.HttpMediaDrmCallback(licenseUrl, licenseDataSourceFactory)
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

        val drmCallback = androidx.media3.exoplayer.drm.HttpMediaDrmCallback(licenseUrl, licenseDataSourceFactory)
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
    btnLock?.setImageResource(if (isLocked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open)
    updateMuteIcon()
    btnRewind?.setImageResource(R.drawable.ic_skip_backward)
    updatePlayPauseIcon(player?.isPlaying == true)
    btnForward?.setImageResource(R.drawable.ic_skip_forward)
    
    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    btnFullscreen?.setImageResource(if (isLandscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
    btnAspectRatio?.setImageResource(R.drawable.ic_aspect_ratio)

    listOf(btnBack, btnPip, btnSettings, btnLock, btnMute, btnRewind, btnPlayPause, btnForward, btnFullscreen, btnAspectRatio).forEach { 
        it?.apply { isClickable = true; isFocusable = true; isEnabled = true }
    }

    btnAspectRatio?.visibility = View.VISIBLE
    btnPip?.visibility = View.VISIBLE
    btnFullscreen?.visibility = View.VISIBLE
    tvChannelName?.text = channel.name
}

private fun bindControllerViewsOnce() {
        if (isBindingControls) return
        isBindingControls = true
        bindControllerViewsExact()
        mainHandler.postDelayed({ isBindingControls = false }, 500)
    }

    private fun setupControlListenersExact() {
        btnBack?.setOnClickListener { if (!isLocked) finish() }
        btnPip?.setOnClickListener {
            if (!isLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                userRequestedPip = true
                enterPipMode()
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

        btnPlayPause?.setOnClickListener {
            if (!isLocked) {
                // If error is showing, retry playback
                if (binding.errorView.visibility == View.VISIBLE) {
                    binding.errorView.visibility = View.GONE
                    binding.progressBar.visibility = View.VISIBLE
                    player?.release()
                    player = null
                    setupPlayer()
                } else {
                    // Normal play/pause
                    player?.let { p -> 
                        if (p.isPlaying) p.pause() else p.play() 
                    }
                }
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
    
    private fun setupControlListenersOnce() {
        controlsBindingRunnable?.let { mainHandler.removeCallbacks(it) }
        controlsBindingRunnable = Runnable {
            setupControlListenersExact()
            controlsBindingRunnable = null
        }
        mainHandler.post(controlsBindingRunnable!!)
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
        Toast.makeText(this, when(currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"
            else -> "Fill"
        }, Toast.LENGTH_SHORT).show()
    }

    private fun showPlayerSettingsDialog() {
        val exoPlayer = player ?: return 
        try {
            val dialog = com.livetvpro.ui.player.settings.PlayerSettingsDialog(this, exoPlayer)
            dialog.show()
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open settings", Toast.LENGTH_SHORT).show()
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
            binding.lockOverlay.visibility = View.VISIBLE
            binding.lockOverlay.isClickable = true
            binding.lockOverlay.isFocusable = true
            showUnlockButton()
            btnLock?.setImageResource(R.drawable.ic_lock_closed)
            Toast.makeText(this, "Controls locked", Toast.LENGTH_SHORT).show()
        } else {
            binding.playerView.useController = true
            binding.lockOverlay.visibility = View.GONE
            hideUnlockButton()
            btnLock?.setImageResource(R.drawable.ic_lock_open)
            binding.playerView.showController()
            Toast.makeText(this, "Controls unlocked", Toast.LENGTH_SHORT).show()
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

    // ==================== PiP METHODS ====================

    private fun isPiPSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun isInPip(): Boolean {
        if (!isPiPSupported()) return false
        return isInPictureInPictureMode
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePictureInPictureActions(
        iconId: Int,
        resTitle: Int,
        controlType: Int,
        requestCode: Int
    ): Boolean {
        return try {
            val actions = ArrayList<RemoteAction>()
            val intent = PendingIntent.getBroadcast(
                this,
                requestCode,
                // Add .setPackage(packageName) to ensure it hits your app correctly
                Intent(ACTION_MEDIA_CONTROL).setPackage(packageName).putExtra(EXTRA_CONTROL_TYPE, controlType),
                PendingIntent.FLAG_IMMUTABLE
            )
            val icon = Icon.createWithResource(this, iconId)
            val title = getString(resTitle)
            actions.add(RemoteAction(icon, title, title, intent))
            
            (mPictureInPictureParamsBuilder as PictureInPictureParams.Builder).setActions(actions)
            setPictureInPictureParams((mPictureInPictureParamsBuilder as PictureInPictureParams.Builder).build())
            true
        } catch (e: IllegalStateException) {
            Timber.e(e, "Error updating PiP actions")
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipParamsBasic() {
        if (!isInPipMode) {
            try {
                val format = player?.videoFormat
                val width = format?.width ?: 16
                val height = format?.height ?: 9
                val ratio = if (width > 0 && height > 0) Rational(width, height) else Rational(16, 9)

                val builder = mPictureInPictureParamsBuilder as PictureInPictureParams.Builder
                builder.setAspectRatio(ratio)
                
                // Source Rect Hint Logic
                if (binding.playerView.width > 0 && binding.playerView.height > 0) {
                    binding.playerView.getGlobalVisibleRect(pipSourceRect)
                }
                
                if (!pipSourceRect.isEmpty) {
                    builder.setSourceRectHint(pipSourceRect)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(false)
                    builder.setSeamlessResizeEnabled(true)
                }

                setPictureInPictureParams(builder.build())
            } catch (e: Exception) {
                Timber.e(e, "PiP Error")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return
        }

        // Check PiP permission
        val appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        if (AppOpsManager.MODE_ALLOWED != appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                android.os.Process.myUid(),
                packageName
            )) {
            val intent = Intent(
                "android.settings.PICTURE_IN_PICTURE_SETTINGS",
                Uri.fromParts("package", packageName, null)
            )
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            }
            return
        }

        if (player == null) {
            return
        }

        // Disable controller auto-show and hide it
        binding.playerView.setControllerAutoShow(false)
        binding.playerView.hideController()

        val format = player?.videoFormat

        if (format != null) {
            // Fix for ExoPlayer SurfaceView issue
            val videoSurfaceView = binding.playerView.videoSurfaceView
            if (videoSurfaceView is SurfaceView) {
                videoSurfaceView.holder.setFixedSize(format.width, format.height)
            }

            var rational = Rational(format.width, format.height)
            
            // Handle expanded PiP for extreme aspect ratios (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_EXPANDED_PICTURE_IN_PICTURE) &&
                (rational.toFloat() > rationalLimitWide.toFloat() || 
                 rational.toFloat() < rationalLimitTall.toFloat())) {
                (mPictureInPictureParamsBuilder as PictureInPictureParams.Builder)
                    .setExpandedAspectRatio(rational)
            }
            
            // Clamp aspect ratio to acceptable limits
            if (rational.toFloat() > rationalLimitWide.toFloat()) {
                rational = rationalLimitWide
            } else if (rational.toFloat() < rationalLimitTall.toFloat()) {
                rational = rationalLimitTall
            }

            (mPictureInPictureParamsBuilder as PictureInPictureParams.Builder)
                .setAspectRatio(rational)
        }

        enterPictureInPictureMode(
            (mPictureInPictureParamsBuilder as PictureInPictureParams.Builder).build()
        )
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
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode

        if (isInPipMode) {
            // ENTERING PIP
            binding.playerView.hideController()
            setSubtitleTextSizePiP()
        } else {
            // EXITING PIP
            setSubtitleTextSize()
            binding.playerView.setControllerAutoShow(true)
            
            // Handle finish on back press from PiP
            if (!userRequestedPip && lifecycle.currentState == Lifecycle.State.CREATED) {
                finish()
                return
            }
            userRequestedPip = false
            
            if (isFinishing) return

            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            applyOrientationSettings(isLandscape)

            if (isLocked) {
                binding.playerView.useController = false
                binding.lockOverlay.visibility = View.VISIBLE
                showUnlockButton()
            } else {
                binding.playerView.useController = true
                if (player?.isPlaying == true) {
                    toggleSystemUi(false)
                } else {
                    binding.playerView.post { 
                        binding.playerView.showController() 
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Auto-enter PiP when user leaves the app while video is playing
        if (!isInPipMode && player?.isPlaying == true && isPiPSupported()) {
            userRequestedPip = true
            enterPipMode()
        }
    }

    private fun toggleSystemUi(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (show) {
                controller?.show(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                )
            } else {
                controller?.hide(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                )
            }
        } else {
            @Suppress("DEPRECATION")
            if (show) {
                binding.playerView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            } else {
                binding.playerView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
            }
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


