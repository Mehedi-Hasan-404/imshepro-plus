package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import timber.log.Timber

/**
 * ChannelPlayerActivity
 *
 * This Activity hosts the PlayerView on top and related channels below in portrait.
 * When entering PiP we hide related UI, compute the PlayerView window rect and aspect,
 * and call enterPictureInPictureMode(...) so PiP contains only the video.
 */
class ChannelPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelPlayerBinding

    private var player: ExoPlayer? = null
    private lateinit var relatedChannelsAdapter: RelatedChannelAdapter
    private lateinit var channel: Channel

    private var isFullscreen = false
    private var isLocked = false

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"

        fun start(context: Context, channel: Channel) {
            val intent = Intent(context, ChannelPlayerActivity::class.java)
            intent.putExtra(EXTRA_CHANNEL, channel)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            Timber.e("No channel provided to ChannelPlayerActivity")
            finish()
            return
        }

        binding.progressBar.visibility = View.GONE

        setupPlayer()
        setupCustomControls()
        setupRelatedChannels()
        loadRelatedChannels()
    }

    private fun setupPlayer() {
        try {
            // release previous instance if any (we will create fresh for this activity)
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
                            // keep layout stable - we will use videoSize for PiP aspect when available
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
                            // update UI icons if you need
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "Failed to setup player")
            showError("Failed to initialize player")
        }
    }

    private fun setupCustomControls() {
        // Set channel name in custom player controls if present
        val channelNameView = binding.playerView.findViewById<android.widget.TextView>(
            resources.getIdentifier("exo_channel_name", "id", packageName)
        )
        channelNameView?.text = channel.name

        // Back button inside controller
        val backButton = binding.playerView.findViewById<android.widget.ImageButton>(
            resources.getIdentifier("exo_back", "id", packageName)
        )
        backButton?.setOnClickListener { finish() }

        // Fullscreen button inside controller
        val fullscreenButton = binding.playerView.findViewById<android.widget.ImageButton>(
            resources.getIdentifier("exo_fullscreen", "id", packageName)
        )
        fullscreenButton?.setOnClickListener { toggleFullscreen() }

        // PiP button inside controller (if provided in controller layout)
        val pipButton = binding.playerView.findViewById<android.widget.ImageButton>(
            resources.getIdentifier("exo_pip", "id", packageName)
        )
        pipButton?.setOnClickListener { requestEnterPip() }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            binding.relatedChannelsContainer.visibility = View.GONE
            val fullscreenButton = binding.playerView.findViewById<android.widget.ImageButton>(
                resources.getIdentifier("exo_fullscreen", "id", packageName)
            )
            fullscreenButton?.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            binding.relatedChannelsContainer.visibility = View.VISIBLE
            val fullscreenButton = binding.playerView.findViewById<android.widget.ImageButton>(
                resources.getIdentifier("exo_fullscreen", "id", packageName)
            )
            fullscreenButton?.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { relatedChannel ->
            switchChannel(relatedChannel)
        }

        binding.relatedChannelsRecycler.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                this@ChannelPlayerActivity,
                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                false
            )
            adapter = relatedChannelsAdapter
            setHasFixedSize(true)
        }
    }

    private fun loadRelatedChannels() {
        // Use your ViewModel / repository method to load related channels.
        // For this snippet, assume you have a method viewModel.loadRelatedChannels(...)
        // and observe viewModel.relatedChannels like in your original code.
        // Here, we'll just ensure the related section is hidden/shown depending on adapter list.
        relatedChannelsAdapter.submitList(emptyList()) // fallback; replace with real data when integrating
        binding.relatedChannelsContainer.visibility =
            if (relatedChannelsAdapter.currentList.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun switchChannel(newChannel: Channel) {
        Timber.d("Switching to channel: ${newChannel.name}")
        player?.release()
        player = null
        channel = newChannel

        val channelNameView = binding.playerView.findViewById<android.widget.TextView>(
            resources.getIdentifier("exo_channel_name", "id", packageName)
        )
        channelNameView?.text = channel.name

        setupPlayer()
    }

    /**
     * User-visible entry point for PiP: hide non-video UI first, then enter PiP with PlayerView rect hint.
     */
    fun requestEnterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Timber.w("PiP not available on this device")
            return
        }

        // 1) Hide non-video UI immediately
        hideNonVideoUiForPip()

        // 2) Post to next frame so layout changes apply and PlayerView coordinates are up-to-date
        binding.playerView.post {
            enterPipUsingPlayerViewRect()
        }
    }

    private fun hideNonVideoUiForPip() {
        // Hide anything that should not be visible in PiP
        binding.relatedChannelsContainer.visibility = View.GONE
        binding.lockOverlay.visibility = View.GONE
        binding.errorView.visibility = View.GONE
        binding.progressBar.visibility = View.GONE

        // Hide the controller UI so it does not appear in PiP
        binding.playerView.useController = false
        binding.playerView.hideController()
    }

    private fun restoreUiAfterPip() {
        binding.relatedChannelsContainer.visibility = View.VISIBLE
        binding.lockOverlay.visibility = View.GONE
        binding.errorView.visibility = View.GONE
        binding.progressBar.visibility = View.GONE

        binding.playerView.useController = true
    }

    private fun enterPipUsingPlayerViewRect() {
        // Guard
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // compute PlayerView rect in window coordinates
                val loc = IntArray(2)
                binding.playerView.getLocationInWindow(loc)
                val left = loc[0]
                val top = loc[1]
                val right = left + binding.playerView.width
                val bottom = top + binding.playerView.height
                val sourceRect = Rect(left, top, right, bottom)

                // compute aspect ratio - prefer actual video size if available
                val videoWidth = player?.videoSize?.width ?: binding.playerView.width.takeIf { it > 0 } ?: 16
                val videoHeight = player?.videoSize?.height ?: binding.playerView.height.takeIf { it > 0 } ?: 9
                val aspect = Rational(videoWidth.coerceAtLeast(1), videoHeight.coerceAtLeast(1))

                val params = PictureInPictureParams.Builder()
                    .setSourceRectHint(sourceRect)
                    .setAspectRatio(aspect)
                    .build()

                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                Timber.e(e, "Failed to enter PiP mode")
                // If PiP fails, restore UI so user can continue
                restoreUiAfterPip()
            }
        } else {
            try {
                enterPictureInPictureMode()
            } catch (e: Exception) {
                Timber.e(e, "Failed to enter PiP mode (pre-O)")
                restoreUiAfterPip()
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // In PiP: keep only the video
            binding.playerView.useController = false
            binding.relatedChannelsContainer.visibility = View.GONE
            binding.lockOverlay.visibility = View.GONE
            binding.errorView.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            supportActionBar?.hide()
        } else {
            // Out of PiP: restore UI
            supportActionBar?.show()
            restoreUiAfterPip()
            // If fullscreen was active, keep that behavior
            binding.playerView.useController = !isLocked
        }
    }

    private fun showError(message: String) {
        binding.errorView.visibility = View.VISIBLE
        binding.errorText.text = message
        binding.progressBar.visibility = View.GONE
    }

    private fun hideSystemUI() {
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
        // Important: don't release player when entering PiP - only release when app is really finished
        if (!isInPictureInPictureMode) {
            // If you want playback to continue in PiP, do not release here.
            // If your flow expects release, call player?.release() here.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // safe release on destroy
        player?.release()
        player = null
    }
}
