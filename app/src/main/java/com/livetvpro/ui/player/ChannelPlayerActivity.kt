package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.GridLayoutManager
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import com.livetvpro.ui.player.PlayerViewModel
import timber.log.Timber

@UnstableApi
class ChannelPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var player: ExoPlayer? = null
    private lateinit var channel: Channel
    private lateinit var relatedChannelsAdapter: RelatedChannelAdapter

    // controller views (nullable)
    private var exoBack: ImageButton? = null
    private var exoChannelName: View? = null
    private var exoPip: ImageButton? = null
    private var exoSettings: ImageButton? = null
    private var exoMute: ImageButton? = null
    private var exoLock: ImageButton? = null
    private var exoAspectRatio: ImageButton? = null
    private var exoPlayPause: ImageButton? = null
    private var exoFullscreen: ImageButton? = null
    private var exoRewind: ImageButton? = null
    private var exoForward: ImageButton? = null

    // state
    private var isFullscreen = false
    private var isLocked = false
    private var isMuted = false
    private var currentAspectRatioIndex = 0

    private val aspectRatios = listOf(
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )

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
        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on & hide system UI
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        // Start in portrait
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            Timber.e("No channel provided")
            finish()
            return
        }

        // Prepare UI: set initial heights to avoid layout jumps if needed
        binding.playerView.post {
            val screenWidth = resources.displayMetrics.widthPixels
            val height16by9 = (screenWidth * 9f / 16f).toInt()
            val playerParams = binding.playerView.layoutParams
            playerParams.height = height16by9
            binding.playerView.layoutParams = playerParams

            val containerParams = binding.playerContainer.layoutParams
            containerParams.height = height16by9
            binding.playerContainer.layoutParams = containerParams
        }

        binding.progressBar.visibility = View.GONE

        setupPlayer()
        setupCustomControls()
        setupRelatedChannels()
        loadRelatedChannels()
    }

    private fun updateResizeMode() {
        binding.playerView.resizeMode =
            if (isFullscreen) aspectRatios[currentAspectRatioIndex]
            else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    private fun setupPlayer() {
        try {
            player?.release()
            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(
                            DefaultHttpDataSource.Factory()
                                .setUserAgent("LiveTVPro/1.0")
                                .setConnectTimeoutMs(30000)
                                .setReadTimeoutMs(30000)
                                .setAllowCrossProtocolRedirects(true)
                        )
                )
                .build()
                .also { exoPlayer ->
                    binding.playerView.player = exoPlayer
                    updateResizeMode()
                    binding.playerView.useController = true
                    binding.playerView.controllerAutoShow = true
                    binding.playerView.controllerShowTimeoutMs = 5000

                    val mediaItem = MediaItem.fromUri(channel.streamUrl)
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true

                    exoPlayer.addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            super.onVideoSizeChanged(videoSize)
                            // Keep layout stable (we set a fixed 16:9 container)
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar.visibility = View.VISIBLE
                                    binding.errorView.visibility = View.GONE
                                }
                                Player.STATE_READY -> {
                                    binding.progressBar.visibility = View.GONE
                                    binding.errorView.visibility = View.GONE
                                    updatePlayPauseIcon()
                                }
                                Player.STATE_ENDED -> showError("Stream ended")
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            binding.progressBar.visibility = View.GONE
                            val msg = when (error.errorCode) {
                                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Server error"
                                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Network error"
                                else -> "Playback failed"
                            }
                            showError(msg)
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            updatePlayPauseIcon()
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "Failed to setup player")
            showError("Failed to initialize player")
        }
    }

    private fun setupCustomControls() {
        // find controller views inside PlayerView (IDs come from your controller layout)
        exoBack = binding.playerView.findViewById(R.id.exo_back)
        exoChannelName = binding.playerView.findViewById(R.id.exo_channel_name)
        exoPip = binding.playerView.findViewById(R.id.exo_pip)
        exoSettings = binding.playerView.findViewById(R.id.exo_settings)
        exoMute = binding.playerView.findViewById(R.id.exo_mute)
        exoLock = binding.playerView.findViewById(R.id.exo_lock)
        exoAspectRatio = binding.playerView.findViewById(R.id.exo_aspect_ratio)
        exoPlayPause = binding.playerView.findViewById(R.id.exo_play_pause)
        exoFullscreen = binding.playerView.findViewById(R.id.exo_fullscreen)
        exoRewind = binding.playerView.findViewById(R.id.exo_rewind)
        exoForward = binding.playerView.findViewById(R.id.exo_forward)

        val lockOverlay = binding.lockOverlay
        val unlockButton = binding.unlockButton

        (exoChannelName as? android.widget.TextView)?.text = channel.name

        // Fullscreen button - always visible
        exoFullscreen?.visibility = View.VISIBLE
        exoFullscreen?.setImageResource(R.drawable.ic_fullscreen)

        updateResizeMode()

        // Back button
        exoBack?.setOnClickListener {
            if (isFullscreen) exitFullscreen() else finish()
        }

        // PiP button - show only if device supports it
        exoPip?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            ) {
                visibility = View.VISIBLE
                setOnClickListener { enterPipMode() }
            } else {
                visibility = View.GONE
            }
        }

        exoSettings?.setOnClickListener { /* TODO: settings */ }

        exoMute?.setOnClickListener { toggleMute() }

        // Lock button
        exoLock?.setOnClickListener {
            isLocked = true
            binding.playerView.useController = false
            lockOverlay.visibility = View.VISIBLE
            unlockButton.visibility = View.VISIBLE
            exoLock?.setImageResource(R.drawable.ic_lock_closed)
        }

        // Unlock button
        unlockButton.setOnClickListener {
            isLocked = false
            binding.playerView.useController = true
            lockOverlay.visibility = View.GONE
            unlockButton.visibility = View.GONE
            exoLock?.setImageResource(R.drawable.ic_lock_open)
        }

        // Aspect ratio
        exoAspectRatio?.setOnClickListener {
            currentAspectRatioIndex = (currentAspectRatioIndex + 1) % aspectRatios.size
            updateResizeMode()
        }

        // Play/Pause
        exoPlayPause?.setOnClickListener {
            player?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
        }

        // Fullscreen toggle
        exoFullscreen?.setOnClickListener {
            toggleFullscreen()
        }

        // Retry button
        binding.retryButton.setOnClickListener {
            binding.errorView.visibility = View.GONE
            setupPlayer()
        }
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { relatedChannel ->
            if (!isLocked) {
                switchChannel(relatedChannel)
            }
        }

        binding.relatedChannelsRecycler.apply {
            layoutManager = GridLayoutManager(this@ChannelPlayerActivity, 3)
            adapter = relatedChannelsAdapter
            setHasFixedSize(true)
        }
    }

    private fun loadRelatedChannels() {
        viewModel.loadRelatedChannels(channel.categoryId, channel.id)
        viewModel.relatedChannels.observe(this) { channels ->
            relatedChannelsAdapter.submitList(channels)
            binding.relatedCount.text = channels.size.toString()
            binding.relatedChannelsSection.visibility = if (channels.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun switchChannel(newChannel: Channel) {
        channel = newChannel
        (exoChannelName as? android.widget.TextView)?.text = channel.name

        player?.stop()
        val mediaItem = MediaItem.fromUri(channel.streamUrl)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.playWhenReady = true

        binding.errorView.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun toggleFullscreen() {
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        isFullscreen = true
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Hide related channels
        binding.relatedChannelsSection.visibility = View.GONE

        // Make player fill screen
        val params = binding.playerContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        params.dimensionRatio = null
        params.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        binding.playerContainer.layoutParams = params

        exoFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
    }

    private fun exitFullscreen() {
        isFullscreen = false
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Show related channels
        binding.relatedChannelsSection.visibility = View.VISIBLE

        // Restore 16:9
        val params = binding.playerContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        params.dimensionRatio = "16:9"
        params.bottomToTop = R.id.related_channels_section
        params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        binding.playerContainer.layoutParams = params

        exoFullscreen?.setImageResource(R.drawable.ic_fullscreen)
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            exoMute?.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        }
    }

    private fun updatePlayPauseIcon() {
        player?.let {
            exoPlayPause?.setImageResource(if (it.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }
    }

    /**
     * PROPER PiP implementation that captures ONLY the player view:
     * 1) Hide controllers
     * 2) Force a short delay for UI to update
     * 3) Build params with sourceRectHint (PlayerView location) + aspect ratio
     * 4) Enter PiP
     */
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Timber.w("PiP not supported on this Android version")
            return
        }

        try {
            // 1) Hide controls immediately BEFORE entering PiP
            binding.playerView.useController = false
            binding.playerView.hideController()

            // 2) Give the UI a short moment to hide controls
            binding.playerView.postDelayed({
                try {
                    // 3a) Compute aspect ratio from actual video size if available
                    val playerVideoWidth = player?.videoSize?.width ?: 16
                    val playerVideoHeight = player?.videoSize?.height ?: 9
                    val aspectRatio = if (playerVideoWidth > 0 && playerVideoHeight > 0) {
                        Rational(playerVideoWidth, playerVideoHeight)
                    } else {
                        Rational(16, 9)
                    }

                    // 3b) Get PlayerView's on-screen rect so PiP captures only that view
                    val sourceRectHint = Rect()
                    binding.playerView.getGlobalVisibleRect(sourceRectHint)

                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(aspectRatio)
                        .setSourceRectHint(sourceRectHint)
                        .build()

                    // 4) Enter PiP
                    enterPictureInPictureMode(params)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to enter PiP mode")
                    binding.playerView.useController = true
                }
            }, 120L) // small delay (120ms) to ensure controller is hidden
        } catch (e: Exception) {
            Timber.e(e, "Failed to start PiP flow")
            binding.playerView.useController = true
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

        if (isInPictureInPictureMode) {
            // Hide UI that should not appear inside PiP
            binding.playerView.useController = false
            binding.relatedChannelsSection.visibility = View.GONE
            binding.lockOverlay.visibility = View.GONE
            binding.errorView.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
        } else {
            // Restore UI when exiting PiP
            if (!isFullscreen) binding.relatedChannelsSection.visibility = View.VISIBLE
            binding.playerView.useController = !isLocked
            // progressBar and errorView restored by player listener
        }
    }

    override fun onUserLeaveHint() {
        // When user leaves the activity (home pressed), optionally auto-enter PiP
        // Only auto-enter if the device supports PiP and we are not locked
        if (!isLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            // Optionally auto-enter PiP — comment out if you want only the PiP button to trigger it
            enterPipMode()
        }
        super.onUserLeaveHint()
    }

    private fun showError(message: String) {
        binding.errorView.visibility = View.VISIBLE
        binding.errorText.text = message
        binding.progressBar.visibility = View.GONE
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) {
            player?.release()
            player = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
