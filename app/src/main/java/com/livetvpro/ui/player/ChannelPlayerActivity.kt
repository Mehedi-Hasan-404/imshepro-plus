package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
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
import androidx.media3.ui.TimeBar
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@UnstableApi
@AndroidEntryPoint
class ChannelPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private lateinit var channel: Channel

    // Controller Views
    private var btnBack: ImageButton? = null
    private var btnPip: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var btnMute: ImageButton? = null
    private var btnLock: ImageButton? = null
    private var btnRewind: ImageButton? = null
    private var btnPlayPause: ImageButton? = null
    private var btnForward: ImageButton? = null
    private var btnFullscreen: ImageButton? = null
    private var btnAspectRatio: ImageButton? = null
    private var tvChannelName: TextView? = null
    private var timeBar: TimeBar? = null

    // State flags
    private var isInPipMode = false
    private var isLocked = false
    private var lastVolume = 1f
    private val skipMs = 10_000L
    private var userRequestedPip = false

    // Set default resize mode to FIT for portrait start
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideUnlockButtonRunnable = Runnable {
        binding.unlockButton.visibility = View.GONE
    }

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"
        fun start(context: Context, channel: Channel) {
            val intent = Intent(context, ChannelPlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. FIX: Configure Window for Fullscreen + Cutout support immediately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = 
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Ensure content draws behind system bars for true fullscreen positioning
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }

        val currentOrientation = resources.configuration.orientation
        adjustLayoutForOrientation(currentOrientation == Configuration.ORIENTATION_LANDSCAPE)

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            finish()
            return
        }

        binding.progressBar.visibility = View.GONE

        setupPlayer()
        bindControllerViewsExact()

        tvChannelName?.text = channel.name

        setupControlListenersExact()
        setupPlayerViewInteractions()
        setupLockOverlay()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        adjustLayoutForOrientation(isLandscape)
    }

    private fun adjustLayoutForOrientation(isLandscape: Boolean) {
        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams

        if (isLandscape) {
            // 1. FIX: Ensure immersive mode covers cutout
            hideSystemUI()

            binding.playerView.hideController()
            binding.playerView.controllerAutoShow = false

            // 2. Start from Fill/Zoom
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

            params.dimensionRatio = null
            params.height = ConstraintLayout.LayoutParams.MATCH_PARENT
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID

            binding.relatedChannelsSection.visibility = View.GONE
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            // Restore System UI Visibility
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

            // Restore controller behavior
            binding.playerView.controllerAutoShow = true

            // Force FIT mode in Portrait
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

            params.dimensionRatio = "16:9"
            params.height = 0
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET

            binding.relatedChannelsSection.visibility = View.VISIBLE
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
        }

        binding.playerContainer.layoutParams = params
    }

    override fun onResume() {
        super.onResume()
        // Re-apply immersive mode on resume if in landscape
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            hideSystemUI()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPipMode) {
            releasePlayer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun releasePlayer() {
        player?.let {
            try {
                it.pause()
                it.release()
            } catch (t: Throwable) {
                Timber.w(t, "releasePlayer")
            }
        }
        player = null
    }

    private fun setupPlayer() {
        player?.release()

        trackSelector = DefaultTrackSelector(this)

        try {
            player = ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector!!)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(this).setDataSourceFactory(
                        DefaultHttpDataSource.Factory()
                            .setUserAgent("LiveTVPro/1.0")
                            .setConnectTimeoutMs(30_000)
                            .setReadTimeoutMs(30_000)
                            .setAllowCrossProtocolRedirects(true)
                    )
                ).build().also { exo ->
                    binding.playerView.player = exo
                    // Initial resize mode is set by adjustLayoutForOrientation in onCreate

                    val mediaItem = MediaItem.fromUri(channel.streamUrl)
                    exo.setMediaItem(mediaItem)
                    exo.prepare()
                    exo.playWhenReady = true

                    exo.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) {
                                updatePlayPauseIcon(exo.playWhenReady)
                                binding.progressBar.visibility = View.GONE
                            } else if (playbackState == Player.STATE_BUFFERING) {
                                binding.progressBar.visibility = View.VISIBLE
                            }
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            updatePlayPauseIcon(isPlaying)
                        }
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            super.onVideoSizeChanged(videoSize)
                            updatePipParams(videoSize.width, videoSize.height)
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "Error creating ExoPlayer")
        }

        binding.playerView.apply {
            useController = true
            // controllerAutoShow is managed by adjustLayoutForOrientation
            controllerShowTimeoutMs = 5000
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        if (isPlaying) {
            btnPlayPause?.setImageResource(R.drawable.ic_pause)
        } else {
            btnPlayPause?.setImageResource(R.drawable.ic_play)
        }
    }

    private fun bindControllerViewsExact() {
        with(binding.playerView) {
            btnBack = findViewById(R.id.exo_back)
            btnPip = findViewById(R.id.exo_pip)
            btnSettings = findViewById(R.id.exo_settings)
            btnMute = findViewById(R.id.exo_mute)
            btnLock = findViewById(R.id.exo_lock)
            btnRewind = findViewById(R.id.exo_rewind)
            btnPlayPause = findViewById(R.id.exo_play_pause)
            btnForward = findViewById(R.id.exo_forward)
            btnFullscreen = findViewById(R.id.exo_fullscreen)
            btnAspectRatio = findViewById(R.id.exo_aspect_ratio)

            tvChannelName = findViewById(R.id.exo_channel_name)

            val tbId = resources.getIdentifier("exo_progress", "id", packageName)
            if (tbId != 0) timeBar = findViewById(tbId)
        }

        btnBack?.setImageResource(R.drawable.ic_arrow_back)
        btnPip?.setImageResource(R.drawable.ic_pip)
        btnSettings?.setImageResource(R.drawable.ic_settings)
        btnMute?.setImageResource(R.drawable.ic_volume_up)
        btnLock?.setImageResource(R.drawable.ic_lock_open)
        btnRewind?.setImageResource(R.drawable.ic_skip_backward)
        btnPlayPause?.setImageResource(R.drawable.ic_pause)
        btnForward?.setImageResource(R.drawable.ic_skip_forward)
        btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)

        btnAspectRatio?.visibility = View.VISIBLE
        btnPip?.visibility = View.VISIBLE
        btnFullscreen?.visibility = View.VISIBLE
    }

    private fun setupControlListenersExact() {
        btnBack?.setOnClickListener { if (!isLocked) finish() }

        btnPip?.setOnClickListener {
            if (!isLocked) {
                userRequestedPip = true
                enterPipMode()
            }
        }

        btnSettings?.setOnClickListener {
            if (!isLocked) showQualityDialog()
        }

        btnAspectRatio?.setOnClickListener {
            if (!isLocked) {
                toggleAspectRatio()
            }
        }

        btnMute?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            player?.let {
                if (it.volume > 0f) {
                    lastVolume = it.volume
                    it.volume = 0f
                    btnMute?.setImageResource(R.drawable.ic_volume_off)
                } else {
                    it.volume = if (lastVolume > 0f) lastVolume else 1f
                    btnMute?.setImageResource(R.drawable.ic_volume_up)
                }
            }
        }

        btnLock?.setOnClickListener { toggleLock() }

        btnRewind?.setOnClickListener {
            if (!isLocked) player?.seekTo((player?.currentPosition ?: 0) - skipMs)
        }

        btnPlayPause?.setOnClickListener {
            if (!isLocked) {
                if (player?.isPlaying == true) player?.pause() else player?.play()
            }
        }

        btnForward?.setOnClickListener {
            if (!isLocked) player?.seekTo((player?.currentPosition ?: 0) + skipMs)
        }

        btnFullscreen?.setOnClickListener { if (!isLocked) toggleFullscreen() }
    }

    private fun toggleAspectRatio() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (!isLandscape) {
            Toast.makeText(this, "Rotate to landscape to change aspect ratio", Toast.LENGTH_SHORT).show()
            return
        }

        // Cycle through modes: ZOOM (Fill) -> FIT -> FILL -> ZOOM
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> {
                Toast.makeText(this, "Fit", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> {
                Toast.makeText(this, "Fill", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            else -> { // RESIZE_MODE_FILL
                Toast.makeText(this, "Zoom", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        }
        binding.playerView.resizeMode = currentResizeMode
    }

    private fun showQualityDialog() {
        if (trackSelector == null || player == null) return

        TrackSelectionDialogBuilder(
            /* context = */ this,
            /* title = */ "Select Video Quality",
            /* player = */ player!!,
            /* trackType = */ C.TRACK_TYPE_VIDEO
        ).build().show()
    }

    private fun setupPlayerViewInteractions() {
        binding.playerView.setOnClickListener(null)
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

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return

        // Hide UI for PiP
        binding.relatedChannelsSection.visibility = View.GONE
        binding.playerView.useController = false
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE

        val format = player?.videoFormat
        val width = format?.width ?: 16
        val height = format?.height ?: 9
        updatePipParams(width, height, enter = true)
    }

    private fun updatePipParams(width: Int, height: Int, enter: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ratio = if (width > 0 && height > 0) Rational(width, height) else Rational(16, 9)
            val builder = PictureInPictureParams.Builder()
            builder.setAspectRatio(ratio)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(true)
                builder.setSeamlessResizeEnabled(true)
            }

            try {
                if (enter) {
                    if (enterPictureInPictureMode(builder.build())) isInPipMode = true
                } else if (isInPipMode) {
                    setPictureInPictureParams(builder.build())
                }
            } catch (e: Exception) {
                Timber.e("PiP Error: ${e.message}")
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode

        if (isInPipMode) {
            binding.relatedChannelsSection.visibility = View.GONE
            binding.playerView.useController = false
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        } else {
            userRequestedPip = false

            // Restore Layout
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            adjustLayoutForOrientation(isLandscape)

            // Restore controls state based on lock
            if (isLocked) {
                binding.playerView.useController = false
                binding.lockOverlay.visibility = View.VISIBLE
                showUnlockButton()
            } else {
                binding.playerView.useController = true
                binding.playerView.showController()
                binding.lockOverlay.visibility = View.GONE
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPipMode && player?.isPlaying == true && !userRequestedPip) {
            userRequestedPip = true
            enterPipMode()
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

    private fun hideSystemUI() {
        // FIX: Handle Notch/Cutout Mode to remove black bars
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // Standard immersive flags
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
}
